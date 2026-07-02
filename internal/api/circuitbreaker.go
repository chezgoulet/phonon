package api

import (
	"sync"
	"time"
)

// BreakerState is the state of a device's circuit breaker.
type BreakerState string

const (
	// BreakerClosed — device is healthy, requests flow normally.
	BreakerClosed BreakerState = "closed"
	// BreakerOpen — device recently failed repeatedly; no routing.
	BreakerOpen BreakerState = "open"
	// BreakerHalfOpen — cooldown elapsed; a single probe request is allowed.
	BreakerHalfOpen BreakerState = "half-open"
)

// CircuitBreaker tracks per-device inference failures and gates routing.
//
// Semantics (defaults):
//   - 3 failures within a 5s sliding window open the breaker.
//   - While open, Allow returns false for 30s.
//   - After 30s the breaker becomes half-open: exactly one probe request is
//     admitted. If the probe fails, the breaker re-opens for another 30s.
//     If it succeeds, the breaker closes.
//
// The interface exists so tests (and alternative policies) can substitute
// implementations.
type CircuitBreaker interface {
	// Allow reports whether a request may be routed to the device. In the
	// half-open state, Allow claims the single probe slot as a side effect;
	// call it only when the request will actually be sent.
	Allow(deviceID string) bool

	// RecordFailure records a failed inference request for the device.
	RecordFailure(deviceID string)

	// RecordSuccess records a successful inference request for the device.
	RecordSuccess(deviceID string)

	// State returns the device's current breaker state without side effects.
	State(deviceID string) BreakerState

	// Snapshot returns the current state of every tracked device. Used by
	// the health monitor loop to sync breaker state into node telemetry.
	Snapshot() map[string]BreakerState
}

// DeviceCircuitBreaker is the default CircuitBreaker implementation.
type DeviceCircuitBreaker struct {
	failureThreshold int
	failureWindow    time.Duration
	cooldown         time.Duration
	now              func() time.Time // injectable clock for tests

	mu      sync.Mutex
	devices map[string]*breakerEntry
}

type breakerEntry struct {
	failures []time.Time // failure timestamps within the sliding window
	openedAt time.Time   // when the breaker last opened
	open     bool        // true while open or half-open (cooldown-derived)
	probing  bool        // half-open probe currently in flight
}

// BreakerOption configures a DeviceCircuitBreaker.
type BreakerOption func(*DeviceCircuitBreaker)

// WithBreakerClock injects a clock (for tests).
func WithBreakerClock(now func() time.Time) BreakerOption {
	return func(b *DeviceCircuitBreaker) { b.now = now }
}

// WithBreakerPolicy overrides the failure threshold, window, and cooldown.
func WithBreakerPolicy(threshold int, window, cooldown time.Duration) BreakerOption {
	return func(b *DeviceCircuitBreaker) {
		b.failureThreshold = threshold
		b.failureWindow = window
		b.cooldown = cooldown
	}
}

// NewDeviceCircuitBreaker creates a breaker with the default policy:
// 3 failures / 5s window / 30s cooldown.
func NewDeviceCircuitBreaker(opts ...BreakerOption) *DeviceCircuitBreaker {
	b := &DeviceCircuitBreaker{
		failureThreshold: 3,
		failureWindow:    5 * time.Second,
		cooldown:         30 * time.Second,
		now:              time.Now,
		devices:          make(map[string]*breakerEntry),
	}
	for _, opt := range opts {
		opt(b)
	}
	return b
}

func (b *DeviceCircuitBreaker) entry(deviceID string) *breakerEntry {
	e, ok := b.devices[deviceID]
	if !ok {
		e = &breakerEntry{}
		b.devices[deviceID] = e
	}
	return e
}

// stateLocked computes the state of an entry at time t. Callers hold b.mu.
func (b *DeviceCircuitBreaker) stateLocked(e *breakerEntry, t time.Time) BreakerState {
	if !e.open {
		return BreakerClosed
	}
	if t.Sub(e.openedAt) >= b.cooldown {
		return BreakerHalfOpen
	}
	return BreakerOpen
}

// Allow implements CircuitBreaker.
func (b *DeviceCircuitBreaker) Allow(deviceID string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	e := b.entry(deviceID)
	switch b.stateLocked(e, b.now()) {
	case BreakerClosed:
		return true
	case BreakerOpen:
		return false
	default: // half-open — admit exactly one probe
		if e.probing {
			return false
		}
		e.probing = true
		return true
	}
}

// RecordFailure implements CircuitBreaker.
func (b *DeviceCircuitBreaker) RecordFailure(deviceID string) {
	b.mu.Lock()
	defer b.mu.Unlock()

	t := b.now()
	e := b.entry(deviceID)

	switch b.stateLocked(e, t) {
	case BreakerHalfOpen:
		// Probe failed — re-open for a full cooldown.
		e.open = true
		e.openedAt = t
		e.probing = false
		e.failures = nil
	case BreakerOpen:
		// Already open; nothing to do.
	default: // closed
		e.failures = append(e.failures, t)
		e.failures = pruneWindow(e.failures, t.Add(-b.failureWindow))
		if len(e.failures) >= b.failureThreshold {
			e.open = true
			e.openedAt = t
			e.failures = nil
		}
	}
}

// RecordSuccess implements CircuitBreaker.
func (b *DeviceCircuitBreaker) RecordSuccess(deviceID string) {
	b.mu.Lock()
	defer b.mu.Unlock()

	e := b.entry(deviceID)
	if b.stateLocked(e, b.now()) == BreakerHalfOpen {
		// Probe succeeded — close the breaker.
		e.open = false
		e.probing = false
		e.failures = nil
	}
}

// State implements CircuitBreaker.
func (b *DeviceCircuitBreaker) State(deviceID string) BreakerState {
	b.mu.Lock()
	defer b.mu.Unlock()
	e, ok := b.devices[deviceID]
	if !ok {
		return BreakerClosed
	}
	return b.stateLocked(e, b.now())
}

// Snapshot implements CircuitBreaker.
func (b *DeviceCircuitBreaker) Snapshot() map[string]BreakerState {
	b.mu.Lock()
	defer b.mu.Unlock()
	t := b.now()
	out := make(map[string]BreakerState, len(b.devices))
	for id, e := range b.devices {
		out[id] = b.stateLocked(e, t)
	}
	return out
}

// pruneWindow drops timestamps at or before the cutoff (in place).
func pruneWindow(ts []time.Time, cutoff time.Time) []time.Time {
	kept := ts[:0]
	for _, t := range ts {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	return kept
}
