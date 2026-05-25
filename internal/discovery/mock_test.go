package discovery

import (
	"context"
	"sync"
)

// mockDiscoverer implements Discoverer for testing.
type mockDiscoverer struct {
	mu      sync.Mutex
	devices []DiscoveredDevice
	running bool
	started chan struct{}
}

func newMockDiscoverer(devices ...DiscoveredDevice) *mockDiscoverer {
	return &mockDiscoverer{
		devices: devices,
		started: make(chan struct{}, 1),
	}
}

func (m *mockDiscoverer) Start(ctx context.Context, ch chan<- DiscoveredDevice) error {
	m.mu.Lock()
	if m.running {
		m.mu.Unlock()
		return nil
	}
	m.running = true
	m.mu.Unlock()

	// Signal that the mock has started
	m.started <- struct{}{}

	// Run discovery in background so Start() returns immediately
	go m.run(ctx, ch)
	return nil
}

func (m *mockDiscoverer) run(ctx context.Context, ch chan<- DiscoveredDevice) {
	defer close(ch)

	// Send all devices immediately
	for _, d := range m.devices {
		select {
		case ch <- d:
		case <-ctx.Done():
			return
		}
	}

	// Keep running until ctx is cancelled (when Manager.Stop() is called)
	<-ctx.Done()
}

func (m *mockDiscoverer) Stop() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.running = false
	return nil
}
