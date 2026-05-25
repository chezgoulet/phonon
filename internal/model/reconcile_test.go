package model

import (
	"context"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/config"
	"github.com/chezgoulet/phonon/internal/registry"
)

// mockIssuer implements CommandIssuer for testing.
type mockIssuer struct {
	pushCommands   []string // deviceID+model
	loadCommands   []string
	unloadCommands []string
	connected      map[string]bool
}

func newMockIssuer() *mockIssuer {
	return &mockIssuer{
		connected: make(map[string]bool),
	}
}

func (m *mockIssuer) SendModelPush(deviceID, model, _ string, _ string, _ int64) (string, error) {
	m.pushCommands = append(m.pushCommands, deviceID+":"+model)
	return "cmd-" + deviceID, nil
}

func (m *mockIssuer) SendModelLoad(deviceID, model string) (string, error) {
	m.loadCommands = append(m.loadCommands, deviceID+":"+model)
	return "cmd-" + deviceID, nil
}

func (m *mockIssuer) SendModelUnload(deviceID string) (string, error) {
	m.unloadCommands = append(m.unloadCommands, deviceID)
	return "cmd-" + deviceID, nil
}

func (m *mockIssuer) HasConnection(deviceID string) bool {
	return m.connected[deviceID]
}

func registerNode(reg *registry.Registry, deviceID string, state registry.NodeState, modelName string, loaded bool) {
	if err := reg.Register(deviceID, "", ""); err != nil && !registry.IsAlreadyRegistered(err) {
		panic(err)
	}
	reg.SetOnline(deviceID)
	reg.AssignToGroup(deviceID, "alpha")
	// Set model status via the returned pointer (single-threaded test)
	node, _ := reg.Get(deviceID)
	node.State = state
	node.ModelStatus = registry.ModelStatus{
		Name:   modelName,
		Loaded: loaded,
	}
}

const testPhone01 = "phone-01"

func TestReconcileGroup_NoActionNeeded(t *testing.T) {
	reg := registry.New()
	registerNode(reg, testPhone01, registry.NodeStateOnline, "llama3.2:1b", true)

	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	group := config.GroupConfig{
		Name:  "alpha",
		Model: "llama3.2:1b",
	}

	steps := reconciler.ReconcileGroup(&group)
	if len(steps) != 0 {
		t.Errorf("expected 0 steps, got %d: %+v", len(steps), steps)
	}
}

func TestReconcileGroup_NeedsLoad(t *testing.T) {
	reg := registry.New()
	registerNode(reg, testPhone01, registry.NodeStateOnline, "llama3.2:1b", false)

	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	// Add model to cache
	cache.mu.Lock()
	cache.entries["llama3.2:1b"] = &CacheEntry{Name: "llama3.2:1b", Path: "/fake/path", SizeBytes: 1000}
	cache.mu.Unlock()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	group := config.GroupConfig{
		Name:   "alpha",
		Model:  "llama3.2:1b",
		Phones: []string{testPhone01},
	}

	steps := reconciler.ReconcileGroup(&group)
	if len(steps) == 0 {
		t.Fatal("expected at least 1 step")
	}

	// Should push since not loaded
	found := false
	for _, s := range steps {
		if s.DeviceID == testPhone01 && s.Action == ActionPush {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("expected push step, got steps: %+v", steps)
	}
}

func TestReconcileGroup_NeedsUnload(t *testing.T) {
	reg := registry.New()
	registerNode(reg, testPhone01, registry.NodeStateOnline, "old-model", true)

	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	cache.mu.Lock()
	cache.entries["new-model"] = &CacheEntry{Name: "new-model", Path: "/fake/path"}
	cache.mu.Unlock()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	group := config.GroupConfig{
		Name:   "alpha",
		Model:  "new-model",
		Phones: []string{testPhone01},
	}

	steps := reconciler.ReconcileGroup(&group)
	if len(steps) == 0 {
		t.Fatal("expected steps for model change")
	}
}

func TestReconcileGroup_PhoneNotConnected(t *testing.T) {
	reg := registry.New()
	registerNode(reg, testPhone01, registry.NodeStateOnline, "", false)

	issuer := newMockIssuer() // no connections registered
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	group := config.GroupConfig{
		Name:   "alpha",
		Model:  "new-model",
		Phones: []string{testPhone01},
	}

	// Model not cached and URL would be empty — no steps
	steps := reconciler.ReconcileGroup(&group)
	for _, s := range steps {
		if s.DeviceID == testPhone01 {
			t.Errorf("expected no steps for disconnected phone, got %+v", s)
		}
	}
}

func TestReconcileGroup_MultiplePhones(t *testing.T) {
	reg := registry.New()

	// phone-01 has the right model loaded
	registerNode(reg, testPhone01, registry.NodeStateOnline, "llama3.2:1b", true)

	// phone-02 needs the model
	registerNode(reg, "phone-02", registry.NodeStateOnline, "", false)

	// phone-03 is offline
	registerNode(reg, "phone-03", registry.NodeStateOffline, "", false)

	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	issuer.connected["phone-02"] = true

	cache := NewCache(t.TempDir(), nil)
	cache.Init()
	cache.mu.Lock()
	cache.entries["llama3.2:1b"] = &CacheEntry{Name: "llama3.2:1b", Path: "/fake/path"}
	cache.mu.Unlock()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	group := config.GroupConfig{
		Name:  "alpha",
		Model: "llama3.2:1b",
		Phones: []string{testPhone01, "phone-02", "phone-03"},
	}

	steps := reconciler.ReconcileGroup(&group)

	// phone-01 should have no action (already loaded)
	// phone-02 should have push or load
	// phone-03 should have no action (offline, issuer not connected)
	for _, s := range steps {
		if s.DeviceID == testPhone01 && s.Action != ActionNone {
			t.Errorf("phone-01 should have no action, got %+v", s)
		}
	}

	// At least one step for phone-02
	found := false
	for _, s := range steps {
		if s.DeviceID == "phone-02" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected step for phone-02")
	}
}

func TestReconcileGroup_Standby(t *testing.T) {
	reg := registry.New()
	registerNode(reg, testPhone01, registry.NodeStateOnline, "model", true)
	registerNode(reg, "standby-01", registry.NodeStateOnline, "old-model", false)

	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	issuer.connected["standby-01"] = true

	cache := NewCache(t.TempDir(), nil)
	cache.Init()
	cache.mu.Lock()
	cache.entries["model"] = &CacheEntry{Name: "model", Path: "/fake/path"}
	cache.mu.Unlock()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	group := config.GroupConfig{
		Name:    "alpha",
		Model:   "model",
		Phones:  []string{testPhone01},
		Standby: []string{"standby-01"},
	}

	steps := reconciler.ReconcileGroup(&group)

	// Standby should get a push action
	found := false
	for _, s := range steps {
		if s.DeviceID == "standby-01" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected step for standby phone")
	}
}

func TestReconcilerStartStop(t *testing.T) {
	reg := registry.New()
	issuer := newMockIssuer()
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")
	reconciler.SetInterval(100 * time.Millisecond)

	ctx := context.Background()
	if err := reconciler.Start(ctx, nil); err != nil {
		t.Fatalf("Start: %v", err)
	}

	// Should error on second start
	if err := reconciler.Start(ctx, nil); err == nil {
		t.Error("expected error on double start")
	}

	reconciler.Stop()

	// Second stop should be a no-op
	reconciler.Stop()
}

func TestReconcilerExecutePush(t *testing.T) {
	reg := registry.New()
	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	step := ReconcilerStep{
		DeviceID:  testPhone01,
		Action:    ActionPush,
		ModelName: "llama3.2:1b",
		URL:       "http://coord:9876/api/v1/models/llama3.2:1b",
	}

	reconciler.executeStep(&step)

	if len(issuer.pushCommands) != 1 {
		t.Errorf("expected 1 push command, got %d", len(issuer.pushCommands))
	}
	if issuer.pushCommands[0] != "phone-01:llama3.2:1b" {
		t.Errorf("unexpected push: %q", issuer.pushCommands[0])
	}
}

func TestReconcilerExecuteLoad(t *testing.T) {
	reg := registry.New()
	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	step := ReconcilerStep{
		DeviceID:  testPhone01,
		Action:    ActionLoad,
		ModelName: "llama3.2:1b",
	}

	reconciler.executeStep(&step)

	if len(issuer.loadCommands) != 1 {
		t.Errorf("expected 1 load command, got %d", len(issuer.loadCommands))
	}
}

func TestReconcilerExecuteUnload(t *testing.T) {
	reg := registry.New()
	issuer := newMockIssuer()
	issuer.connected[testPhone01] = true
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	step := ReconcilerStep{
		DeviceID:  testPhone01,
		Action:    ActionUnload,
	}

	reconciler.executeStep(&step)

	if len(issuer.unloadCommands) != 1 {
		t.Errorf("expected 1 unload command, got %d", len(issuer.unloadCommands))
	}
}

func TestReconcilerExecuteNotConnected(t *testing.T) {
	reg := registry.New()
	issuer := newMockIssuer() // no connections
	cache := NewCache(t.TempDir(), nil)
	cache.Init()

	reconciler := NewReconciler(cache, reg, issuer, "http://coord:9876")

	step := ReconcilerStep{
		DeviceID:  testPhone01,
		Action:    ActionPush,
		ModelName: "test-model",
	}

	reconciler.executeStep(&step)

	if len(issuer.pushCommands) != 0 {
		t.Error("expected no command for disconnected device")
	}
}

func TestModelMetadataTypes(_ *testing.T) {
	// Verify types compile and work
	_ = SourceHuggingFace
	_ = SourceGeneric
}

func TestReconcilerActionNames(t *testing.T) {
	if ActionNone != 0 {
		t.Errorf("ActionNone should be 0")
	}
	if int(ActionUnload) != 3 {
		t.Errorf("ActionUnload should be 3")
	}
}
