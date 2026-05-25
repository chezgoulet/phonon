package log

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNewDefaultPath(t *testing.T) {
	// Use a temp dir so we don't pollute cwd
	orig, _ := os.Getwd()
	td := t.TempDir()
	os.Chdir(td)
	defer os.Chdir(orig)

	el, err := New(Opts{})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer el.Close()

	if el.path != DefaultPath {
		t.Errorf("expected default path %q, got %q", DefaultPath, el.path)
	}
}

func TestNewCustomPath(t *testing.T) {
	path := filepath.Join(t.TempDir(), "test-events.db")
	el, err := New(Opts{Path: path})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer el.Close()

	if _, err := os.Stat(path); os.IsNotExist(err) {
		t.Error("expected file to exist")
	}
}

func TestWriteAndCount(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	if err := el.Write(EventNodeJoined, "phone-01", SeverityInfo, "phone joined"); err != nil {
		t.Fatalf("Write: %v", err)
	}

	count, err := el.Count("", "")
	if err != nil {
		t.Fatalf("Count: %v", err)
	}
	if count != 1 {
		t.Errorf("expected 1 event, got %d", count)
	}
}

func TestWriteMultiple(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	for i := 0; i < 10; i++ {
		el.Write(EventInfo, "", SeverityInfo, "event")
	}

	count, _ := el.Count("", "")
	if count != 10 {
		t.Errorf("expected 10 events, got %d", count)
	}
}

func TestQueryAll(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	el.Write(EventNodeJoined, "phone-01", SeverityInfo, "joined")
	el.Write(EventNodeLeft, "phone-01", SeverityInfo, "left")

	events, err := el.Query(Query{Limit: 10})
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(events) != 2 {
		t.Errorf("expected 2 events, got %d", len(events))
	}

	// Newest first
	if events[0].Type != EventNodeLeft {
		t.Errorf("expected first event to be %s, got %s", EventNodeLeft, events[0].Type)
	}
}

func TestQueryFilterByType(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	el.Write(EventNodeJoined, "phone-01", SeverityInfo, "joined")
	el.Write(EventInfo, "", SeverityInfo, "info log")
	el.Write(EventError, "phone-01", SeverityError, "error!")

	events, err := el.Query(Query{EventType: EventError, Limit: 10})
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(events) != 1 {
		t.Errorf("expected 1 error event, got %d", len(events))
	}
}

func TestQueryFilterByDevice(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	el.Write(EventNodeJoined, "phone-01", SeverityInfo, "")
	el.Write(EventNodeJoined, "phone-02", SeverityInfo, "")
	el.Write(EventNodeLeft, "phone-01", SeverityInfo, "")

	events, err := el.Query(Query{DeviceID: "phone-01", Limit: 10})
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(events) != 2 {
		t.Errorf("expected 2 events for phone-01, got %d", len(events))
	}
}

func TestQueryLimit(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	for i := 0; i < 50; i++ {
		el.Write(EventInfo, "", SeverityInfo, "event")
	}

	events, err := el.Query(Query{Limit: 10})
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(events) > 10 {
		t.Errorf("expected at most 10 events, got %d", len(events))
	}
}

func TestQueryLimitDefault(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	for i := 0; i < 200; i++ {
		el.Write(EventInfo, "", SeverityInfo, "event")
	}

	// Limit=0 should default to 100
	events, err := el.Query(Query{})
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(events) > 100 {
		t.Errorf("expected default limit of 100, got %d", len(events))
	}
}

func TestQueryOffset(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	for i := 0; i < 10; i++ {
		el.Write(EventInfo, "", SeverityInfo, "event")
	}

	first, _ := el.Query(Query{Limit: 5, Offset: 0})
	second, _ := el.Query(Query{Limit: 5, Offset: 5})

	if len(first) != 5 || len(second) != 5 {
		t.Errorf("expected 5 each, got %d and %d", len(first), len(second))
	}

	// No overlap
	seen := make(map[int64]bool)
	for _, e := range first {
		seen[e.ID] = true
	}
	for _, e := range second {
		if seen[e.ID] {
			t.Errorf("duplicate event ID %d across pages", e.ID)
		}
	}
}

func TestQuerySince(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	el.Write(EventInfo, "", SeverityInfo, "before")
	time.Sleep(5 * time.Millisecond)
	since := time.Now()
	time.Sleep(5 * time.Millisecond)
	el.Write(EventInfo, "", SeverityInfo, "after")

	events, err := el.Query(Query{Since: &since, Limit: 10})
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(events) != 1 {
		t.Errorf("expected 1 event after timestamp, got %d", len(events))
	}
}

func TestCountFiltered(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	el.Write(EventNodeJoined, "phone-01", SeverityInfo, "")
	el.Write(EventNodeJoined, "phone-02", SeverityInfo, "")
	el.Write(EventError, "phone-01", SeverityError, "err")
	el.Write(EventInfo, "", SeverityInfo, "")

	count, _ := el.Count(EventNodeJoined, "")
	if count != 2 {
		t.Errorf("expected 2 node_joined events, got %d", count)
	}

	count, _ = el.Count("", "phone-01")
	if count != 2 {
		t.Errorf("expected 2 events for phone-01, got %d", count)
	}

	count, _ = el.Count(EventError, "phone-01")
	if count != 1 {
		t.Errorf("expected 1 error for phone-01, got %d", count)
	}
}

func TestPurgeOlderThan(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	el.Write(EventInfo, "", SeverityInfo, "recent")

	// Manually add an old event
	oldTime := time.Now().UTC().Add(-100 * 24 * time.Hour) // 100 days ago
	el.mu.Lock()
	el.events = append(el.events, Event{
		ID: el.nextID.Add(1) - 1, Timestamp: oldTime,
		Type: EventInfo, Severity: SeverityInfo,
	})
	el.mu.Unlock()

	n, err := el.PurgeOlderThan(90 * 24 * time.Hour)
	if err != nil {
		t.Fatalf("PurgeOlderThan: %v", err)
	}
	if n != 1 {
		t.Errorf("expected 1 purged, got %d", n)
	}

	count, _ := el.Count("", "")
	if count != 1 {
		t.Errorf("expected 1 remaining event, got %d", count)
	}
}

func TestPersistence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "persist.db")

	// Write some events
	el1, err := New(Opts{Path: path})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	el1.Write(EventNodeJoined, "phone-01", SeverityInfo, "first")
	el1.Write(EventError, "phone-01", SeverityError, "second")
	el1.Close()

	// Reopen and verify
	el2, err := New(Opts{Path: path})
	if err != nil {
		t.Fatalf("New reopen: %v", err)
	}
	defer el2.Close()

	count, _ := el2.Count("", "")
	if count != 2 {
		t.Errorf("expected 2 events after reopen, got %d", count)
	}
}

func TestConcurrentWrites(t *testing.T) {
	el := newTestLog(t)
	defer el.Close()

	done := make(chan struct{})
	const n = 50

	for i := 0; i < n; i++ {
		go func() {
			el.Write(EventInfo, "", SeverityInfo, "concurrent")
			done <- struct{}{}
		}()
	}

	for i := 0; i < n; i++ {
		<-done
	}

	count, _ := el.Count("", "")
	if count != n {
		t.Errorf("expected %d events, got %d", n, count)
	}
}

func TestClose(t *testing.T) {
	el := newTestLog(t)
	el.Write(EventInfo, "", SeverityInfo, "before close")

	if err := el.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	// Write after close should fail
	if err := el.Write(EventInfo, "", SeverityInfo, "after close"); err == nil {
		t.Error("expected error writing to closed log")
	}
}

func TestAutoExpiryDefault(t *testing.T) {
	// RetentionDays=0 should not panic and should use default
	path := filepath.Join(t.TempDir(), "auto.db")
	el, err := New(Opts{Path: path})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer el.Close()

	ctx, cancel := context.WithCancel(context.Background())
	el.StartRetentionLoop(ctx, 0, 24*time.Hour)
	time.Sleep(10 * time.Millisecond)
	cancel()
}

func TestEventTypes(t *testing.T) {
	if string(EventNodeJoined) != "node_joined" {
		t.Errorf("unexpected: %s", EventNodeJoined)
	}
	if string(EventError) != "error" {
		t.Errorf("unexpected: %s", EventError)
	}
	if string(SeverityWarning) != "warning" {
		t.Errorf("unexpected: %s", SeverityWarning)
	}
}

func newTestLog(t *testing.T) *EventLog {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.db")
	el, err := New(Opts{Path: path})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return el
}
