package health

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/log"
	"github.com/chezgoulet/phonon/internal/registry"
)

// ActionType identifies what triggered the action.
type ActionType string

const (
	reasonOverheating = "overheating"
	reasonLowBattery  = "low-battery"
	reasonDraining    = "battery-draining"
	reasonDegraded    = "degraded"
	ActionStandbyPromote ActionType = "standby_promote"
	ActionNodeOffline    ActionType = "node_offline"
	ActionNodeOverheat   ActionType = "node_overheat"
	ActionNodeDraining   ActionType = "node_draining"
	ActionNodeDrained    ActionType = "node_drained"
	ActionNodeReEntered  ActionType = "node_reentered"
)

// Action is a hook called when the health monitor detects a state transition.
type Action func(_ context.Context, deviceID string, groupName string, actionType ActionType)

// WithEventLog returns an action that writes health state transitions to an event log.
func WithEventLog(el *log.EventLog) Action {
	return func(_ context.Context, deviceID, groupName string, actionType ActionType) {
		switch actionType {
		case ActionNodeOffline:
			_ = el.Write(log.EventNodeOffline, deviceID, log.SeverityWarning, "node went offline")
		case ActionNodeOverheat:
			_ = el.Write(log.EventNodeOverheated, deviceID, log.SeverityError, "node overheating or low battery")
		case ActionNodeReEntered:
			_ = el.Write(log.EventNodeOnline, deviceID, log.SeverityInfo, "node re-entered routing pool")
		case ActionNodeDraining:
			_ = el.Write(log.EventNodeDraining, deviceID, log.SeverityWarning, "battery draining — reducing routing priority")
		case ActionNodeDrained:
			_ = el.Write(log.EventNodeDrained, deviceID, log.SeverityError, "battery depleted — offloading models")
		case ActionStandbyPromote:
			_ = el.Write(log.EventInfo, deviceID, log.SeverityInfo, "standby node promoted to active")
		}
		_ = groupName
	}
}

// MonitorConfig holds the thresholds and settings for the health monitor.
// Defaults match the config package defaults.
type MonitorConfig struct {
	OverheatThreshold        float64 // °C
	OverheatReentryThreshold float64 // °C
	DrainingThreshold        float64 // % — enter draining state when unplugged below this
	BatteryLowThreshold      float64 // %
	BatteryReentryThreshold  float64 // %
	BatteryCapacityThreshold float64 // % — mark charger-dependent below this
	OfflineTimeout           time.Duration
	CheckInterval            time.Duration // how often to run the check loop
}

// DefaultMonitorConfig returns a config with sensible defaults.
func DefaultMonitorConfig() MonitorConfig {
	return MonitorConfig{
		OverheatThreshold:        45,
		OverheatReentryThreshold: 40,
		DrainingThreshold:        50,
		BatteryLowThreshold:      15,
		BatteryReentryThreshold:  30,
		BatteryCapacityThreshold: 80,
		OfflineTimeout:           60 * time.Second,
		CheckInterval:            5 * time.Second,
	}
}

// Monitor runs periodic health checks on the node registry.
type Monitor struct {
	reg     *registry.Registry
	cfg     MonitorConfig
	log     *slog.Logger
	metrics *Metrics

	actions []Action

	mu       sync.Mutex
	stopCh   chan struct{}
	running  bool
}

// NewMonitor creates a health monitor.
func NewMonitor(reg *registry.Registry, cfg MonitorConfig) *Monitor {
	return &Monitor{
		reg:     reg,
		cfg:     cfg,
		log:     slog.With("component", "health-monitor"),
		metrics: NewMetrics(),
		actions: make([]Action, 0),
	}
}

// AddAction registers an action hook called on state transitions.
func (m *Monitor) AddAction(a Action) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.actions = append(m.actions, a)
}

// RegisterMetrics registers Prometheus metrics and returns the metrics instance.
func (m *Monitor) RegisterMetrics() *Metrics {
	m.metrics.Register()
	return m.metrics
}

// Metrics returns the metrics instance for updating from other components.
func (m *Monitor) Metrics() *Metrics {
	return m.metrics
}

// Start begins the periodic health check loop in a goroutine.
func (m *Monitor) Start() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.running {
		return
	}

	m.running = true
	m.stopCh = make(chan struct{})
	go m.loop()
	m.log.Info("health monitor started", "interval", m.cfg.CheckInterval)
}

// Stop terminates the health check loop.
func (m *Monitor) Stop() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.running {
		return
	}

	m.running = false
	close(m.stopCh)
	m.log.Info("health monitor stopped")
}

// loop runs health checks at the configured interval.
func (m *Monitor) loop() {
	ticker := time.NewTicker(m.cfg.CheckInterval)
	defer ticker.Stop()

	// Run an immediate check on start
	m.Check()

	for {
		select {
		case <-ticker.C:
			m.Check()
		case <-m.stopCh:
			return
		}
	}
}

// Check runs one full pass of health evaluation.
func (m *Monitor) Check() {
	ctx := context.Background()

	// Step 1: Purge stale nodes (offline detection)
	m.checkStaleNodes(ctx)

	// Step 2: Evaluate each online node for health conditions
	m.evaluateNodes(ctx)

	// Step 3: Update Prometheus metrics
	m.updateMetrics()
}

// checkStaleNodes marks nodes as offline if they haven't sent a heartbeat.
func (m *Monitor) checkStaleNodes(ctx context.Context) {
	stale := m.reg.PurgeStale(m.cfg.OfflineTimeout)
	if stale > 0 {
		m.log.Info("marked stale nodes offline", "count", stale)
	}
	_ = ctx
}

// evaluateNodes checks each online node for overheat, low battery, draining,
// and degraded capacity. Sets or clears ExcludeReason with hysteresis.
func (m *Monitor) evaluateNodes(ctx context.Context) {
	nodes := m.reg.ListOnline()

	for i := range nodes {
		node := &nodes[i]
		oldReason := node.ExcludeReason
		newReason := m.evaluateNode(node)

		if newReason != oldReason {
			if newReason != "" {
				m.log.Info("node excluded from routing",
					"device_id", node.DeviceID,
					"reason", newReason,
					"temp", node.Telemetry.ThermalTempC,
					"battery", node.Telemetry.BatteryLevel,
					"charging", node.Telemetry.IsCharging)
				_ = m.reg.SetExcludeReason(node.DeviceID, newReason)

				switch newReason {
				case reasonDraining:
					m.fireActions(ctx, node, ActionNodeDraining)
				case reasonLowBattery:
					m.fireActions(ctx, node, ActionNodeDrained)
				default:
					m.fireActions(ctx, node, ActionNodeOverheat)
				}
			} else {
				m.log.Info("node re-entered routing",
					"device_id", node.DeviceID,
					"old_reason", oldReason,
					"temp", node.Telemetry.ThermalTempC,
					"battery", node.Telemetry.BatteryLevel)
				_ = m.reg.ClearExcludeReason(node.DeviceID)
				m.fireActions(ctx, node, ActionNodeReEntered)
			}
		}
	}
}

// evaluateNode determines why a node should be excluded (or empty if healthy).
// Priority: overheating > low battery > draining > degraded > healthy.
func (m *Monitor) evaluateNode(node *registry.Node) string {
	reason := node.ExcludeReason

	// --- Overheat check with hysteresis ---
	if node.Telemetry.ThermalTempC >= m.cfg.OverheatThreshold {
		return reasonOverheating
	}
	if reason == reasonOverheating && node.Telemetry.ThermalTempC >= m.cfg.OverheatReentryThreshold {
		return reasonOverheating
	}

	// --- Low battery check (highest priority among battery states) ---
	if node.Telemetry.BatteryLevel < m.cfg.BatteryLowThreshold && !node.Telemetry.IsCharging {
		return reasonLowBattery
	}
	if reason == reasonLowBattery {
		if !node.Telemetry.IsCharging && node.Telemetry.BatteryLevel < m.cfg.BatteryReentryThreshold {
			return reasonLowBattery
		}
	}

	// --- Draining check (unplugged, above low-battery threshold) ---
	if !node.Telemetry.IsCharging && node.Telemetry.BatteryLevel < m.cfg.DrainingThreshold {
		return reasonDraining
	}
	if reason == reasonDraining {
		// Stay draining until plugged in OR battery drops to low threshold
		if !node.Telemetry.IsCharging && node.Telemetry.BatteryLevel > m.cfg.BatteryLowThreshold {
			return reasonDraining
		}
	}

	// --- Degraded capacity (charger-dependent) ---
	if node.Telemetry.BatteryCapacityPct > 0 &&
		node.Telemetry.BatteryCapacityPct < m.cfg.BatteryCapacityThreshold &&
		!node.Telemetry.IsCharging &&
		node.ExcludeReason == "" {
		return reasonDegraded
	}
	if reason == reasonDegraded && node.Telemetry.IsCharging {
		return ""
	}
	if reason == reasonDegraded {
		return reasonDegraded
	}

	return ""
}

// fireActions calls all registered actions for a node state transition.
func (m *Monitor) fireActions(ctx context.Context, node *registry.Node, actionType ActionType) {
	m.mu.Lock()
	actions := make([]Action, len(m.actions))
	copy(actions, m.actions)
	m.mu.Unlock()

	for i := range actions {
		actions[i](ctx, node.DeviceID, node.Group, actionType)
	}
}

// updateMetrics refreshes all Prometheus gauges from the current registry state.
func (m *Monitor) updateMetrics() {
	nodes := m.reg.List()

	m.metrics.NodesOnline.Reset()
	m.metrics.NodesOffline.Reset()

	totalOverheating := 0

	for i := range nodes {
		n := &nodes[i]
		m.metrics.BatteryLevel.WithLabelValues(n.DeviceID).Set(n.Telemetry.BatteryLevel)
		m.metrics.ThermalTempC.WithLabelValues(n.DeviceID).Set(n.Telemetry.ThermalTempC)
		m.metrics.QueueDepth.WithLabelValues(n.DeviceID).Set(0)

		if n.State == registry.NodeStateOnline && n.ExcludeReason == reasonOverheating {
			totalOverheating++
		}

		if n.Group != "" {
			groupLabel := n.Group
			switch n.State {
			case registry.NodeStateOnline:
				m.metrics.NodesOnline.WithLabelValues(groupLabel).Inc()
			case registry.NodeStateOffline:
				m.metrics.NodesOffline.WithLabelValues(groupLabel).Inc()
			}
		}
	}

	m.metrics.NodesOverheating.Set(float64(totalOverheating))
}
