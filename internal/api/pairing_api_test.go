package api

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/chezgoulet/phonon/internal/pair"
	"github.com/chezgoulet/phonon/internal/registry"
)

// memStore is an in-memory Store implementation for tests.
type memStore struct {
	mu      sync.Mutex
	devices map[string]*pair.PairedDevice
}

func (s *memStore) SavePaired(d *pair.PairedDevice) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.devices[d.DeviceID] = d
	return nil
}

func (s *memStore) LoadPaired() ([]*pair.PairedDevice, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]*pair.PairedDevice, 0, len(s.devices))
	for _, d := range s.devices {
		result = append(result, d)
	}
	return result, nil
}

func (s *memStore) RemovePaired(deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.devices, deviceID)
	return nil
}

func (s *memStore) Close() error { return nil }

// setupPairingTest creates a reg + pairing handler + mux with operator routes.
func setupPairingTest(t *testing.T) (*registry.Registry, *pair.Manager, *http.ServeMux) {
	t.Helper()
	reg := registry.New()
	pm, err := pair.NewManager("", &memStore{devices: make(map[string]*pair.PairedDevice)})
	if err != nil {
		t.Fatalf("pair.NewManager: %v", err)
	}
	t.Cleanup(pm.StopCleanup)

	h := NewPairingHandler(pm, reg)
	reg.Register("TEST-PHONE", "pixel-9", "")

	mux := http.NewServeMux()
	h.RegisterOperatorRoutes(mux)
	h.RegisterSidecarRoutes(mux)

	return reg, pm, mux
}

// ed25519PubKey returns a 32-byte hex-encoded Ed25519 public key for testing.
func ed25519PubKey() string {
	// 32 bytes of deterministic test data
	var b [32]byte
	for i := range b {
		b[i] = byte(i)
	}
	return hex.EncodeToString(b[:])
}

func TestPairingRequest_Success(t *testing.T) {
	_, _, mux := setupPairingTest(t)

	body := mustMarshal(t, map[string]string{
		"device_id":    "TEST-PHONE",
		"device_model": "pixel-9",
		"device_pubkey": ed25519PubKey(),
	})

	w := execPost(t, mux, "/api/v1/sidecar/pair/request", body)
	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", w.Code, w.Body.String())
	}

	var resp map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if resp["status"] != "pending" {
		t.Errorf("expected status pending, got %q", resp["status"])
	}
	code, ok := resp["code"].(string)
	if !ok || len(code) != 6 {
		t.Errorf("expected 6-digit code, got %q", code)
	}
}

func TestPairingRequest_MissingDeviceID(t *testing.T) {
	_, _, mux := setupPairingTest(t)

	body := mustMarshal(t, map[string]string{
		"device_pubkey": ed25519PubKey(),
	})
	w := execPost(t, mux, "/api/v1/sidecar/pair/request", body)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

func TestPairingRequest_MissingPubKey(t *testing.T) {
	_, _, mux := setupPairingTest(t)

	body := mustMarshal(t, map[string]string{
		"device_id": "TEST-PHONE",
	})
	w := execPost(t, mux, "/api/v1/sidecar/pair/request", body)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", w.Code)
	}
}

func TestPairingRequest_AlreadyPaired(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	// Complete the pairing first
	pubKeyHex := ed25519PubKey()
	pubKey, _ := hex.DecodeString(pubKeyHex)
	code, _ := pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)
	pm.ConfirmPairing("TEST-PHONE", code, "test-phone")

	// Attempt to pair again
	body := mustMarshal(t, map[string]string{
		"device_id":    "TEST-PHONE",
		"device_model": "pixel-9",
		"device_pubkey": pubKeyHex,
	})
	w := execPost(t, mux, "/api/v1/sidecar/pair/request", body)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 (already_paired), got %d: %s", w.Code, w.Body.String())
	}
	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["status"] != "already_paired" {
		t.Errorf("expected already_paired, got %q", resp["status"])
	}
}

func TestPairingConfirm_Success(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	pubKey, _ := hex.DecodeString(ed25519PubKey())
	code, _ := pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)

	body := mustMarshal(t, map[string]string{
		"device_id": "TEST-PHONE",
		"code":      code,
		"name":      "kitchen-phone",
	})

	w := execPost(t, mux, "/api/v1/pair/confirm", body)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}

	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["status"] != "paired" {
		t.Errorf("expected paired, got %q", resp["status"])
	}
	if resp["node_name"] != "kitchen-phone" {
		t.Errorf("expected kitchen-phone, got %q", resp["node_name"])
	}

	if !pm.IsPaired("TEST-PHONE") {
		t.Error("expected TEST-PHONE to be paired")
	}
}

func TestPairingConfirm_WrongCode(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	pubKey, _ := hex.DecodeString(ed25519PubKey())
	pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)

	body := mustMarshal(t, map[string]string{
		"device_id": "TEST-PHONE",
		"code":      "999999",
	})

	w := execPost(t, mux, "/api/v1/pair/confirm", body)
	if w.Code != http.StatusConflict {
		t.Fatalf("expected 409, got %d: %s", w.Code, w.Body.String())
	}
}

func TestPairingConfirm_NoPairing(t *testing.T) {
	_, _, mux := setupPairingTest(t)

	body := mustMarshal(t, map[string]string{
		"device_id": "TEST-PHONE",
		"code":      "123456",
	})

	w := execPost(t, mux, "/api/v1/pair/confirm", body)
	if w.Code != http.StatusConflict {
		t.Fatalf("expected 409, got %d: %s", w.Code, w.Body.String())
	}
}

func TestListPending_Empty(t *testing.T) {
	_, _, mux := setupPairingTest(t)

	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/api/v1/pair/pending", nil)
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	pending, ok := resp["pending"].([]any)
	if !ok {
		t.Fatal("expected pending array")
	}
	if len(pending) != 0 {
		t.Errorf("expected 0 pending, got %d", len(pending))
	}
}

func TestListPending_WithPending(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	pubKey, _ := hex.DecodeString(ed25519PubKey())
	pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)

	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/api/v1/pair/pending", nil)
	mux.ServeHTTP(w, req)

	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	pending := resp["pending"].([]any)
	if len(pending) != 1 {
		t.Fatalf("expected 1 pending, got %d", len(pending))
	}
}

func TestListPaired_WithDevices(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	pubKey, _ := hex.DecodeString(ed25519PubKey())
	code, _ := pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)
	pm.ConfirmPairing("TEST-PHONE", code, "kitchen-phone")

	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/api/v1/pair/paired", nil)
	mux.ServeHTTP(w, req)

	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	paired := resp["paired"].([]any)
	if len(paired) != 1 {
		t.Fatalf("expected 1 paired, got %d", len(paired))
	}
}

func TestPairing_StatusPolls(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	pubKey, _ := hex.DecodeString(ed25519PubKey())

	// Start pairing
	code, _ := pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)

	// Poll — should show pending
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/api/v1/sidecar/pair/status?device_id=TEST-PHONE", nil)
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["status"] != "pending" {
		t.Errorf("expected pending, got %q", resp["status"])
	}

	// Confirm
	pm.ConfirmPairing("TEST-PHONE", code, "kitchen-phone")

	// Poll — should show paired
	w2 := httptest.NewRecorder()
	req2 := httptest.NewRequest("GET", "/api/v1/sidecar/pair/status?device_id=TEST-PHONE", nil)
	mux.ServeHTTP(w2, req2)

	json.Unmarshal(w2.Body.Bytes(), &resp)
	if resp["status"] != "paired" {
		t.Errorf("expected paired, got %q", resp["status"])
	}
}

func TestUnpair(t *testing.T) {
	_, pm, mux := setupPairingTest(t)

	pubKey, _ := hex.DecodeString(ed25519PubKey())
	code, _ := pm.StartPairing("TEST-PHONE", "pixel-9", "10.0.0.5", pubKey)
	pm.ConfirmPairing("TEST-PHONE", code, "kitchen-phone")

	if !pm.IsPaired("TEST-PHONE") {
		t.Fatal("should be paired")
	}

	w := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/v1/pair/unpair?device_id=TEST-PHONE", strings.NewReader("{}"))
	req.Header.Set("Content-Type", "application/json")
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	if pm.IsPaired("TEST-PHONE") {
		t.Error("should be unpaired")
	}
}
