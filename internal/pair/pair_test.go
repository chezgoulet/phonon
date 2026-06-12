package pair

import (
	"crypto/ed25519"
	"crypto/rand"
	"testing"
	"time"
)

// ed25519Key generates a test keypair and returns the hex public key + raw private key.
func testKey(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}
	return pub, priv
}

func TestNewManager_generatesKey(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}
	if m.CoordinatorPublicKey() == "" {
		t.Fatal("coordinator public key is empty")
	}
	if len(m.CoordinatorPublicKey()) != 64 { // hex of 32 bytes
		t.Fatalf("unexpected hex key length: %d", len(m.CoordinatorPublicKey()))
	}
}

func TestStartPairing_rejectsShortKey(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	_, err = m.StartPairing("device-01", "pixel-9", "10.0.0.5", []byte{1, 2, 3})
	if err == nil {
		t.Fatal("expected error for short public key, got nil")
	}
}

func TestStartPairing_createsPending(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub, _ := testKey(t)
	code, err := m.StartPairing("device-01", "pixel-9", "10.0.0.5", pub)
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}
	if len(code) != 6 {
		t.Fatalf("expected 6-digit code, got %q", code)
	}

	pendings := m.ListPending()
	if len(pendings) != 1 {
		t.Fatalf("expected 1 pending, got %d", len(pendings))
	}
	if pendings[0].Code != code {
		t.Fatalf("code mismatch: %s vs %s", pendings[0].Code, code)
	}
}

func TestConfirmPairing_requiresMatch(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub, _ := testKey(t)
	code, err := m.StartPairing("device-01", "pixel-9", "10.0.0.5", pub)
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}

	// Wrong code
	_, err = m.ConfirmPairing("device-01", "000000", "test-device")
	if err == nil {
		t.Fatal("expected error for wrong code, got nil")
	}

	// Correct code
	paired, err := m.ConfirmPairing("device-01", code, "test-device")
	if err != nil {
		t.Fatalf("ConfirmPairing: %v", err)
	}
	if paired.Name != "test-device" {
		t.Fatalf("expected name test-device, got %q", paired.Name)
	}
	if paired.DeviceID != "device-01" {
		t.Fatalf("expected device-01, got %q", paired.DeviceID)
	}
}

func TestConfirmPairing_rejectsExpired(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub, _ := testKey(t)
	code, err := m.StartPairing("device-01", "pixel-9", "10.0.0.5", pub)
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}

	// Manually expire the pairing
	m.mu.Lock()
	if p, ok := m.pending["device-01"]; ok {
		p.ExpiresAt = time.Now().Add(-1 * time.Second)
	}
	m.mu.Unlock()

	_, err = m.ConfirmPairing("device-01", code, "test-device")
	if err == nil {
		t.Fatal("expected error for expired code, got nil")
	}
}

func TestIsPaired_afterConfirm(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub, _ := testKey(t)
	code, err := m.StartPairing("device-01", "pixel-9", "10.0.0.5", pub)
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}

	if m.IsPaired("device-01") {
		t.Fatal("should not be paired before confirmation")
	}

	_, err = m.ConfirmPairing("device-01", code, "test-device")
	if err != nil {
		t.Fatalf("ConfirmPairing: %v", err)
	}

	if !m.IsPaired("device-01") {
		t.Fatal("should be paired after confirmation")
	}
}

func TestListPaired(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub1, _ := testKey(t)
	pub2, _ := testKey(t)

	code1, _ := m.StartPairing("dev-a", "pixel-7", "10.0.0.1", pub1)
	code2, _ := m.StartPairing("dev-b", "pixel-8", "10.0.0.2", pub2)

	_, _ = m.ConfirmPairing("dev-a", code1, "alpha")
	_, _ = m.ConfirmPairing("dev-b", code2, "bravo")

	paired := m.ListPaired()
	if len(paired) != 2 {
		t.Fatalf("expected 2 paired devices, got %d", len(paired))
	}
}

func TestRemovePaired(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub, _ := testKey(t)
	code, _ := m.StartPairing("device-01", "pixel-9", "10.0.0.5", pub)
	_, _ = m.ConfirmPairing("device-01", code, "test-device")

	if !m.IsPaired("device-01") {
		t.Fatal("should be paired")
	}

	m.RemovePaired("device-01")
	if m.IsPaired("device-01") {
		t.Fatal("should not be paired after removal")
	}
}

func TestCleanup_expiresPending(t *testing.T) {
	m, err := NewManager("")
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	pub, _ := testKey(t)
	_, err = m.StartPairing("device-01", "pixel-9", "10.0.0.5", pub)
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}

	// Manually set the expiry in the past
	m.mu.Lock()
	if p, ok := m.pending["device-01"]; ok {
		p.ExpiresAt = time.Now().Add(-1 * time.Second)
	}
	m.mu.Unlock()

	// Run cleanup
	m.mu.Lock()
	m.cleanup()
	m.mu.Unlock()

	if len(m.ListPending()) != 0 {
		t.Fatal("expected all pending to be cleaned up")
	}
}

func TestGenerateCode_uniqueness(t *testing.T) {
	codes := make(map[string]bool)
	for i := 0; i < 100; i++ {
		c, err := generateCode()
		if err != nil {
			t.Fatalf("generateCode: %v", err)
		}
		if codes[c] {
			t.Fatalf("duplicate code generated: %s", c)
		}
		codes[c] = true
	}
}
