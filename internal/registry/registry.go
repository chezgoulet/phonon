package registry

import (
	"fmt"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/log"
)

// Registry is the thread-safe in-memory node registry.
// All runtime state is ephemeral and reconstructed from heartbeats.
type Registry struct {
	mu       sync.RWMutex
	nodes    map[string]*Node
	eventLog *log.EventLog
}

// New creates an empty registry.
func New() *Registry {
	return &Registry{
		nodes: make(map[string]*Node),
	}
}

// SetEventLog attaches an event log for recording node lifecycle events.
func (r *Registry) SetEventLog(el *log.EventLog) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.eventLog = el
}

// Register adds a new node in unpaired state.
// Returns ErrAlreadyRegistered if the device already exists.
func (r *Registry) Register(deviceID, name, ipAddress string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, exists := r.nodes[deviceID]; exists {
		return fmt.Errorf("%w: %s", ErrAlreadyRegistered, deviceID)
	}

	now := time.Now()
	r.nodes[deviceID] = &Node{
		DeviceID:    deviceID,
		Name:        name,
		State:       NodeStateUnpaired,
		RegisteredAt: now,
		IPAddress:   ipAddress,
	}

	if r.eventLog != nil {
		_ = r.eventLog.Write(log.EventNodeJoined, deviceID, log.SeverityInfo, "node registered: "+name)
	}

	return nil
}

// Pair transitions a node from unpaired to paired state.
// Returns ErrNotFound if the device doesn't exist.
// Returns ErrWrongState if not in unpaired state.
func (r *Registry) Pair(deviceID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}
	if node.State != NodeStateUnpaired {
		return fmt.Errorf("%w: expected state unpaired, got %s", ErrWrongState, node.State)
	}

	node.State = NodeStatePaired
	node.PairedAt = time.Now()

	if r.eventLog != nil {
		_ = r.eventLog.Write(log.EventPairingDone, deviceID, log.SeverityInfo, "node paired")
	}

	return nil
}

// UpdateHeartbeat updates health telemetry and sets last heartbeat timestamp.
// If the node is in paired state, transitions to online.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) UpdateHeartbeat(deviceID string, telemetry HealthTelemetry) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}

	wasOffline := node.State == NodeStateOffline
	node.Telemetry = telemetry
	node.LastHeartbeat = time.Now()

	// If paired or offline, transition to online on first heartbeat
	if node.State == NodeStatePaired || node.State == NodeStateOffline {
		node.State = NodeStateOnline
	}

	if r.eventLog != nil && wasOffline {
		_ = r.eventLog.Write(log.EventNodeOnline, deviceID, log.SeverityInfo, "node came online")
	}

	return nil
}

// AssignToGroup assigns a node to a group.
// Returns ErrNotFound if the device doesn't exist.
// Returns ErrWrongState if the node is not paired or online.
func (r *Registry) AssignToGroup(deviceID, group string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}
	if node.State != NodeStatePaired && node.State != NodeStateOnline {
		return fmt.Errorf("%w: expected state paired or online, got %s", ErrWrongState, node.State)
	}

	node.Group = group
	return nil
}

// Unassign removes a node from its group. Node stays in its current state.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) Unassign(deviceID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}

	node.Group = ""
	return nil
}

// SetOnline transitions a node to online state.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) SetOnline(deviceID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}

	node.State = NodeStateOnline
	node.LastHeartbeat = time.Now()
	return nil
}

// SetOffline transitions a node to offline state.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) SetOffline(deviceID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}

	node.State = NodeStateOffline
	return nil
}

// Get returns a single node by device ID.
// Returns nil and false if not found.
func (r *Registry) Get(deviceID string) (Node, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return Node{}, false
	}
	return *node, true
}

// GetByGroup returns all nodes assigned to the given group.
func (r *Registry) GetByGroup(group string) []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]Node, 0, len(r.nodes))
	for _, node := range r.nodes {
		if node.Group == group {
			result = append(result, *node)
		}
	}
	return result
}

// GetHealthyByGroup returns nodes that are online, not overheating, and
// not low-battery-unplugged or draining. Battery threshold defaults to 15%.
// Thermal threshold defaults to 45°C. Draining nodes are excluded because
// the model reconciler should migrate models from them before they go offline.
func (r *Registry) GetHealthyByGroup(group string, batteryThreshold, thermalThreshold float64) []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]Node, 0, len(r.nodes))
	for _, node := range r.nodes {
		if node.Group != group {
			continue
		}
		if node.State != NodeStateOnline {
			continue
		}
		if node.Telemetry.ThermalTempC > thermalThreshold {
			continue
		}
		// Exclude nodes in draining or low-battery state (matching health package reasons)
		if node.ExcludeReason == "battery-draining" || node.ExcludeReason == "low-battery" {
			continue
		}
		if node.Telemetry.BatteryLevel < batteryThreshold && !node.Telemetry.IsCharging {
			continue
		}
		result = append(result, *node)
	}
	return result
}

// List returns all registered nodes.
func (r *Registry) List() []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]Node, 0, len(r.nodes))
	for _, node := range r.nodes {
		result = append(result, *node)
	}
	return result
}

// Count returns the total number of registered nodes.
func (r *Registry) Count() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.nodes)
}

// SetExcludeReason sets the reason a node is excluded from routing.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) SetExcludeReason(deviceID, reason string) error {
	return r.updateField(deviceID, func(n *Node) {
		n.ExcludeReason = reason
	})
}

// SetDeviceModel sets the device model string (e.g. "Pixel 9 Pro").
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) SetDeviceModel(deviceID, model string) error {
	return r.updateField(deviceID, func(n *Node) {
		n.DeviceModel = model
	})
}

// SetDeviceIP sets the IP address for a node.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) SetDeviceIP(deviceID, ipAddress string) error {
	return r.updateField(deviceID, func(n *Node) {
		n.IPAddress = ipAddress
	})
}

// SetModelStatus updates the model status on a node (what model is loaded).
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) SetModelStatus(deviceID string, status ModelStatus) error {
	return r.updateField(deviceID, func(n *Node) {
		n.ModelStatus = status
	})
}

// updateField is a helper that locks, looks up the node, and applies a mutation.
func (r *Registry) updateField(deviceID string, fn func(*Node)) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}

	fn(node)
	return nil
}

// ClearExcludeReason removes any exclusion reason from a node.
// Returns ErrNotFound if the device doesn't exist.
func (r *Registry) ClearExcludeReason(deviceID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	node, exists := r.nodes[deviceID]
	if !exists {
		return fmt.Errorf("%w: %s", ErrNotFound, deviceID)
	}

	node.ExcludeReason = ""
	return nil
}

// ListOnline returns all nodes in online state.
func (r *Registry) ListOnline() []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]Node, 0, len(r.nodes))
	for _, node := range r.nodes {
		if node.State == NodeStateOnline {
			result = append(result, *node)
		}
	}
	return result
}

// PurgeStale marks nodes as offline if their last heartbeat exceeds the timeout.
// Returns the number of nodes marked offline.
func (r *Registry) PurgeStale(timeout time.Duration) int {
	r.mu.Lock()
	defer r.mu.Unlock()

	deadline := time.Now().Add(-timeout)
	count := 0
	var staleIDs []string

	for _, node := range r.nodes {
		if node.State == NodeStateOnline && node.LastHeartbeat.Before(deadline) {
			node.State = NodeStateOffline
			staleIDs = append(staleIDs, node.DeviceID)
			count++
		}
	}

	if count > 0 && r.eventLog != nil {
		for _, id := range staleIDs {
			_ = r.eventLog.Write(log.EventNodeLeft, id, log.SeverityWarning, "node went offline (stale heartbeat)")
		}
	}

	return count
}
