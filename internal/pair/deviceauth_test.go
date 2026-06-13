package pair

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"strconv"
	"testing"
	"time"
)

// newTestManager creates a Manager with a file store in a temp dir and
// completes pairing for one device, returning the manager, device ID,
// and the device's private key.
func newTestPairedManager(t *testing.T) (*Manager, string, ed25519.PrivateKey) {
	t.Helper()
	dir := t.TempDir()
	store := NewFileStore(dir + "/paired.json")
	m, err := NewManager(dir+"/coord.key", store)
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}
	t.Cleanup(m.StopCleanup)

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}

	deviceID := "test-device-1"
	code, err := m.StartPairing(deviceID, "Pixel 6", "10.0.0.5", pub)
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}
	if _, err := m.ConfirmPairing(deviceID, code, "node-1"); err != nil {
		t.Fatalf("ConfirmPairing: %v", err)
	}
	return m, deviceID, priv
}

func TestConfirmPairingGeneratesAuthToken(t *testing.T) {
	m, deviceID, _ := newTestPairedManager(t)

	token := m.AuthTokenFor(deviceID)
	if len(token) != AuthTokenBytes*2 {
		t.Fatalf("expected %d-char hex token, got %d chars", AuthTokenBytes*2, len(token))
	}
	if _, err := hex.DecodeString(token); err != nil {
		t.Fatalf("token is not valid hex: %v", err)
	}
}

func TestAuthorize(t *testing.T) {
	m, deviceID, _ := newTestPairedManager(t)
	token := m.AuthTokenFor(deviceID)

	if !m.Authorize(deviceID, token) {
		t.Error("expected correct token to authorize")
	}
	if m.Authorize(deviceID, "") {
		t.Error("expected empty token to fail")
	}
	if m.Authorize(deviceID, token[:len(token)-2]+"zz") {
		t.Error("expected wrong token to fail")
	}
	if m.Authorize("unknown-device", token) {
		t.Error("expected unpaired device to fail")
	}
}

func TestVerifyPairStatusSignature(t *testing.T) {
	m, deviceID, priv := newTestPairedManager(t)

	sign := func(id string, ts int64) string {
		msg := []byte(PairStatusSigPrefix + "|" + id + "|" + strconv.FormatInt(ts, 10))
		return hex.EncodeToString(ed25519.Sign(priv, msg))
	}

	now := time.Now().Unix()
	if !m.VerifyPairStatusSignature(deviceID, now, sign(deviceID, now)) {
		t.Error("expected valid signature to verify")
	}

	// Stale timestamp
	old := time.Now().Add(-SignatureWindow - time.Minute).Unix()
	if m.VerifyPairStatusSignature(deviceID, old, sign(deviceID, old)) {
		t.Error("expected stale timestamp to fail")
	}

	// Signature over wrong device ID
	if m.VerifyPairStatusSignature(deviceID, now, sign("other-device", now)) {
		t.Error("expected signature over wrong device ID to fail")
	}

	// Garbage signature
	if m.VerifyPairStatusSignature(deviceID, now, "deadbeef") {
		t.Error("expected malformed signature to fail")
	}

	// Unpaired device
	if m.VerifyPairStatusSignature("unknown", now, sign("unknown", now)) {
		t.Error("expected unpaired device to fail")
	}
}

func TestLoadPersistedBackfillsToken(t *testing.T) {
	dir := t.TempDir()
	storePath := dir + "/paired.json"

	// Persist a legacy device with no token.
	store := NewFileStore(storePath)
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	legacy := &PairedDevice{
		DeviceID:  "legacy-1",
		Name:      "legacy",
		DeviceKey: pub,
		PairedAt:  time.Now(),
	}
	if err := store.SavePaired(legacy); err != nil {
		t.Fatalf("SavePaired: %v", err)
	}

	m, err := NewManager(dir+"/coord.key", store)
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}
	defer m.StopCleanup()

	if m.AuthTokenFor("legacy-1") == "" {
		t.Fatal("expected legacy device to receive a backfilled auth token")
	}

	// And it should have been persisted.
	devices, err := store.LoadPaired()
	if err != nil {
		t.Fatalf("LoadPaired: %v", err)
	}
	for _, d := range devices {
		if d.DeviceID == "legacy-1" && d.AuthToken == "" {
			t.Error("backfilled token was not persisted")
		}
	}
}
