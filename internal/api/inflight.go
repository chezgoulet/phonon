package api

import "sync"

// inflightTracker counts the requests the coordinator has dispatched to each
// device but not yet received a final response for. This is the coordinator's
// own observation of per-node concurrency — unlike the phone-reported queue
// depth in HealthTelemetry, which the sidecar currently hardcodes to 0 and
// which lags a heartbeat behind reality regardless.
//
// It is the routing signal for load balancing and backpressure: because the
// coordinator dispatches every request, it always knows the true in-flight
// count, with no telemetry lag and no trust in the phone.
type inflightTracker struct {
	mu     sync.Mutex
	counts map[string]int
}

func newInflightTracker() *inflightTracker {
	return &inflightTracker{counts: make(map[string]int)}
}

// acquire increments the in-flight count for a device and returns a release
// function to call when the request completes. The release is idempotent, so
// calling it more than once (e.g. defer plus an explicit call) is safe.
func (t *inflightTracker) acquire(deviceID string) func() {
	t.mu.Lock()
	t.counts[deviceID]++
	t.mu.Unlock()

	var once sync.Once
	return func() {
		once.Do(func() {
			t.mu.Lock()
			if t.counts[deviceID] > 0 {
				t.counts[deviceID]--
			}
			if t.counts[deviceID] == 0 {
				delete(t.counts, deviceID)
			}
			t.mu.Unlock()
		})
	}
}

// depth returns the current in-flight request count for a device.
func (t *inflightTracker) depth(deviceID string) int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.counts[deviceID]
}

// snapshot returns a copy of the per-device in-flight counts.
func (t *inflightTracker) snapshot() map[string]int {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make(map[string]int, len(t.counts))
	for k, v := range t.counts {
		out[k] = v
	}
	return out
}
