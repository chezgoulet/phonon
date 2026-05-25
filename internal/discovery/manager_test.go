package discovery

import (
	"context"
	"sync"
	"net"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

const testPhone01 = "phone-01"

func TestNewManager_NoMDNS(t *testing.T) {
	m := NewManager(nil, nil)
	if m == nil {
		t.Fatal("expected non-nil manager")
	}

	// Start with nil discoverer should be a no-op
	if err := m.Start(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if err := m.Stop(); err != nil {
		t.Fatalf("unexpected stop error: %v", err)
	}
}

func TestManager_StartStop(t *testing.T) {
	mock := newMockDiscoverer()
	manager := NewManager(mock, nil)

	if err := manager.Start(context.Background()); err != nil {
		t.Fatalf("unexpected start error: %v", err)
	}

	// Wait for mock to start
	select {
	case <-mock.started:
	case <-time.After(time.Second):
		t.Fatal("mock discoverer did not start")
	}

	if err := manager.Stop(); err != nil {
		t.Fatalf("unexpected stop error: %v", err)
	}
}

func TestManager_StartTwice(t *testing.T) {
	mock := newMockDiscoverer()
	manager := NewManager(mock, nil)

	if err := manager.Start(context.Background()); err != nil {
		t.Fatalf("first start: %v", err)
	}

	if err := manager.Start(context.Background()); err == nil {
		t.Error("expected error starting twice")
	}

	manager.Stop()
}

func TestManager_StopTwice(t *testing.T) {
	mock := newMockDiscoverer()
	manager := NewManager(mock, nil)

	manager.Start(context.Background())
	<-mock.started

	if err := manager.Stop(); err != nil {
		t.Fatalf("first stop: %v", err)
	}

	if err := manager.Stop(); err != nil {
		t.Fatalf("second stop should be no-op: %v", err)
	}
}

func TestManager_DiscoveryCallback(t *testing.T) {
	var (
		mu               sync.Mutex
		callbackDeviceID string
		callbackModel    string
		callbackIP       net.IP
		callbackPort     int
	)

	callback := func(deviceID, model string, ip net.IP, port int) error {
		mu.Lock()
		defer mu.Unlock()
		callbackDeviceID = deviceID
		callbackModel = model
		callbackIP = ip
		callbackPort = port
		return nil
	}

	mock := newMockDiscoverer(DiscoveredDevice{
		DeviceID:    testPhone01,
		DeviceModel: "Pixel 9 Pro",
		IP:          net.ParseIP("192.168.1.10"),
		Port:        9876,
	})

	manager := NewManager(mock, callback)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if err := manager.Start(ctx); err != nil {
		t.Fatalf("start: %v", err)
	}

	// Give it time to process
	time.Sleep(100 * time.Millisecond)
	manager.Stop()

	mu.Lock()
	defer mu.Unlock()

	if callbackDeviceID != testPhone01 {
		t.Errorf("expected device_id phone-01, got %q", callbackDeviceID)
	}
	if callbackModel != "Pixel 9 Pro" {
		t.Errorf("expected model Pixel 9 Pro, got %q", callbackModel)
	}
	if callbackIP.String() != "192.168.1.10" {
		t.Errorf("expected IP 192.168.1.10, got %s", callbackIP)
	}
	if callbackPort != 9876 {
		t.Errorf("expected port 9876, got %d", callbackPort)
	}
}

func TestManager_ManualRegistration(t *testing.T) {
	reg := registry.New()
	manager := NewManager(nil, DefaultRegistrationCallback(reg))

	deviceID, err := manager.RegisterManual("192.168.1.50", "Pixel-6")
	if err != nil {
		t.Fatalf("RegisterManual: %v", err)
	}
	if deviceID != "Pixel-6" {
		t.Errorf("expected device ID Pixel-6, got %q", deviceID)
	}

	// Check it was registered in the registry
	node, ok := reg.Get("Pixel-6")
	if !ok {
		t.Fatal("node not found in registry after manual registration")
	}
	if node.State != registry.NodeStateUnpaired {
		t.Errorf("expected unpaired state, got %s", node.State)
	}
	if node.DeviceModel != "manual" {
		t.Errorf("expected model 'manual', got %q", node.DeviceModel)
	}
}

func TestManager_ManualRegistrationNoName(t *testing.T) {
	reg := registry.New()
	manager := NewManager(nil, DefaultRegistrationCallback(reg))

	deviceID, err := manager.RegisterManual("192.168.1.50", "")
	if err != nil {
		t.Fatalf("RegisterManual: %v", err)
	}
	if deviceID != "manual-192.168.1.50" {
		t.Errorf("expected manual-192.168.1.50, got %q", deviceID)
	}
}

func TestManager_ManualRegistrationInvalidIP(t *testing.T) {
	manager := NewManager(nil, nil)
	_, err := manager.RegisterManual("not-an-ip", "")
	if err == nil {
		t.Error("expected error for invalid IP")
	}
}

func TestManager_DuplicateDiscovery(t *testing.T) {
	var callCount int
	callback := func(_ string, _ string, _ net.IP, _ int) error {
		callCount++
		return nil
	}

	device := DiscoveredDevice{
		DeviceID:    testPhone01,
		DeviceModel: "Pixel 9 Pro",
		IP:          net.ParseIP("192.168.1.10"),
		Port:        9876,
	}

	manager := NewManager(nil, callback)

	// First discovery should trigger callback
	manager.handleDiscovery(&device)
	if callCount != 1 {
		t.Errorf("expected 1 callback, got %d", callCount)
	}

	// Same device, same IP/port, within 60s — should be deduped
	manager.handleDiscovery(&device)
	if callCount != 1 {
		t.Errorf("expected 1 callback (dedup), got %d", callCount)
	}
}

func TestManager_DuplicateDiscoveryDifferentIP(t *testing.T) {
	var callCount int
	callback := func(_ string, _ string, _ net.IP, _ int) error {
		callCount++
		return nil
	}

	manager := NewManager(nil, callback)

	manager.handleDiscovery(&DiscoveredDevice{
		DeviceID:    testPhone01,
		DeviceModel: "Pixel 9 Pro",
		IP:          net.ParseIP("192.168.1.10"),
		Port:        9876,
	})

	// Same device, different IP — should trigger callback again
	manager.handleDiscovery(&DiscoveredDevice{
		DeviceID:    testPhone01,
		DeviceModel: "Pixel 9 Pro",
		IP:          net.ParseIP("192.168.1.20"),
		Port:        9876,
	})

	if callCount != 2 {
		t.Errorf("expected 2 callbacks (IP changed), got %d", callCount)
	}
}

func TestManager_List(t *testing.T) {
	manager := NewManager(nil, nil)

	manager.handleDiscovery(&DiscoveredDevice{
		DeviceID:    testPhone01,
		DeviceModel: "Pixel 9 Pro",
		IP:          net.ParseIP("192.168.1.10"),
		Port:        9876,
	})
	manager.handleDiscovery(&DiscoveredDevice{
		DeviceID:    "phone-02",
		DeviceModel: "Pixel 8",
		IP:          net.ParseIP("192.168.1.11"),
		Port:        9877,
	})

	devices := manager.List()
	if len(devices) != 2 {
		t.Fatalf("expected 2 devices, got %d", len(devices))
	}
}

func TestDefaultCallback_RegistersInRegistry(t *testing.T) {
	reg := registry.New()
	callback := DefaultRegistrationCallback(reg)

	if err := callback("device-01", "Pixel 9 Pro", net.ParseIP("10.0.0.1"), 9876); err != nil {
		t.Fatalf("callback: %v", err)
	}

	node, ok := reg.Get("device-01")
	if !ok {
		t.Fatal("device not found in registry")
	}
	if node.State != registry.NodeStateUnpaired {
		t.Errorf("expected unpaired, got %s", node.State)
	}
	if node.DeviceModel != "Pixel 9 Pro" {
		t.Errorf("expected model Pixel 9 Pro, got %q", node.DeviceModel)
	}
}

func TestDefaultCallback_UpdateExisting(t *testing.T) {
	reg := registry.New()
	callback := DefaultRegistrationCallback(reg)

	// First call
	callback("device-01", "Pixel 9 Pro", net.ParseIP("10.0.0.1"), 9876)

	// Second call — should update not error
	if err := callback("device-01", "Pixel 9 Pro Updated", net.ParseIP("10.0.0.2"), 9877); err != nil {
		t.Fatalf("update callback: %v", err)
	}
}

func TestParseEntry_Nil(t *testing.T) {
	if parseEntry(nil) != nil {
		t.Error("expected nil for nil entry")
	}
}

func TestParseTXTField(t *testing.T) {
	kv := parseTXTField("device_id=phone-01")
	if kv.key != "device_id" || kv.value != testPhone01 {
		t.Errorf("expected device_id=phone-01, got %s=%s", kv.key, kv.value)
	}

	kv = parseTXTField("noeq")
	if kv.key != "noeq" || kv.value != "" {
		t.Errorf("expected key only, got %s=%s", kv.key, kv.value)
	}

	kv = parseTXTField("")
	if kv.key != "" || kv.value != "" {
		t.Errorf("expected empty, got %s=%s", kv.key, kv.value)
	}

	kv = parseTXTField("key=value=extra")
	if kv.key != "key" || kv.value != "value=extra" {
		t.Errorf("expected key=value=extra, got %s=%s", kv.key, kv.value)
	}
}

func TestSanitizeHostname(t *testing.T) {
	if got := sanitizeHostname("phone-01.local."); got != testPhone01 {
		t.Errorf("expected phone-01, got %q", got)
	}
	if got := sanitizeHostname(testPhone01); got != testPhone01 {
		t.Errorf("expected phone-01, got %q", got)
	}
	if got := sanitizeHostname(""); got != "" {
		t.Errorf("expected empty, got %q", got)
	}
}
