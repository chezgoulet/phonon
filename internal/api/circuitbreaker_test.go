package api

import (
	"testing"
	"time"
)

// testClock is a manually-advanced clock for breaker tests.
type testClock struct{ t time.Time }

func (c *testClock) now() time.Time          { return c.t }
func (c *testClock) advance(d time.Duration) { c.t = c.t.Add(d) }

func newTestBreaker() (*DeviceCircuitBreaker, *testClock) {
	clk := &testClock{t: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}
	return NewDeviceCircuitBreaker(WithBreakerClock(clk.now)), clk
}

func TestBreakerOpensAfterThreeFailuresInWindow(t *testing.T) {
	b, clk := newTestBreaker()

	b.RecordFailure("dev1")
	clk.advance(1 * time.Second)
	b.RecordFailure("dev1")
	if b.State("dev1") != BreakerClosed {
		t.Fatalf("breaker should stay closed at 2 failures, got %s", b.State("dev1"))
	}
	clk.advance(1 * time.Second)
	b.RecordFailure("dev1")

	if got := b.State("dev1"); got != BreakerOpen {
		t.Fatalf("breaker should open after 3 failures in 5s, got %s", got)
	}
	if b.Allow("dev1") {
		t.Fatal("open breaker must not allow routing")
	}
}

func TestBreakerSlidingWindowExpiresOldFailures(t *testing.T) {
	b, clk := newTestBreaker()

	b.RecordFailure("dev1")
	clk.advance(6 * time.Second) // first failure now outside the 5s window
	b.RecordFailure("dev1")
	b.RecordFailure("dev1")

	if got := b.State("dev1"); got != BreakerClosed {
		t.Fatalf("failures outside window should not count, got %s", got)
	}
}

func TestBreakerHalfOpenProbeSuccessCloses(t *testing.T) {
	b, clk := newTestBreaker()
	for i := 0; i < 3; i++ {
		b.RecordFailure("dev1")
	}
	if b.State("dev1") != BreakerOpen {
		t.Fatal("expected open")
	}

	clk.advance(30 * time.Second)
	if got := b.State("dev1"); got != BreakerHalfOpen {
		t.Fatalf("expected half-open after cooldown, got %s", got)
	}

	// Exactly one probe admitted.
	if !b.Allow("dev1") {
		t.Fatal("half-open should admit one probe")
	}
	if b.Allow("dev1") {
		t.Fatal("second concurrent probe must be rejected")
	}

	b.RecordSuccess("dev1")
	if got := b.State("dev1"); got != BreakerClosed {
		t.Fatalf("successful probe should close breaker, got %s", got)
	}
	if !b.Allow("dev1") {
		t.Fatal("closed breaker should allow requests")
	}
}

func TestBreakerHalfOpenProbeFailureReopens(t *testing.T) {
	b, clk := newTestBreaker()
	for i := 0; i < 3; i++ {
		b.RecordFailure("dev1")
	}
	clk.advance(30 * time.Second)
	if !b.Allow("dev1") {
		t.Fatal("half-open should admit one probe")
	}
	b.RecordFailure("dev1")

	if got := b.State("dev1"); got != BreakerOpen {
		t.Fatalf("failed probe should re-open breaker, got %s", got)
	}

	// Full cooldown restarts after the failed probe.
	clk.advance(29 * time.Second)
	if b.Allow("dev1") {
		t.Fatal("breaker should stay open through the fresh 30s cooldown")
	}
	clk.advance(1 * time.Second)
	if !b.Allow("dev1") {
		t.Fatal("breaker should be probeable after the fresh cooldown")
	}
}

func TestBreakerSuccessInClosedStateDoesNotPanic(t *testing.T) {
	b, _ := newTestBreaker()
	b.RecordSuccess("unknown")
	if b.State("unknown") != BreakerClosed {
		t.Fatal("unknown device should be closed")
	}
}

func TestBreakerSnapshot(t *testing.T) {
	b, clk := newTestBreaker()
	for i := 0; i < 3; i++ {
		b.RecordFailure("bad")
	}
	b.RecordFailure("flaky")

	snap := b.Snapshot()
	if snap["bad"] != BreakerOpen {
		t.Fatalf("expected bad=open, got %s", snap["bad"])
	}
	if snap["flaky"] != BreakerClosed {
		t.Fatalf("expected flaky=closed, got %s", snap["flaky"])
	}

	clk.advance(30 * time.Second)
	if b.Snapshot()["bad"] != BreakerHalfOpen {
		t.Fatal("snapshot should surface half-open after cooldown")
	}
}

func TestBreakerDevicesAreIndependent(t *testing.T) {
	b, _ := newTestBreaker()
	for i := 0; i < 3; i++ {
		b.RecordFailure("dev1")
	}
	if !b.Allow("dev2") {
		t.Fatal("dev2 should be unaffected by dev1 failures")
	}
}
