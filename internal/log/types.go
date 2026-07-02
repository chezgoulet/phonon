package log

import "time"

// EventType enumerates the kinds of events that can be logged.
type EventType string

const (
	EventNodeJoined     EventType = "node_joined"
	EventNodeLeft       EventType = "node_left"
	EventNodeOnline     EventType = "node_online"
	EventNodeOffline    EventType = "node_offline"
	EventNodeOverheated EventType = "node_overheated"
	EventLowBattery     EventType = "low_battery"
	EventModelLoaded    EventType = "model_loaded"
	EventModelUnloaded  EventType = "model_unloaded"
	EventModelPushed    EventType = "model_pushed"
	EventPairing        EventType = "pairing"
	EventPairingDone    EventType = "pairing_complete"
	EventConfigChanged  EventType = "config_changed"
	EventNodeDraining   EventType = "node_draining"
	EventNodeDrained    EventType = "node_drained"
	EventNodeReEntered  EventType = "node_reentered"
	EventError          EventType = "error"
	EventInfo           EventType = "info"

	// Inference request lifecycle events (trace_id-correlated).
	EventInferenceStarted EventType = "inference_started"
	EventInferenceRouted  EventType = "inference_routed"
	EventInferenceResult  EventType = "inference_result"
	EventInferenceFailed  EventType = "inference_failed"
	EventInferenceRetried EventType = "inference_retried"
)

// Severity levels.
type Severity string

const (
	SeverityInfo    Severity = "info"
	SeverityWarning Severity = "warning"
	SeverityError   Severity = "error"
)

// Event represents a single event log entry.
type Event struct {
	ID        int64     `json:"id"`
	Timestamp time.Time `json:"timestamp"`
	Type      EventType `json:"event_type"`
	DeviceID  string    `json:"device_id,omitempty"`
	Severity  Severity  `json:"severity"`
	Details   string    `json:"details,omitempty"`

	// TraceID correlates inference-related events with a single HTTP
	// request (see api.TraceMiddleware). Empty for non-request events.
	TraceID string `json:"trace_id,omitempty"`
}

// Query represents parameters for querying events.
type Query struct {
	Since     *time.Time `json:"since,omitempty"`     // return events after this time
	EventType EventType  `json:"event_type,omitempty"` // filter by type
	DeviceID  string     `json:"device_id,omitempty"`  // filter by device
	Limit     int        `json:"limit,omitempty"`      // max results (default 100)
	Offset    int        `json:"offset,omitempty"`     // pagination offset
}
