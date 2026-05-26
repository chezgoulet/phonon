package discovery

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

// RegistrationCallback is called when a device is discovered or manually registered.
// The receiver should register the device with the node registry.
type RegistrationCallback func(deviceID string, deviceModel string, ip net.IP, port int) error

// Manager coordinates discovery modes (mDNS, manual) and feeds discoveries into
// the node registry via callbacks.
type Manager struct {
	mdns     Discoverer
	log      *slog.Logger
	callback RegistrationCallback
	mu       sync.Mutex

	discovered    map[string]DiscoveredDevice // deviceID → device
	mdnsCancel    context.CancelFunc
	mdnsRunning   bool

	stopCh chan struct{}
}

// NewManager creates a discovery manager.
// If mdns is nil, mDNS discovery is disabled.
func NewManager(mdns Discoverer, callback RegistrationCallback) *Manager {
	return &Manager{
		mdns:       mdns,
		log:        slog.With("component", "discovery"),
		callback:   callback,
		discovered: make(map[string]DiscoveredDevice),
		stopCh:     make(chan struct{}),
	}
}

// Start begins mDNS discovery if configured.
func (m *Manager) Start(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.mdns == nil {
		m.log.Info("mDNS discovery not configured", "enabled", false)
		return nil
	}

	if m.mdnsRunning {
		return fmt.Errorf("discovery already started")
	}

	ctx, cancel := context.WithCancel(ctx)
	m.mdnsCancel = cancel
	m.mdnsRunning = true

	// Single channel: discoverer writes here, processDiscovered reads here
	deviceCh := make(chan DiscoveredDevice, 20)

	// Start processing before passing the channel to the discoverer,
	// so there's no race where we miss the first entries
	go m.processDiscovered(ctx, deviceCh)

	if err := m.mdns.Start(ctx, deviceCh); err != nil {
		m.mdnsRunning = false
		cancel()
		return fmt.Errorf("start mDNS: %w", err)
	}

	m.log.Info("mDNS discovery started")
	return nil
}

// Stop terminates all discovery activity.
func (m *Manager) Stop() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.mdnsRunning {
		return nil
	}

	if m.mdns != nil {
		_ = m.mdns.Stop()
	}
	m.mdnsRunning = false
	m.mdnsCancel()

	m.log.Info("discovery stopped")
	return nil
}

// processDiscovered handles discovered devices, deduplicates, and calls the
// registration callback for new or updated devices.
func (m *Manager) processDiscovered(ctx context.Context, ch <-chan DiscoveredDevice) {
	for {
		select {
		case device, ok := <-ch:
			if !ok {
				// Channel closed — discoverer has stopped
				return
			}
			m.handleDiscovery(&device)
		case <-ctx.Done():
			return
		}
	}
}

// handleDiscovery processes a single discovered device.
func (m *Manager) handleDiscovery(device *DiscoveredDevice) {
	now := time.Now()
	device.LastSeen = now

	m.mu.Lock()
	prev, seen := m.discovered[device.DeviceID]
	m.discovered[device.DeviceID] = *device
	m.mu.Unlock()

	// Skip if seen before with same IP and port within the last 60 seconds
	if seen && prev.IP.Equal(device.IP) && prev.Port == device.Port &&
		now.Sub(prev.LastSeen) < 60*time.Second {
		return
	}

	if m.callback != nil {
		m.log.Info("device discovered",
			"device_id", device.DeviceID,
			"model", device.DeviceModel,
			"ip", device.IP,
			"port", device.Port)

		if err := m.callback(device.DeviceID, device.DeviceModel, device.IP, device.Port); err != nil {
			m.log.Warn("registration callback failed",
				"device_id", device.DeviceID,
				"error", err)
		}
	}
}

// RegisterManual manually registers a device by IP address.
// This is used as a fallback when mDNS is unavailable.
// If the device later sends a heartbeat with a device ID, the registry entry
// will be updated via the normal path.
func (m *Manager) RegisterManual(ipStr, name string) (string, error) {
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return "", fmt.Errorf("invalid IP address: %s", ipStr)
	}

	deviceID := name
	if deviceID == "" {
		deviceID = fmt.Sprintf("manual-%s", ipStr)
	}

	device := DiscoveredDevice{
		DeviceID:    deviceID,
		DeviceModel: "manual",
		IP:          ip,
		Port:        0, // Unknown until sidecar connects
		LastSeen:    time.Now(),
	}

	m.mu.Lock()
	m.discovered[deviceID] = device
	m.mu.Unlock()

	if m.callback != nil {
		m.log.Info("manual registration",
			"device_id", deviceID,
			"ip", ipStr)
		if err := m.callback(deviceID, "manual", ip, 0); err != nil {
			return deviceID, fmt.Errorf("callback: %w", err)
		}
	}

	return deviceID, nil
}

// List returns all currently known discovered devices.
func (m *Manager) List() []DiscoveredDevice {
	m.mu.Lock()
	defer m.mu.Unlock()

	devices := make([]DiscoveredDevice, 0, len(m.discovered))
	for _, d := range m.discovered {
		devices = append(devices, d)
	}
	return devices
}

// DefaultRegistrationCallback returns a RegistrationCallback that feeds into
// the given registry, registering devices in the unpaired state.
func DefaultRegistrationCallback(reg *registry.Registry) RegistrationCallback {
	return func(deviceID string, deviceModel string, ip net.IP, _ int) error {
		ipStr := ""
		if ip != nil {
			ipStr = ip.String()
		}
		// Register or update
		if err := reg.Register(deviceID, deviceModel, ipStr); err != nil {
			if !registry.IsAlreadyRegistered(err) {
				return fmt.Errorf("register: %w", err)
			}
			// Already registered — update IP
			if ip != nil {
				_ = reg.SetDeviceIP(deviceID, ipStr)
			}
		}
		// Always set device model (new or existing registration)
		if deviceModel != "" {
			_ = reg.SetDeviceModel(deviceID, deviceModel)
		}
		return nil
	}
}
