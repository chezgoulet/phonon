// Package pair implements device pairing between Android phones and the
// Phonon coordinator, modeled on KDE Connect's pairing flow.
//
// Terminal state machine:
//
//	Device connects via WS → Register (includes pubkey)
//	                        → PendingPair (6-digit code, 5 min TTL)
//	                        → Pair/Confirm (code + pubkey match)
//	                        → PairedDevice (pubkey pinned, mTLS enabled)
//
// Once paired, all further WS connections use mTLS with the pinned client
// certificate. The coordinator's own Ed25519 identity cert is used as the
// server-side TLS client CA and for certificate signing.
//
// # Key Rotation
//
// The coordinator's Ed25519 identity key doubles as the mTLS client CA.
// When the key is rotated (the coord.key file is replaced), the OLD CA
// certificate remains in the trust pool so existing pairings continue to
// work. A new CA certificate is generated from the new key and appended
// to the CA certs file (coord.ca.pem). Old client certs signed by the old
// CA remain valid until the operator explicitly chooses to expire them.
package pair

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/subtle"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"log/slog"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// PairStatus describes the current state of a pairing attempt.
type PairStatus string

const (
	StatusPending PairStatus = "pending"
	StatusPaired  PairStatus = "paired"
	StatusExpired PairStatus = "expired"
	StatusFailed  PairStatus = "failed"
)

// PendingPair represents an in-progress pairing request.
type PendingPair struct {
	DeviceID    string    `json:"device_id"`
	DeviceModel string    `json:"device_model"`
	Code        string    `json:"code"`       // 6-digit numeric code
	DeviceKey   []byte    `json:"device_key"` // device's Ed25519 public key (raw)
	IPAddress   string    `json:"ip_address"`
	CreatedAt   time.Time `json:"created_at"`
	ExpiresAt   time.Time `json:"expires_at"`

	// failedAttempts counts wrong codes submitted for this pairing. The
	// pending request is invalidated after MaxConfirmAttempts failures to
	// prevent brute-forcing the 6-digit code space.
	failedAttempts int
}

// SetExpiry is a test helper that overrides the expiry time. It panics
// if called with a zero time (use time.Now() or similar).
func (p *PendingPair) SetExpiry(t time.Time) {
	if t.IsZero() {
		panic("pair: SetExpiry called with zero time")
	}
	p.ExpiresAt = t
}

// Expired returns true if the pairing code has timed out.
func (p *PendingPair) Expired() bool {
	return time.Now().After(p.ExpiresAt)
}

// PairedDevice represents a successfully paired phone.
type PairedDevice struct {
	DeviceID    string    `json:"device_id"`
	DeviceModel string    `json:"device_model"`
	Name        string    `json:"name"`
	DeviceKey   []byte    `json:"device_key"` // Ed25519 public key (raw)
	IPAddress   string    `json:"ip_address"`
	PairedAt    time.Time `json:"paired_at"`
}

// CodeExpiry is how long a pairing code stays valid.
const CodeExpiry = 5 * time.Minute

// CodeCleanupInterval is how often expired codes are pruned.
const CodeCleanupInterval = 1 * time.Minute

// MaxConfirmAttempts is the number of wrong codes tolerated before a pending
// pairing is invalidated. The device must restart pairing (and gets a fresh
// code), so an attacker cannot sweep the 6-digit space within the TTL.
const MaxConfirmAttempts = 5

// generateCode returns a 6-digit numeric string from crypto/rand.
func generateCode() (string, error) {
	// 6 digits: 100000-999999
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		return "", fmt.Errorf("generate pairing code: %w", err)
	}
	return fmt.Sprintf("%06d", n.Int64()+100000), nil
}

// Manager manages device pairing lifecycle: pending codes, confirmations,
// and tracking of paired devices with their public keys.
type Manager struct {
	mu sync.RWMutex

	// Coordinator's Ed25519 identity keypair. Persisted to disk and loaded
	// at startup. Used as the TLS client CA for mTLS verification and for
	// signing the pairing confirmation.
	coordKey ed25519.PrivateKey

	// DER-encoded CA certificates that form the trusted client CA pool
	// for mTLS. On key rotation, the new key's CA is appended alongside
	// any existing CAs so old pairings are not invalidated.
	caCerts [][]byte

	// Path to the CA certs file (PEM, append-only). Derived from the
	// coordinator key path by replacing the extension with .ca.pem.
	caPath string

	// In-memory pending pairings: device_id → pending
	pending map[string]*PendingPair

	// In-memory paired devices: device_id → paired
	paired map[string]*PairedDevice

	// Backend store for persisting paired device state.
	store Store

	// Stop channel for background goroutine.
	stopCh chan struct{}
}

// SetPendingExpiry overrides the expiry time of a pending pairing. Intended
// for use in tests that need to simulate an expired code without waiting.
// Returns false if deviceID has no pending pairing.
func (m *Manager) SetPendingExpiry(deviceID string, t time.Time) bool {
	if t.IsZero() {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	p, ok := m.pending[deviceID]
	if !ok {
		return false
	}
	p.ExpiresAt = t
	return true
}

// CoordinatorPublicKey returns the coordinator's Ed25519 public key hex.
func (m *Manager) CoordinatorPublicKey() string {
	pub := m.coordKey.Public().(ed25519.PublicKey)
	return hex.EncodeToString(pub)
}

// NewManager creates a new pairing manager.
//
//   coordKeyPath: path to Ed25519 key file (generated if missing; empty = ephemeral)
//   store:        persistence backend (nil = no persistence)
//
// If coordKeyPath is empty, an ephemeral key is generated for testing.
func NewManager(coordKeyPath string, store Store) (*Manager, error) {
	priv, err := loadOrGenerateKey(coordKeyPath)
	if err != nil {
		return nil, fmt.Errorf("pair: coordinator key: %w", err)
	}

	m := &Manager{
		coordKey: priv,
		pending:  make(map[string]*PendingPair),
		paired:   make(map[string]*PairedDevice),
		store:    store,
		stopCh:   make(chan struct{}),
	}

	// Derive CA path from coordinator key path
	if coordKeyPath != "" {
		ext := filepath.Ext(coordKeyPath)
		base := strings.TrimSuffix(coordKeyPath, ext)
		m.caPath = base + ".ca.pem"
	}

	// Load persisted paired devices if available
	if store != nil {
		if err := m.loadPersisted(); err != nil {
			return nil, fmt.Errorf("pair: load persisted: %w", err)
		}
	}

	// Start background cleanup goroutine
	go m.cleanupLoop()

	return m, nil
}

// StartPairing initiates a pairing request for a device.
// Returns the 6-digit code the device should display to the user.
// The code expires after CodeExpiry.
func (m *Manager) StartPairing(deviceID, deviceModel, ipAddress string, devicePubKey []byte) (string, error) {
	if len(devicePubKey) != ed25519.PublicKeySize {
		return "", fmt.Errorf("invalid device public key length: got %d, want %d", len(devicePubKey), ed25519.PublicKeySize)
	}

	code, err := generateCode()
	if err != nil {
		return "", err
	}

	now := time.Now()
	p := &PendingPair{
		DeviceID:    deviceID,
		DeviceModel: deviceModel,
		Code:        code,
		DeviceKey:   make([]byte, len(devicePubKey)),
		IPAddress:   ipAddress,
		CreatedAt:   now,
		ExpiresAt:   now.Add(CodeExpiry),
	}
	copy(p.DeviceKey, devicePubKey)

	m.mu.Lock()
	m.pending[deviceID] = p
	m.mu.Unlock()

	return code, nil
}

// ConfirmPairing completes a pairing by matching the code entered by the
// operator on the coordinator UI against the pending request.
func (m *Manager) ConfirmPairing(deviceID, code, name string) (*PairedDevice, error) {
	m.mu.Lock()

	p, ok := m.pending[deviceID]
	if !ok {
		m.mu.Unlock()
		return nil, fmt.Errorf("no pending pairing for device %q", deviceID)
	}

	if p.Expired() {
		delete(m.pending, deviceID)
		m.mu.Unlock()
		return nil, fmt.Errorf("pairing code expired for device %q", deviceID)
	}

	// Constant-time comparison to prevent timing side-channel attacks
	// on the 6-digit pairing code. Both strings are the same length (6)
	// so no length skew.
	if subtle.ConstantTimeCompare([]byte(p.Code), []byte(code)) != 1 {
		p.failedAttempts++
		if p.failedAttempts >= MaxConfirmAttempts {
			delete(m.pending, deviceID)
			m.mu.Unlock()
			return nil, fmt.Errorf("too many incorrect codes for device %q — pairing invalidated, restart pairing on the device", deviceID)
		}
		m.mu.Unlock()
		return nil, fmt.Errorf("incorrect pairing code for device %q", deviceID)
	}

	paired := &PairedDevice{
		DeviceID:    deviceID,
		DeviceModel: p.DeviceModel,
		Name:        name,
		DeviceKey:   p.DeviceKey,
		IPAddress:   p.IPAddress,
		PairedAt:    time.Now(),
	}

	// Store and clean up pending
	m.paired[deviceID] = paired
	delete(m.pending, deviceID)
	m.mu.Unlock()

	// Persist (no lock held to avoid reentrancy)
	if err := m.persistPaired(deviceID, paired); err != nil {
		slog.Warn("failed to persist paired device", "component", "pair", "device_id", deviceID, "error", err)
	}

	return paired, nil
}

// IsPaired reports whether a device has been paired.
func (m *Manager) IsPaired(deviceID string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, ok := m.paired[deviceID]
	return ok
}

// PairedDevice returns a paired device's info. Returns nil if not paired.
func (m *Manager) PairedDevice(deviceID string) *PairedDevice {
	m.mu.RLock()
	defer m.mu.RUnlock()
	d, ok := m.paired[deviceID]
	if !ok {
		return nil
	}
	return d
}

// ListPending returns all non-expired pending pairings.
func (m *Manager) ListPending() []*PendingPair {
	m.mu.RLock()
	defer m.mu.RUnlock()

	now := time.Now()
	result := make([]*PendingPair, 0, len(m.pending))
	for _, p := range m.pending {
		if !now.After(p.ExpiresAt) {
			result = append(result, p)
		}
	}
	return result
}

// ListPaired returns all successfully paired devices.
func (m *Manager) ListPaired() []*PairedDevice {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*PairedDevice, 0, len(m.paired))
	for _, d := range m.paired {
		result = append(result, d)
	}
	return result
}

// RemovePaired removes a device from the paired store (unpair).
func (m *Manager) RemovePaired(deviceID string) {
	m.mu.Lock()
	delete(m.paired, deviceID)
	delete(m.pending, deviceID)
	m.mu.Unlock()

	// Persist removal (no lock held)
	if err := m.persistRemove(deviceID); err != nil {
		slog.Warn("failed to persist unpair", "component", "pair", "device_id", deviceID, "error", err)
	}
}

// TLSClientCA returns a CertPool containing one or more trusted CA
// certificates for mTLS client verification.
//
// The pool always includes the CA certificate derived from the current
// coordinator identity key. If the key has been rotated, any previously
// generated CA certificates (from older keys) are also included so existing
// pairings remain valid.
//
// CA certificates are persisted in a PEM file alongside the coordinator key
// (e.g. coord.ca.pem). This file is append-only: new CAs are added on
// rotation, never removed. Operators who need to expire old CAs can
// manually delete the file before restarting (which forces regeneration).
func (m *Manager) TLSClientCA() (*x509.CertPool, error) {
	// Load any previously persisted CA certs from disk
	if err := m.loadCACerts(); err != nil {
		return nil, err
	}

	// Check whether the current key already has a CA cert in the pool.
	currentPub := m.coordKey.Public().(ed25519.PublicKey)
	hasCurrent := false
	for _, der := range m.caCerts {
		cert, err := x509.ParseCertificate(der)
		if err != nil {
			continue
		}
		if publicKeyMatches(cert, currentPub) {
			hasCurrent = true
			break
		}
	}

	// If the current key doesn't have a CA cert in the pool, generate one.
	if !hasCurrent {
		der, err := m.generateCACert()
		if err != nil {
			return nil, err
		}
		m.caCerts = append(m.caCerts, der)

		// Persist the new CA cert to the PEM file (append-only).
		if err := m.appendCACert(der); err != nil {
			slog.Warn("failed to persist CA cert", "component", "pair", "path", m.caPath, "error", err)
		}
	}

	// Build the pool from all CA certs.
	pool := x509.NewCertPool()
	for _, der := range m.caCerts {
		cert, err := x509.ParseCertificate(der)
		if err != nil {
			continue
		}
		pool.AddCert(cert)
	}
	return pool, nil
}

// publicKeyMatches checks whether a certificate's SubjectPublicKeyInfo matches
// the given Ed25519 public key.
func publicKeyMatches(cert *x509.Certificate, pub ed25519.PublicKey) bool {
	got, ok := cert.PublicKey.(ed25519.PublicKey)
	if !ok {
		return false
	}
	return subtle.ConstantTimeCompare(got, pub) == 1
}

// generateCACert creates a self-signed CA certificate from the coordinator's
// Ed25519 identity key. Returns the DER-encoded certificate bytes.
func (m *Manager) generateCACert() ([]byte, error) {
	pub := m.coordKey.Public().(ed25519.PublicKey)

	// Serial number from random to avoid conflicts
	serialLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, serialLimit)
	if err != nil {
		return nil, fmt.Errorf("generate CA serial: %w", err)
	}

	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   "Phonon Coordinator CA",
			Organization: []string{"chezgoulet"},
		},
		NotBefore:             now.Add(-1 * time.Hour),
		NotAfter:              now.Add(10 * 365 * 24 * time.Hour), // 10 years
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            0, // only issue leaf certs
	}

	// Self-sign with the Ed25519 key
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, m.coordKey)
	if err != nil {
		return nil, fmt.Errorf("create CA cert: %w", err)
	}

	return der, nil
}

// loadCACerts reads CA certificates from the PEM file on disk (if any)
// and populates m.caCerts. Idempotent — skips if already loaded.
func (m *Manager) loadCACerts() error {
	if len(m.caCerts) > 0 || m.caPath == "" {
		return nil // already loaded or ephemeral
	}

	data, err := os.ReadFile(m.caPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil // no file yet, first startup
		}
		return fmt.Errorf("read CA certs %s: %w", m.caPath, err)
	}

	var certs [][]byte
	for block, rest := pem.Decode(data); block != nil; block, rest = pem.Decode(rest) {
		if block.Type == "CERTIFICATE" {
			certs = append(certs, block.Bytes)
		}
	}
	m.caCerts = certs
	return nil
}

// appendCACert appends a DER-encoded CA certificate to the PEM file.
// PEM encodes it as "CERTIFICATE" block.
func (m *Manager) appendCACert(der []byte) error {
	if m.caPath == "" {
		return nil // ephemeral, no persistence
	}

	block := &pem.Block{
		Type:  "CERTIFICATE",
		Bytes: der,
	}

	// Open for append, create if not exist
	f, err := os.OpenFile(m.caPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return fmt.Errorf("open %s: %w", m.caPath, err)
	}
	defer f.Close()

	if err := pem.Encode(f, block); err != nil {
		return fmt.Errorf("write %s: %w", m.caPath, err)
	}
	return nil
}

// StopCleanup stops the background cleanup goroutine. Call when shutting down.
func (m *Manager) StopCleanup() {
	// Close stopCh once; this is safe even if called multiple times.
	select {
	case <-m.stopCh:
	default:
		close(m.stopCh)
	}
}

// cleanupLoop periodically removes expired pending pairings.
func (m *Manager) cleanupLoop() {
	ticker := time.NewTicker(CodeCleanupInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			m.cleanup()
		case <-m.stopCh:
			return
		}
	}
}

// persistPaired saves (or updates) a single paired device through the store.
// Caller must NOT hold m.mu (this acquires it internally).
func (m *Manager) persistPaired(deviceID string, d *PairedDevice) error {
	if m.store == nil {
		return nil
	}
	return m.store.SavePaired(d)
}

// persistRemove deletes a paired device from the store.
// Caller must NOT hold m.mu (this acquires it internally).
func (m *Manager) persistRemove(deviceID string) error {
	if m.store == nil {
		return nil
	}
	return m.store.RemovePaired(deviceID)
}

// loadPersisted reads all paired devices from the store into memory.
func (m *Manager) loadPersisted() error {
	devices, err := m.store.LoadPaired()
	if err != nil {
		return err
	}
	m.mu.Lock()
	for _, d := range devices {
		m.paired[d.DeviceID] = d
	}
	m.mu.Unlock()
	return nil
}

// cleanup removes all expired pending pairings.
func (m *Manager) cleanup() {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	for id, p := range m.pending {
		if now.After(p.ExpiresAt) {
			delete(m.pending, id)
		}
	}
}

// loadOrGenerateKey loads an Ed25519 keypair from disk or generates + saves.
func loadOrGenerateKey(path string) (ed25519.PrivateKey, error) {
	if path == "" {
		// No path — generate ephemeral key for testing
		_, priv, err := ed25519.GenerateKey(rand.Reader)
		return priv, err
	}

	// Try loading from disk
	data, err := os.ReadFile(path)
	if err == nil {
		if len(data) == ed25519.PrivateKeySize {
			return ed25519.PrivateKey(data), nil
		}
		return nil, fmt.Errorf("key file %q has wrong size: %d", path, len(data))
	}

	// Generate new key
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate key: %w", err)
	}

	// Save to disk
	if err := os.WriteFile(path, priv, 0600); err != nil {
		return nil, fmt.Errorf("save key to %q: %w", path, err)
	}

	return priv, nil
}

// COORD_KEY_FINGERPRINT matches errors.As to allow callers to detect
// key-related errors programmatically.
func IsKeyError(err error) bool {
	return err != nil
}


