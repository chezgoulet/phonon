package registry

import (
	"fmt"
	"testing"
	"time"
)

func TestNewRegistryIsEmpty(t *testing.T) {
	r := New()
	if count := r.Count(); count != 0 {
		t.Errorf("expected empty registry, got %d nodes", count)
	}
}

func TestRegisterAndGet(t *testing.T) {
	r := New()

	err := r.Register("serial-001", "pixel-7a-001", "192.168.1.10")
	if err != nil {
		t.Fatalf("Register() error: %v", err)
	}

	node, ok := r.Get("serial-001")
	if !ok {
		t.Fatal("Get() returned false for registered node")
	}
	if node.State != NodeStateUnpaired {
		t.Errorf("expected unpaired state, got %s", node.State)
	}
	if node.Name != "pixel-7a-001" {
		t.Errorf("expected name 'pixel-7a-001', got %q", node.Name)
	}
	if node.IPAddress != "192.168.1.10" {
		t.Errorf("expected IP '192.168.1.10', got %q", node.IPAddress)
	}
}

func TestRegisterDuplicate(t *testing.T) {
	r := New()

	_ = r.Register("serial-001", "pixel-7a-001", "")
	err := r.Register("serial-001", "pixel-7a-002", "")
	if !IsAlreadyRegistered(err) {
		t.Errorf("expected ErrAlreadyRegistered, got %v", err)
	}
}

func TestGetNonexistent(t *testing.T) {
	r := New()

	_, ok := r.Get("nonexistent")
	if ok {
		t.Fatal("expected false for nonexistent node")
	}
}

func TestPairNode(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "pixel-7a-001", "")

	err := r.Pair("serial-001")
	if err != nil {
		t.Fatalf("Pair() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.State != NodeStatePaired {
		t.Errorf("expected paired state, got %s", node.State)
	}
	if node.PairedAt.IsZero() {
		t.Error("expected PairedAt to be set")
	}
}

func TestPairNonexistent(t *testing.T) {
	r := New()
	err := r.Pair("nonexistent")
	if !IsNotFound(err) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestPairWrongState(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "pixel-7a-001", "")
	_ = r.Pair("serial-001")

	err := r.Pair("serial-001")
	if !IsWrongState(err) {
		t.Errorf("expected ErrWrongState for already-paired node, got %v", err)
	}
}

func TestUpdateHeartbeatTransitionsToOnline(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "pixel-7a-001", "")
	_ = r.Pair("serial-001")

	telemetry := HealthTelemetry{
		BatteryLevel: 85.0,
		ThermalTempC: 32.0,
		IsCharging:   true,
	}
	err := r.UpdateHeartbeat("serial-001", telemetry)
	if err != nil {
		t.Fatalf("UpdateHeartbeat() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.State != NodeStateOnline {
		t.Errorf("expected online state after heartbeat, got %s", node.State)
	}
	if node.Telemetry.BatteryLevel != 85.0 {
		t.Errorf("expected battery 85.0, got %f", node.Telemetry.BatteryLevel)
	}
	if node.Telemetry.ThermalTempC != 32.0 {
		t.Errorf("expected thermal 32.0, got %f", node.Telemetry.ThermalTempC)
	}
	if !node.Telemetry.IsCharging {
		t.Error("expected is_charging true")
	}
}

func TestUpdateHeartbeatNonexistent(t *testing.T) {
	r := New()
	err := r.UpdateHeartbeat("nonexistent", HealthTelemetry{})
	if !IsNotFound(err) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestUpdateHeartbeatTransitionsFromOffline(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")
	_ = r.SetOffline("serial-001")

	err := r.UpdateHeartbeat("serial-001", HealthTelemetry{BatteryLevel: 50})
	if err != nil {
		t.Fatalf("UpdateHeartbeat() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.State != NodeStateOnline {
		t.Errorf("expected online after heartbeat from offline, got %s", node.State)
	}
}

func TestAssignToGroup(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")

	err := r.AssignToGroup("serial-001", "fast-general")
	if err != nil {
		t.Fatalf("AssignToGroup() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.Group != "fast-general" {
		t.Errorf("expected group 'fast-general', got %q", node.Group)
	}
}

func TestAssignToGroupUnpaired(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")

	err := r.AssignToGroup("serial-001", "fast-general")
	if !IsWrongState(err) {
		t.Errorf("expected ErrWrongState for unpaired node, got %v", err)
	}
}

func TestAssignToGroupNonexistent(t *testing.T) {
	r := New()
	err := r.AssignToGroup("nonexistent", "fast-general")
	if !IsNotFound(err) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestUnassign(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")
	_ = r.AssignToGroup("serial-001", "fast-general")

	err := r.Unassign("serial-001")
	if err != nil {
		t.Fatalf("Unassign() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.Group != "" {
		t.Errorf("expected empty group, got %q", node.Group)
	}
}

func TestSetOnline(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")
	_ = r.SetOffline("serial-001")

	err := r.SetOnline("serial-001")
	if err != nil {
		t.Fatalf("SetOnline() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.State != NodeStateOnline {
		t.Errorf("expected online, got %s", node.State)
	}
}

func TestSetOffline(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")

	err := r.SetOffline("serial-001")
	if err != nil {
		t.Fatalf("SetOffline() error: %v", err)
	}

	node, _ := r.Get("serial-001")
	if node.State != NodeStateOffline {
		t.Errorf("expected offline, got %s", node.State)
	}
}

func TestSetOnlineNonexistent(t *testing.T) {
	r := New()
	err := r.SetOnline("nonexistent")
	if !IsNotFound(err) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestGetByGroup(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")
	_ = r.Register("serial-002", "", "")
	_ = r.Pair("serial-002")
	_ = r.AssignToGroup("serial-001", "group-a")
	_ = r.AssignToGroup("serial-002", "group-b")

	nodes := r.GetByGroup("group-a")
	if len(nodes) != 1 {
		t.Fatalf("expected 1 node in group-a, got %d", len(nodes))
	}
	if nodes[0].DeviceID != "serial-001" {
		t.Errorf("expected serial-001, got %s", nodes[0].DeviceID)
	}

	nodes = r.GetByGroup("nonexistent")
	if len(nodes) != 0 {
		t.Errorf("expected 0 nodes in nonexistent group, got %d", len(nodes))
	}
}

func TestGetHealthyByGroup(t *testing.T) {
	r := New()

	// Healthy node
	_ = r.Register("healthy", "", "")
	_ = r.Pair("healthy")
	_ = r.UpdateHeartbeat("healthy", HealthTelemetry{BatteryLevel: 80, ThermalTempC: 35, IsCharging: true})
	_ = r.AssignToGroup("healthy", "group-a")

	// Overheating node
	_ = r.Register("hot", "", "")
	_ = r.Pair("hot")
	_ = r.UpdateHeartbeat("hot", HealthTelemetry{BatteryLevel: 80, ThermalTempC: 50, IsCharging: true})
	_ = r.AssignToGroup("hot", "group-a")

	// Low battery, not charging
	_ = r.Register("lowbat", "", "")
	_ = r.Pair("lowbat")
	_ = r.UpdateHeartbeat("lowbat", HealthTelemetry{BatteryLevel: 10, ThermalTempC: 35, IsCharging: false})
	_ = r.AssignToGroup("lowbat", "group-a")

	// Offline node
	_ = r.Register("offline", "", "")
	_ = r.Pair("offline")
	_ = r.UpdateHeartbeat("offline", HealthTelemetry{BatteryLevel: 80, ThermalTempC: 35, IsCharging: true})
	_ = r.AssignToGroup("offline", "group-a")
	_ = r.SetOffline("offline")

	// Low battery but charging — should be healthy
	_ = r.Register("lowbat-charging", "", "")
	_ = r.Pair("lowbat-charging")
	_ = r.UpdateHeartbeat("lowbat-charging", HealthTelemetry{BatteryLevel: 5, ThermalTempC: 35, IsCharging: true})
	_ = r.AssignToGroup("lowbat-charging", "group-a")

	healthy := r.GetHealthyByGroup("group-a", 15.0, 45.0)
	if len(healthy) != 2 {
		t.Fatalf("expected 2 healthy nodes, got %d", len(healthy))
	}

	// Verify the two healthy nodes are correct
	ids := make(map[string]bool)
	for _, n := range healthy {
		ids[n.DeviceID] = true
	}
	if !ids["healthy"] {
		t.Error("expected 'healthy' to be in healthy list")
	}
	if !ids["lowbat-charging"] {
		t.Error("expected 'lowbat-charging' to be in healthy list (low but charging)")
	}
	if ids["hot"] {
		t.Error("'hot' should not be in healthy list")
	}
	if ids["lowbat"] {
		t.Error("'lowbat' should not be in healthy list (low and not charging)")
	}
	if ids["offline"] {
		t.Error("'offline' should not be in healthy list")
	}
}

func TestList(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Register("serial-002", "", "")

	nodes := r.List()
	if len(nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(nodes))
	}
}

func TestCount(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Register("serial-002", "", "")

	if count := r.Count(); count != 2 {
		t.Errorf("expected count 2, got %d", count)
	}
}

func TestPurgeStale(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")
	_ = r.Pair("serial-001")
	_ = r.UpdateHeartbeat("serial-001", HealthTelemetry{})
	_ = r.Register("serial-002", "", "")
	_ = r.Pair("serial-002")
	_ = r.UpdateHeartbeat("serial-002", HealthTelemetry{})

	// Mark serial-001's heartbeat as very old by manipulating directly
	r.mu.Lock()
	r.nodes["serial-001"].LastHeartbeat = time.Now().Add(-5 * time.Minute)
	r.mu.Unlock()

	count := r.PurgeStale(60 * time.Second)
	if count != 1 {
		t.Errorf("expected 1 stale node, got %d", count)
	}

	n1, _ := r.Get("serial-001")
	if n1.State != NodeStateOffline {
		t.Errorf("expected serial-001 to be offline after purge, got %s", n1.State)
	}

	n2, _ := r.Get("serial-002")
	if n2.State != NodeStateOnline {
		t.Errorf("expected serial-002 to remain online after purge, got %s", n2.State)
	}
}

func TestPurgeStaleOnlyOnline(t *testing.T) {
	r := New()
	_ = r.Register("serial-001", "", "")

	// Unpaired node shouldn't be affected by purge
	count := r.PurgeStale(1 * time.Second)
	if count != 0 {
		t.Errorf("expected 0 stale nodes for unpaired, got %d", count)
	}
}

func TestConcurrency(t *testing.T) {
	r := New()
	done := make(chan struct{}, 2)

	// Concurrent writes
	go func() {
		for i := 0; i < 100; i++ {
			_ = r.Register(fmt.Sprintf("serial-%04d", i), "", "")
		}
		done <- struct{}{}
	}()

	// Concurrent reads
	go func() {
		for i := 0; i < 100; i++ {
			_ = r.Count()
			_ = r.List()
		}
		done <- struct{}{}
	}()

	<-done
	<-done

	if count := r.Count(); count != 100 {
		t.Errorf("expected 100 nodes after concurrent write, got %d", count)
	}
}

// Test helpers

func TestIsNotFound(t *testing.T) {
	if !IsNotFound(ErrNotFound) {
		t.Error("IsNotFound should return true for ErrNotFound")
	}
	if IsNotFound(nil) {
		t.Error("IsNotFound should return false for nil")
	}
	if IsNotFound(ErrAlreadyRegistered) {
		t.Error("IsNotFound should return false for ErrAlreadyRegistered")
	}
}

func TestIsAlreadyRegistered(t *testing.T) {
	if !IsAlreadyRegistered(ErrAlreadyRegistered) {
		t.Error("IsAlreadyRegistered should return true for ErrAlreadyRegistered")
	}
	if IsAlreadyRegistered(nil) {
		t.Error("IsAlreadyRegistered should return false for nil")
	}
}

func TestIsWrongState(t *testing.T) {
	if !IsWrongState(ErrWrongState) {
		t.Error("IsWrongState should return true for ErrWrongState")
	}
	if IsWrongState(nil) {
		t.Error("IsWrongState should return false for nil")
	}
}

func TestSetExcludeReason(t *testing.T) {
	reg := New()
	reg.Register("d1", "name", "")

	if err := reg.SetExcludeReason("d1", "overheating"); err != nil {
		t.Fatalf("SetExcludeReason error: %v", err)
	}
	node, _ := reg.Get("d1")
	if node.ExcludeReason != "overheating" {
		t.Errorf("expected overheating, got %q", node.ExcludeReason)
	}

	// Nonexistent
	if err := reg.SetExcludeReason("nonexistent", "x"); err == nil {
		t.Error("expected error for nonexistent device")
	}
}

func TestClearExcludeReason(t *testing.T) {
	reg := New()
	reg.Register("d1", "name", "")
	reg.SetExcludeReason("d1", "overheating")

	if err := reg.ClearExcludeReason("d1"); err != nil {
		t.Fatalf("ClearExcludeReason error: %v", err)
	}
	node, _ := reg.Get("d1")
	if node.ExcludeReason != "" {
		t.Errorf("expected empty, got %q", node.ExcludeReason)
	}

	// Nonexistent
	if err := reg.ClearExcludeReason("nonexistent"); err == nil {
		t.Error("expected error for nonexistent device")
	}
}

func TestListOnline(t *testing.T) {
	reg := New()
	reg.Register("d1", "a", "")
	reg.Register("d2", "b", "")

	// Only paired+heartbeat nodes are online
	reg.Pair("d1")
	reg.UpdateHeartbeat("d1", HealthTelemetry{BatteryLevel: 80})

	online := reg.ListOnline()
	if len(online) != 1 {
		t.Fatalf("expected 1 online node, got %d", len(online))
	}
	if online[0].DeviceID != "d1" {
		t.Errorf("expected d1, got %s", online[0].DeviceID)
	}
}

func TestListOnline_Empty(t *testing.T) {
	reg := New()
	online := reg.ListOnline()
	if len(online) != 0 {
		t.Errorf("expected 0 online nodes, got %d", len(online))
	}
}
