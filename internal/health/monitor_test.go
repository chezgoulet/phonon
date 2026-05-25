package health

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

func setupTest(t *testing.T) (*Monitor, *registry.Registry) {
	t.Helper()
	reg := registry.New()
	cfg := DefaultMonitorConfig()
	cfg.CheckInterval = time.Hour // Don't auto-run checks
	m := NewMonitor(reg, cfg)
	return m, reg
}

func registerNode(t *testing.T, reg *registry.Registry, id, name string) {
	t.Helper()
	if err := reg.Register(id, name, ""); err != nil {
		t.Fatalf("register %s: %v", id, err)
	}
}

func pairNode(t *testing.T, reg *registry.Registry, id string) {
	t.Helper()
	if err := reg.Pair(id); err != nil {
		t.Fatalf("pair %s: %v", id, err)
	}
}

func sendHeartbeat(t *testing.T, reg *registry.Registry, id string, battery float64, charging bool, temp float64) {
	t.Helper()
	if err := reg.UpdateHeartbeat(id, registry.HealthTelemetry{
		BatteryLevel: battery,
		ThermalTempC: temp,
		IsCharging:   charging,
	}); err != nil {
		t.Fatalf("heartbeat %s: %v", id, err)
	}
}

func assertExcludeReason(t *testing.T, reg *registry.Registry, id, expected string) {
	t.Helper()
	node, ok := reg.Get(id)
	if !ok {
		t.Fatalf("node %s not found", id)
	}
	if node.ExcludeReason != expected {
		t.Errorf("node %s: expected ExcludeReason=%q, got %q", id, expected, node.ExcludeReason)
	}
}

// --- Overheat tests ---

func TestOverheat_ExcludesNode(t *testing.T) {
	m, reg := setupTest(t)
	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 80, true, 50) // 50°C > 45°C threshold

	m.Check()

	assertExcludeReason(t, reg, "device-1", "overheating")
}

func TestOverheat_HealthyNodeStaysIncluded(t *testing.T) {
	m, reg := setupTest(t)
	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 80, true, 35) // 35°C below threshold

	m.Check()

	assertExcludeReason(t, reg, "device-1", "")
}

func TestOverheat_HysteresisStaysExcluded(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.OverheatThreshold = 45
	m.cfg.OverheatReentryThreshold = 40

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// First check: 50°C → excluded
	sendHeartbeat(t, reg, "device-1", 80, true, 50)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "overheating")

	// Second check: 42°C (below 45, but still above 40 re-entry) → stays excluded
	sendHeartbeat(t, reg, "device-1", 80, true, 42)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "overheating")
}

func TestOverheat_ReentersAfterCooling(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.OverheatThreshold = 45
	m.cfg.OverheatReentryThreshold = 40

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// Overheat
	sendHeartbeat(t, reg, "device-1", 80, true, 50)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "overheating")

	// Cool down below re-entry
	sendHeartbeat(t, reg, "device-1", 80, true, 38)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "")
}

// --- Battery tests ---

func TestLowBattery_ExcludesNode(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.BatteryLowThreshold = 15

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 10, false, 35) // 10% < 15%, not charging

	m.Check()

	assertExcludeReason(t, reg, "device-1", "low-battery")
}

func TestLowBattery_ChargingNodeStaysIncluded(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.BatteryLowThreshold = 15

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 10, true, 35) // 10% but charging

	m.Check()

	assertExcludeReason(t, reg, "device-1", "") // Charging overrides low battery
}

func TestLowBattery_HysteresisReentry(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.BatteryLowThreshold = 15
	m.cfg.BatteryReentryThreshold = 30

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// 10% not charging → excluded
	sendHeartbeat(t, reg, "device-1", 10, false, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "low-battery")

	// 20% not charging (below 30% re-entry, above 15% low) → stays excluded
	sendHeartbeat(t, reg, "device-1", 20, false, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "low-battery")

	// 35% not charging (above 30% re-entry) → re-entered
	sendHeartbeat(t, reg, "device-1", 35, false, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "")
}

func TestLowBattery_ChargingCausesReentry(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.BatteryLowThreshold = 15
	m.cfg.BatteryReentryThreshold = 30

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// Excluded: 10% not charging
	sendHeartbeat(t, reg, "device-1", 10, false, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "low-battery")

	// Now charging at same battery level → re-entered
	sendHeartbeat(t, reg, "device-1", 10, true, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "")
}

// --- Offline / stale detection ---

func TestPurgeStale_MarksOffline(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.OfflineTimeout = 50 * time.Millisecond

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 80, true, 35)
	m.Check()

	// Node should be online
	node, _ := reg.Get("device-1")
	if node.State != registry.NodeStateOnline {
		t.Fatalf("expected online, got %s", node.State)
	}

	// Wait for stale timeout
	time.Sleep(60 * time.Millisecond)

	m.Check()

	node, _ = reg.Get("device-1")
	if node.State != registry.NodeStateOffline {
		t.Errorf("expected offline after timeout, got %s", node.State)
	}
}

func TestPurgeStale_RecentHeartbeatStaysOnline(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.OfflineTimeout = 100 * time.Millisecond

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// Send heartbeat now
	sendHeartbeat(t, reg, "device-1", 80, true, 35)

	// Check immediately — should still be online
	m.Check()
	node, _ := reg.Get("device-1")
	if node.State != registry.NodeStateOnline {
		t.Errorf("expected online, got %s", node.State)
	}
}

// --- Degraded capacity ---

func TestDegradedCapacity_MarksDegraded(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.BatteryCapacityThreshold = 80

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 25, false, 35) // 25%, not charging

	m.Check()

	assertExcludeReason(t, reg, "device-1", "degraded")
}

func TestDegradedCapacity_ClearedOnCharge(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.BatteryCapacityThreshold = 80

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// Degraded
	sendHeartbeat(t, reg, "device-1", 25, false, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "degraded")

	// Now charging
	sendHeartbeat(t, reg, "device-1", 25, true, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "")
}

// --- Action hooks ---

func TestActionHook_StandbyPromote(t *testing.T) {
	m, reg := setupTest(t)

	var called int32
	m.AddAction(func(_ context.Context, deviceID, groupName string, actionType ActionType) {
		atomic.AddInt32(&called, 1)
	})

	// Trigger an overheat event
	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 80, true, 50)
	m.Check()

	if atomic.LoadInt32(&called) == 0 {
		t.Error("action hook should have been called")
	}
}

func TestActionHook_MultipleActions(t *testing.T) {
	m, reg := setupTest(t)

	var count1, count2 int32
	m.AddAction(func(_ context.Context, deviceID, groupName string, actionType ActionType) {
		atomic.AddInt32(&count1, 1)
	})
	m.AddAction(func(_ context.Context, deviceID, groupName string, actionType ActionType) {
		atomic.AddInt32(&count2, 1)
	})

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")
	sendHeartbeat(t, reg, "device-1", 80, true, 50)
	m.Check()

	if atomic.LoadInt32(&count1) == 0 || atomic.LoadInt32(&count2) == 0 {
		t.Error("both action hooks should have been called")
	}
}

// --- Start/Stop lifecycle ---

func TestMonitor_Lifecycle(t *testing.T) {
	m, _ := setupTest(t)

	m.Start()
	if !m.running {
		t.Error("monitor should be running after Start")
	}

	// Start again should be a no-op
	m.Start()

	m.Stop()
	if m.running {
		t.Error("monitor should not be running after Stop")
	}

	// Stop again should be a no-op
	m.Stop()
}

// --- Metrics ---

func TestMonitor_RegisterMetrics(t *testing.T) {
	m, _ := setupTest(t)
	metrics := m.RegisterMetrics()
	if metrics == nil {
		t.Fatal("RegisterMetrics should return a non-nil Metrics")
	}
}

func TestMonitor_Metrics(t *testing.T) {
	m, _ := setupTest(t)
	if m.Metrics() == nil {
		t.Fatal("Metrics() should return non-nil")
	}
}

// --- Edge cases ---

func TestCheck_EmptyRegistry(t *testing.T) {
	m, _ := setupTest(t)
	// Should not panic
	m.Check()
}

func TestCheck_UnpairedNode(t *testing.T) {
	m, reg := setupTest(t)
	registerNode(t, reg, "device-1", "test-phone")
	// Unpaired — shouldn't crash or set exclude reasons
	m.Check()

	node, _ := reg.Get("device-1")
	if node.State != registry.NodeStateUnpaired {
		t.Errorf("should stay unpaired, got %s", node.State)
	}
}

func TestOverheat_UnhealthyClearedOnHealthyBeat(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.OverheatThreshold = 45

	registerNode(t, reg, "device-1", "test-phone")
	pairNode(t, reg, "device-1")

	// Overheat
	sendHeartbeat(t, reg, "device-1", 80, true, 50)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "overheating")

	// Back to healthy
	sendHeartbeat(t, reg, "device-1", 80, true, 35)
	m.Check()
	assertExcludeReason(t, reg, "device-1", "")
}

func TestMultipleNodes_IndependentStates(t *testing.T) {
	m, reg := setupTest(t)
	m.cfg.OverheatThreshold = 45
	m.cfg.BatteryLowThreshold = 15
	m.cfg.BatteryReentryThreshold = 30

	registerNode(t, reg, "device-1", "a")
	registerNode(t, reg, "device-2", "b")
	pairNode(t, reg, "device-1")
	pairNode(t, reg, "device-2")

	// device-1: overheating. device-2: fine
	sendHeartbeat(t, reg, "device-1", 80, true, 50)
	sendHeartbeat(t, reg, "device-2", 80, true, 35)
	m.Check()

	assertExcludeReason(t, reg, "device-1", "overheating")
	assertExcludeReason(t, reg, "device-2", "")
}

func TestMetrics_NoDoubleCounting(t *testing.T) {
	m, reg := setupTest(t)
	metrics := m.RegisterMetrics()

	registerNode(t, reg, "d1", "phone-1")
	registerNode(t, reg, "d2", "phone-2")
	pairNode(t, reg, "d1")
	pairNode(t, reg, "d2")
	reg.AssignToGroup("d1", "fast-general")
	reg.AssignToGroup("d2", "fast-general")
	sendHeartbeat(t, reg, "d1", 80, true, 35)
	sendHeartbeat(t, reg, "d2", 80, true, 35)

	// First check cycle
	m.Check()

	// Scrape metrics after first cycle
	mux := http.NewServeMux()
	mux.Handle("GET /metrics", metrics.Handler())
	req := httptest.NewRequest("GET", "/metrics", nil)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	body1 := w.Body.String()

	// Second check cycle — without Reset(), this would double
	m.Check()

	w2 := httptest.NewRecorder()
	mux.ServeHTTP(w2, req)
	body2 := w2.Body.String()

	if body1 != body2 {
		t.Errorf("metrics changed between cycles — double-counting bug detected")
	}

	// Verify the actual count is correct (2 nodes, not doubled)
	if !strContains(body1, `phonon_nodes_online{group="fast-general"} 2`) {
		t.Errorf("expected 2 online in fast-general, got:\n%s", body1)
	}
}

func TestMonitor_DefaultConfig(t *testing.T) {
	cfg := DefaultMonitorConfig()
	if cfg.OverheatThreshold != 45 {
		t.Errorf("expected default overheat 45, got %f", cfg.OverheatThreshold)
	}
	if cfg.BatteryLowThreshold != 15 {
		t.Errorf("expected default battery low 15, got %f", cfg.BatteryLowThreshold)
	}
	if cfg.BatteryReentryThreshold != 30 {
		t.Errorf("expected default battery reentry 30, got %f", cfg.BatteryReentryThreshold)
	}
	if cfg.OfflineTimeout != 60*time.Second {
		t.Errorf("expected default offline timeout 60s, got %v", cfg.OfflineTimeout)
	}
}
