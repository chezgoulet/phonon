// Package log provides an event log for cluster events.
//
// Current implementation uses a JSON-lines file (newline-delimited JSON) for
// storage with an in-memory index for fast queries. This avoids CGo and large
// dependency builds in constrained environments.
//
// A SQLite backend (e.g. modernc.org/sqlite) can be swapped in by replacing
// the Storage interface — the EventLog API remains the same.
package log

import (
	"bufio"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// EventLog is a file-backed event log with thread-safe writes and queries.
type EventLog struct {
	path    string
	log     *slog.Logger
	mu      sync.RWMutex
	events  []Event
	nextID  atomic.Int64
	closed  bool
	file    *os.File
	writer  *bufio.Writer
}

// Opts controls EventLog behaviour.
type Opts struct {
	Path          string       // database file path (default "phonon.db")
	RetentionDays int          // auto-purge events older than this (default 90)
	Logger        *slog.Logger // if nil, slog.Default() is used
}

// Defaults
const (
	DefaultPath          = "phonon.db"
	DefaultRetentionDays = 90
)

// New opens or creates the event log file and loads existing events.
func New(opts Opts) (*EventLog, error) {
	path := opts.Path
	if path == "" {
		path = DefaultPath
	}

	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	logger = logger.With("component", "eventlog")

	// Ensure directory exists
	dir := filepath.Dir(path)
	if dir != "." {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return nil, fmt.Errorf("create event log dir: %w", err)
		}
	}

	// Open file for append (create if not exists)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_RDWR, 0644)
	if err != nil {
		return nil, fmt.Errorf("open event log: %w", err)
	}

	el := &EventLog{
		path:   path,
		log:    logger,
		file:   f,
		writer: bufio.NewWriter(f),
	}

	// Load existing events into memory
	if err := el.load(); err != nil {
		f.Close()
		return nil, fmt.Errorf("load events: %w", err)
	}

	logger.Info("event log opened", "path", path, "events", len(el.events))
	return el, nil
}

// lineEvent is the on-disk format for one event.
type lineEvent struct {
	ID        int64  `json:"id"`
	Timestamp string `json:"timestamp"`
	Type      string `json:"event_type"`
	DeviceID  string `json:"device_id,omitempty"`
	Severity  string `json:"severity"`
	Details   string `json:"details,omitempty"`
}

func (el *EventLog) load() error {
	scanner := bufio.NewScanner(el.file)
	scanner.Buffer(make([]byte, 0, 1024*1024), 1024*1024) // 1MB max line

	var maxID int64
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}

		var le lineEvent
		if err := json.Unmarshal(line, &le); err != nil {
			el.log.Warn("skipping malformed event line", "error", err)
			continue
		}

		e := Event{
			ID:       le.ID,
			Type:     EventType(le.Type),
			DeviceID: le.DeviceID,
			Severity: Severity(le.Severity),
			Details:  le.Details,
		}
		if t, err := time.Parse(time.RFC3339, le.Timestamp); err == nil {
			e.Timestamp = t
		}

		el.events = append(el.events, e)
		if le.ID > maxID {
			maxID = le.ID
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("scan events: %w", err)
	}

	el.nextID.Store(maxID + 1)
	return nil
}

// Write inserts a new event. Thread-safe.
func (el *EventLog) Write(eventType EventType, deviceID string, severity Severity, details string) error {
	el.mu.Lock()
	defer el.mu.Unlock()

	if el.closed {
		return fmt.Errorf("event log is closed")
	}

	id := el.nextID.Add(1) - 1
	now := time.Now().UTC()
	e := Event{
		ID:        id,
		Timestamp: now,
		Type:      eventType,
		DeviceID:  deviceID,
		Severity:  severity,
		Details:   details,
	}

	le := lineEvent{
		ID:        id,
		Timestamp: now.Format(time.RFC3339),
		Type:      string(eventType),
		DeviceID:  deviceID,
		Severity:  string(severity),
		Details:   details,
	}

	data, err := json.Marshal(le)
	if err != nil {
		return fmt.Errorf("marshal event: %w", err)
	}

	if _, err := el.writer.Write(data); err != nil {
		return fmt.Errorf("write event: %w", err)
	}
	if err := el.writer.WriteByte('\n'); err != nil {
		return fmt.Errorf("write newline: %w", err)
	}
	if err := el.writer.Flush(); err != nil {
		return fmt.Errorf("flush event: %w", err)
	}

	el.events = append(el.events, e)
	return nil
}

// Query returns events matching the given parameters, ordered by ID (insertion
// order) descending (newest first). Thread-safe.
func (el *EventLog) Query(q Query) ([]Event, error) {
	el.mu.RLock()
	defer el.mu.RUnlock()

	if el.closed {
		return nil, fmt.Errorf("event log is closed")
	}

	limit := q.Limit
	if limit <= 0 {
		limit = 100
	}
	if limit > 1000 {
		limit = 1000
	}

	// Collect matching events
	var matched []Event
	for i := len(el.events) - 1; i >= 0; i-- {
		e := el.events[i]

		if q.Since != nil && e.Timestamp.Before(*q.Since) {
			continue
		}
		if q.EventType != "" && e.Type != q.EventType {
			continue
		}
		if q.DeviceID != "" && e.DeviceID != q.DeviceID {
			continue
		}
		matched = append(matched, e)
	}

	// Apply offset + limit
	total := len(matched)
	start := q.Offset
	if start >= total {
		return nil, nil
	}
	end := start + limit
	if end > total {
		end = total
	}

	return matched[start:end], nil
}

// Count returns total number of events (optionally filtered). Thread-safe.
func (el *EventLog) Count(eventType EventType, deviceID string) (int, error) {
	el.mu.RLock()
	defer el.mu.RUnlock()

	if el.closed {
		return 0, fmt.Errorf("event log is closed")
	}

	if eventType == "" && deviceID == "" {
		return len(el.events), nil
	}

	var count int
	for _, e := range el.events {
		if eventType != "" && e.Type != eventType {
			continue
		}
		if deviceID != "" && e.DeviceID != deviceID {
			continue
		}
		count++
	}
	return count, nil
}

// PurgeOlderThan deletes events older than the given duration and returns the
// number of rows removed.
func (el *EventLog) PurgeOlderThan(d time.Duration) (int64, error) {
	el.mu.Lock()
	defer el.mu.Unlock()

	if el.closed {
		return 0, fmt.Errorf("event log is closed")
	}

	cutoff := time.Now().UTC().Add(-d)

	var kept []Event
	for _, e := range el.events {
		if e.Timestamp.After(cutoff) || e.Timestamp.Equal(cutoff) {
			kept = append(kept, e)
		}
	}

	removed := int64(len(el.events) - len(kept))
	if removed == 0 {
		return 0, nil
	}

	// Rewrite the file
	if err := el.rewriteFile(kept); err != nil {
		return 0, err
	}

	el.events = kept
	el.log.Info("purged old events", "count", removed, "cutoff", cutoff.Format(time.RFC3339))
	return removed, nil
}

func (el *EventLog) rewriteFile(events []Event) error {
	// Close old file
	el.file.Close()

	// Write to temp file, then rename
	tmpPath := el.path + ".tmp"
	f, err := os.Create(tmpPath)
	if err != nil {
		return fmt.Errorf("create temp file: %w", err)
	}

	w := bufio.NewWriter(f)
	for _, e := range events {
		le := lineEvent{
			ID:        e.ID,
			Timestamp: e.Timestamp.Format(time.RFC3339),
			Type:      string(e.Type),
			DeviceID:  e.DeviceID,
			Severity:  string(e.Severity),
			Details:   e.Details,
		}
		data, err := json.Marshal(le)
		if err != nil {
			f.Close()
			os.Remove(tmpPath)
			return fmt.Errorf("marshal event: %w", err)
		}
		if _, err := w.Write(data); err != nil {
			f.Close()
			os.Remove(tmpPath)
			return err
		}
		if err := w.WriteByte('\n'); err != nil {
			f.Close()
			os.Remove(tmpPath)
			return err
		}
	}
	if err := w.Flush(); err != nil {
		f.Close()
		os.Remove(tmpPath)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmpPath)
		return err
	}

	if err := os.Rename(tmpPath, el.path); err != nil {
		os.Remove(tmpPath)
		return fmt.Errorf("rename temp file: %w", err)
	}

	// Reopen the file
	f, err = os.OpenFile(el.path, os.O_APPEND|os.O_CREATE|os.O_RDWR, 0644)
	if err != nil {
		return fmt.Errorf("reopen event log: %w", err)
	}
	el.file = f
	el.writer = bufio.NewWriter(f)
	return nil
}

// StartRetentionLoop starts a background goroutine that purges old events
// periodically. The loop stops when ctx is cancelled.
func (el *EventLog) StartRetentionLoop(ctx interface{ Done() <-chan struct{} }, retentionDays int, interval time.Duration) {
	if retentionDays <= 0 {
		retentionDays = DefaultRetentionDays
	}
	if interval <= 0 {
		interval = 24 * time.Hour
	}

	go func() {
		// Run once immediately
		el.PurgeOlderThan(time.Duration(retentionDays) * 24 * time.Hour)

		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				el.PurgeOlderThan(time.Duration(retentionDays) * 24 * time.Hour)
			case <-ctx.Done():
				return
			}
		}
	}()
}

// Close shuts down the event log.
func (el *EventLog) Close() error {
	el.mu.Lock()
	defer el.mu.Unlock()

	if el.closed {
		return nil
	}
	el.closed = true

	if err := el.writer.Flush(); err != nil {
		return err
	}
	return el.file.Close()
}

// compile-time check
var _ = (sort.Interface)(nil)
