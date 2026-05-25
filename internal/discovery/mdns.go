package discovery

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/hashicorp/mdns"
)

// ServiceType is the mDNS service type for Phonon sidecars.
const ServiceType = "_phonon._tcp"

// DiscoveredDevice represents a sidecar found via mDNS or manual registration.
type DiscoveredDevice struct {
	DeviceID    string // Unique device identifier
	DeviceModel string // e.g. "Pixel 9 Pro"
	IP          net.IP
	Port        int
	LastSeen    time.Time
}

// TXT record keys expected in the mDNS advertisement.
const (
	txtKeyDeviceID    = "device_id"
	txtKeyDeviceModel = "device_model"
)

// Discoverer defines the interface for discovering Phonon sidecars.
// Implementations can use mDNS or other protocols.
type Discoverer interface {
	// Start begins discovery. Discovered devices are sent to the channel.
	// The channel must be consumed promptly to avoid blocking.
	Start(ctx context.Context, ch chan<- DiscoveredDevice) error
	// Stop terminates discovery.
	Stop() error
}

// MDNSDiscoverer implements Discoverer using mDNS.
type MDNSDiscoverer struct {
	log     *slog.Logger
	entries chan *mdns.ServiceEntry
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	mu      sync.Mutex
	running bool
}

// NewMDNSDiscoverer creates an mDNS-based discoverer.
func NewMDNSDiscoverer() *MDNSDiscoverer {
	return &MDNSDiscoverer{
		log: slog.With("component", "mdns-discovery"),
	}
}

// Start begins mDNS discovery for _phonon._tcp services.
// Devices are sent to ch as they are discovered.
func (d *MDNSDiscoverer) Start(ctx context.Context, ch chan<- DiscoveredDevice) error {
	d.mu.Lock()
	defer d.mu.Unlock()

	if d.running {
		return fmt.Errorf("mDNS discovery already running")
	}

	ctx, cancel := context.WithCancel(ctx)
	d.cancel = cancel
	d.running = true
	d.entries = make(chan *mdns.ServiceEntry, 10)

	d.wg.Add(1)
	go d.queryLoop(ctx, ch)

	return nil
}

// Stop terminates the mDNS discovery loop.
func (d *MDNSDiscoverer) Stop() error {
	d.mu.Lock()
	defer d.mu.Unlock()

	if !d.running {
		return nil
	}

	d.cancel()
	d.wg.Wait()
	d.running = false
	return nil
}

// queryLoop runs the mDNS query periodically to pick up new/updated services.
func (d *MDNSDiscoverer) queryLoop(ctx context.Context, ch chan<- DiscoveredDevice) {
	defer d.wg.Done()

	// Run an immediate query on start
	d.runQuery(ctx, ch)

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			d.runQuery(ctx, ch)
		case <-ctx.Done():
			return
		}
	}
}

// runQuery performs a single mDNS query for _phonon._tcp.
func (d *MDNSDiscoverer) runQuery(ctx context.Context, ch chan<- DiscoveredDevice) {
	// Use the hashicorp/mdns query which handles the DNS-SD protocol
	entriesCh := make(chan *mdns.ServiceEntry, 10)

	go func() {
		for entry := range entriesCh {
			device := parseEntry(entry)
			if device != nil {
				select {
				case ch <- *device:
				default:
					d.log.Warn("discarding discovered device (channel full)", "device_id", device.DeviceID)
				}
			}
		}
	}()

	if err := mdns.Query(&mdns.QueryParam{
		Service:   ServiceType,
		Domain:    "local",
		Timeout:   5 * time.Second,
		Entries:   entriesCh,
	}); err != nil {
		d.log.Warn("mDNS query failed", "error", err)
	}
}

// parseEntry converts an mDNS service entry to a DiscoveredDevice.
// Returns nil if the entry lacks required fields.
func parseEntry(entry *mdns.ServiceEntry) *DiscoveredDevice {
	if entry == nil {
		return nil
	}

	device := &DiscoveredDevice{
		IP:       entry.AddrV4,
		Port:     entry.Port,
		LastSeen: time.Now(),
	}

	// Fall back to IPv6 if no IPv4
	if device.IP == nil {
		device.IP = entry.AddrV6
	}

	// Parse TXT records for device metadata
	for _, txt := range entry.InfoFields {
		if txt == "" {
			continue
		}
		kv := parseTXTField(txt)
		switch kv.key {
		case txtKeyDeviceID:
			device.DeviceID = kv.value
		case txtKeyDeviceModel:
			device.DeviceModel = kv.value
		}
	}

	if device.DeviceID == "" && entry.Host != "" {
		// Fallback: use hostname minus domain as device ID
		device.DeviceID = sanitizeHostname(entry.Host)
	}

	return device
}

type txtKV struct {
	key   string
	value string
}

func parseTXTField(field string) txtKV {
	for i := 0; i < len(field); i++ {
		if field[i] == '=' {
			return txtKV{key: field[:i], value: field[i+1:]}
		}
	}
	return txtKV{key: field, value: ""}
}

func sanitizeHostname(host string) string {
	// Strip .local. suffix and trim
	for i := 0; i < len(host); i++ {
		if host[i] == '.' {
			return host[:i]
		}
	}
	return host
}


