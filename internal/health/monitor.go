package health

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
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
	// reasonUnreachable is probe-managed (see probeSidecars): the phone
	// heartbeats fine but its inference server does not answer /health.
	reasonUnreachable = "inference_unreachable"

	ActionStandbyPromote  ActionType = "standby_promote"
	ActionNodeOffline     ActionType = "node_offline"
	ActionNodeOverheat    ActionType = "node_overheat"
	ActionNodeDraining    ActionType = "node_draining"
	ActionNodeDrained     ActionType = "node_drained"
	ActionNodeReEntered   ActionType = "node_reentered"
	ActionNodeUnreachable ActionType = "node_unreachable"
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
		case ActionNodeUnreachable:
			_ = el.Write(log.EventError, deviceID, log.SeverityError, "inference server unreachable — excluding from routing")
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

	// Sidecar probing: every check cycle the monitor GETs
	// http://<phone>:<InferencePort>/health on each online phone.
	// InferencePort <= 0 disables probing (the default for bare
	// MonitorConfig values; main wires cluster.inference_port).
	InferencePort         int
	ProbeTimeout          time.Duration // per-probe deadline (default 3s)
	ProbeFailureThreshold int           // consecutive failures before exclusion (default 3)
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
		ProbeTimeout:             3 * time.Second,
		ProbeFailureThreshold:    3,
	}
}

// Monitor runs periodic health checks on the node registry.
type Monitor struct {
	reg     *registry.Registry
	cfg     MonitorConfig
	log     *slog.Logger
	metrics *Metrics

	actions []Action

	// checkHooks run at the end of every check cycle. Used to piggyback
	// periodic maintenance (e.g. syncing circuit breaker state into the
	// registry) on the monitor's 5-second loop.
	checkHooks []func()

	// probeFn performs one sidecar health probe. Overridable in tests.
	probeFn func(ctx context.Context, url string) error

	// probeFailures counts consecutive probe failures per device.
	// Guarded by mu.
	probeFailures map[string]int

	mu      sync.Mutex
	stopCh  chan struct{}
	running bool
}

// NewMonitor creates a health monitor.
func NewMonitor(reg *registry.Registry, cfg MonitorConfig) *Monitor {
	if cfg.ProbeTimeout <= 0 {
		cfg.ProbeTimeout = 3 * time.Second
	}
	if cfg.ProbeFailureThreshold <= 0 {
		cfg.ProbeFailureThreshold = 3
	}
	return &Monitor{
		reg:           reg,
		cfg:           cfg,
		log:           slog.With("component", "health-monitor"),
		metrics:       NewMetrics(),
		actions:       make([]Action, 0),
		probeFn:       defaultSidecarProbe,
		probeFailures: make(map[string]int),
	}
}

// AddAction registers an action hook called on state transitions.
func (m *Monitor) AddAction(a Action) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.actions = append(m.actions, a)
}

// AddCheckHook registers a function invoked at the end of every check
// cycle (every CheckInterval, default 5s). Hooks must be fast and must
// not block.
func (m *Monitor) AddCheckHook(fn func()) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.checkHooks = append(m.checkHooks, fn)
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

	// Step 2b: Probe each online phone's inference server
	m.probeSidecars(ctx)

	// Step 3: Update Prometheus metrics
	m.updateMetrics()

	// Step 4: Run registered check hooks (e.g. circuit breaker sync)
	m.mu.Lock()
	hooks := make([]func(), len(m.checkHooks))
	copy(hooks, m.checkHooks)
	m.mu.Unlock()
	for _, fn := range hooks {
		fn()
	}
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

	// inference_unreachable is owned by the sidecar probe: only a
	// successful probe (probeSidecars) may clear or replace it —
	// telemetry evaluation must not resurrect the node.
	if reason == reasonUnreachable {
		return reasonUnreachable
	}

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

// probeSidecars actively GETs each online phone's inference server
// /health endpoint. A phone can heartbeat perfectly (the PhononService
// is alive) while its inference server is wedged — heartbeats alone
// don't prove the phone can serve requests.
//
// After ProbeFailureThreshold consecutive failures the node is excluded
// with reason "inference_unreachable"; the first successful probe clears
// that reason (and only that reason — telemetry-managed exclusions are
// untouched). Disabled when InferencePort <= 0.
func (m *Monitor) probeSidecars(ctx context.Context) {
	if m.cfg.InferencePort <= 0 {
		return
	}

	nodes := m.reg.ListOnline()
	if len(nodes) == 0 {
		return
	}

	// Probe concurrently: sequential 3s timeouts across a fleet would
	// blow the 5s check interval.
	var wg sync.WaitGroup
	for i := range nodes {
		node := nodes[i] // copy
		wg.Add(1)
		go func() {
			defer wg.Done()
			url := fmt.Sprintf("http://%s:%d/health", node.IPAddress, m.cfg.InferencePort)
			pctx, cancel := context.WithTimeout(ctx, m.cfg.ProbeTimeout)
			err := m.probeFn(pctx, url)
			cancel()
			m.recordProbeResult(ctx, &node, url, err)
		}()
	}
	wg.Wait()
}

// recordProbeResult updates the consecutive-failure counter and the
// node's exclusion state for one probe outcome.
func (m *Monitor) recordProbeResult(ctx context.Context, node *registry.Node, url string, probeErr error) {
	m.mu.Lock()
	if probeErr != nil {
		m.probeFailures[node.DeviceID]++
	} else {
		delete(m.probeFailures, node.DeviceID)
	}
	failures := m.probeFailures[node.DeviceID]
	m.mu.Unlock()

	if probeErr != nil {
		m.log.Debug("sidecar probe failed",
			"device_id", node.DeviceID, "url", url,
			"consecutive_failures", failures, "error", probeErr)

		// Exclude after the threshold — but never overwrite a
		// telemetry-managed exclusion (overheating etc.); if that
		// clears while the phone is still unreachable, the counter is
		// still over threshold and the next cycle excludes it.
		if failures >= m.cfg.ProbeFailureThreshold && node.ExcludeReason == "" {
			m.log.Warn("inference server unreachable — excluding node",
				"device_id", node.DeviceID, "url", url,
				"consecutive_failures", failures)
			_ = m.reg.SetExcludeReason(node.DeviceID, reasonUnreachable)
			m.fireActions(ctx, node, ActionNodeUnreachable)
		}
		return
	}

	// Probe succeeded: clear the exclusion iff the probe owns it.
	if node.ExcludeReason == reasonUnreachable {
		m.log.Info("inference server reachable again — node re-entering routing",
			"device_id", node.DeviceID)
		_ = m.reg.ClearExcludeReason(node.DeviceID)
		m.fireActions(ctx, node, ActionNodeReEntered)
	}
}

// probeHTTPClient is shared by all sidecar probes. No global timeout:
// each probe carries a per-attempt context deadline.
var probeHTTPClient = &http.Client{}

// defaultSidecarProbe GETs the sidecar /health endpoint and treats
// anything but a 200 as a failure.
func defaultSidecarProbe(ctx context.Context, url string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := probeHTTPClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("health endpoint returned HTTP %d", resp.StatusCode)
	}
	return nil
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
