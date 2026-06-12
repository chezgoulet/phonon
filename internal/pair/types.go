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
package pair

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"math/big"
	"os"
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

	// In-memory pending pairings: device_id → pending
	pending map[string]*PendingPair

	// In-memory paired devices: device_id → paired
	paired map[string]*PairedDevice
}

// NewManager creates a new pairing manager. If coordKeyPath exists, the
// keypair is loaded from disk; otherwise a new Ed25519 keypair is generated
// and saved.
func NewManager(coordKeyPath string) (*Manager, error) {
	priv, err := loadOrGenerateKey(coordKeyPath)
	if err != nil {
		return nil, fmt.Errorf("pair: coordinator key: %w", err)
	}

	m := &Manager{
		coordKey: priv,
		pending:  make(map[string]*PendingPair),
		paired:   make(map[string]*PairedDevice),
	}

	// Start background cleanup goroutine
	go m.cleanupLoop()

	return m, nil
}

// CoordinatorPublicKey returns the coordinator's Ed25519 public key hex.
func (m *Manager) CoordinatorPublicKey() string {
	pub := m.coordKey.Public().(ed25519.PublicKey)
	return hex.EncodeToString(pub)
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
	defer m.mu.Unlock()

	p, ok := m.pending[deviceID]
	if !ok {
		return nil, fmt.Errorf("no pending pairing for device %q", deviceID)
	}

	if p.Expired() {
		delete(m.pending, deviceID)
		return nil, fmt.Errorf("pairing code expired for device %q", deviceID)
	}

	if p.Code != code {
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
	defer m.mu.Unlock()
	delete(m.paired, deviceID)
	delete(m.pending, deviceID)
}

// cleanupLoop periodically removes expired pending pairings.
func (m *Manager) cleanupLoop() {
	ticker := time.NewTicker(CodeCleanupInterval)
	defer ticker.Stop()

	for range ticker.C {
		m.cleanup()
	}
}

// cleanup removes all expired pending pairings. Must hold m.mu.
func (m *Manager) cleanup() {
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
