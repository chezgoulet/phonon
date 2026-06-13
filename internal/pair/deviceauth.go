// Package pair — device authentication.
//
// Pairing establishes two credentials per device:
//
//  1. The device's Ed25519 public key, pinned at pairing time. Used to
//     verify signed requests (e.g. the pair/status poll that delivers
//     the auth token).
//  2. A random per-device AuthToken (shared secret). The sidecar sends
//     it on heartbeats / model-status / WebSocket connections so the
//     coordinator can authenticate the device; the coordinator sends it
//     on inference requests so the phone can authenticate the
//     coordinator. A paired phone refuses inference without it.
package pair

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"strconv"
	"time"
)

// AuthTokenBytes is the length of the random device auth token.
const AuthTokenBytes = 32

// SignatureWindow is the maximum allowed clock skew for signed
// sidecar requests (pair/status).
const SignatureWindow = 5 * time.Minute

// PairStatusSigPrefix is the domain-separation prefix for the signed
// pair/status request. The signed message is:
//
//	"phonon-pair-status|" + deviceID + "|" + unixTimestamp
//
// Must match the sidecar's DeviceIdentity implementation.
const PairStatusSigPrefix = "phonon-pair-status"

// generateAuthToken returns a hex-encoded random token from crypto/rand.
func generateAuthToken() (string, error) {
	buf := make([]byte, AuthTokenBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("generate auth token: %w", err)
	}
	return hex.EncodeToString(buf), nil
}

// AuthTokenFor returns the auth token for a paired device, or "" if the
// device is not paired.
func (m *Manager) AuthTokenFor(deviceID string) string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if d, ok := m.paired[deviceID]; ok {
		return d.AuthToken
	}
	return ""
}

// Authorize reports whether the presented token matches the paired
// device's auth token. Comparison is constant-time. Returns false for
// unpaired devices, empty tokens, or devices whose stored token is
// empty (fail closed).
func (m *Manager) Authorize(deviceID, token string) bool {
	m.mu.RLock()
	d, ok := m.paired[deviceID]
	m.mu.RUnlock()
	if !ok || token == "" || d.AuthToken == "" {
		return false
	}
	// Length check first; ConstantTimeCompare requires equal lengths and
	// token length is not secret (it is a fixed public constant).
	if len(token) != len(d.AuthToken) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(token), []byte(d.AuthToken)) == 1
}

// VerifyPairStatusSignature verifies a signed pair/status request from a
// paired device. ts is the unix-seconds timestamp the device signed and
// sigHex the hex-encoded Ed25519 signature over
// "phonon-pair-status|deviceID|ts". The timestamp must be within
// SignatureWindow of the coordinator's clock.
func (m *Manager) VerifyPairStatusSignature(deviceID string, ts int64, sigHex string) bool {
	m.mu.RLock()
	d, ok := m.paired[deviceID]
	m.mu.RUnlock()
	if !ok || len(d.DeviceKey) != ed25519.PublicKeySize {
		return false
	}

	skew := time.Since(time.Unix(ts, 0))
	if skew < -SignatureWindow || skew > SignatureWindow {
		return false
	}

	sig, err := hex.DecodeString(sigHex)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return false
	}

	msg := []byte(PairStatusSigPrefix + "|" + deviceID + "|" + strconv.FormatInt(ts, 10))
	return ed25519.Verify(ed25519.PublicKey(d.DeviceKey), msg, sig)
}
