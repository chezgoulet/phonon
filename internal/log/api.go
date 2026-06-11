package log

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"time"
)

// APIHandler exposes event log queries via HTTP.
type APIHandler struct {
	eventLog *EventLog
	log      *slog.Logger
}

// NewAPIHandler creates an HTTP handler for event log queries.
func NewAPIHandler(el *EventLog) *APIHandler {
	return &APIHandler{
		eventLog: el,
		log:      slog.With("component", "event-api"),
	}
}

// RegisterRoutes adds event query endpoints to the given mux.
func (h *APIHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/events", h.handleQueryEvents)
}

// EventResponse is the JSON shape of one event for the API.
type EventResponse struct {
	ID        int64  `json:"id"`
	Timestamp string `json:"timestamp"`
	Type      string `json:"event_type"`
	DeviceID  string `json:"device_id,omitempty"`
	Severity  string `json:"severity"`
	Details   string `json:"details,omitempty"`
}

func (h *APIHandler) handleQueryEvents(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()

	// Parse optional time range
	var since *time.Time
	if s := q.Get("since"); s != "" {
		t, err := time.Parse(time.RFC3339, s)
		if err == nil {
			since = &t
		}
	}
	var until *time.Time
	if u := q.Get("until"); u != "" {
		t, err := time.Parse(time.RFC3339, u)
		if err == nil {
			until = &t
		}
	}

	// Parse pagination
	limit := 100
	if l := q.Get("limit"); l != "" {
		if v, err := strconv.Atoi(l); err == nil && v > 0 && v <= 1000 {
			limit = v
		}
	}
	offset := 0
	if o := q.Get("offset"); o != "" {
		if v, err := strconv.Atoi(o); err == nil && v >= 0 {
			offset = v
		}
	}

	eventType := EventType(q.Get("event_type"))
	deviceID := q.Get("device_id")

	// Build query
	evq := Query{
		EventType: eventType,
		DeviceID:  deviceID,
		Limit:     limit,
		Offset:    offset,
	}
	if since != nil {
		evq.Since = since
	}

	events, err := h.eventLog.Query(evq)
	if err != nil {
		h.log.Error("event query failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "query failed")
		return
	}

	// Apply until filter client-side (Query doesn't support Until natively)
	if until != nil {
		filtered := make([]Event, 0, len(events))
		for _, e := range events {
			if !e.Timestamp.After(*until) {
				filtered = append(filtered, e)
			}
		}
		events = filtered
	}

	// Map to response
	items := make([]EventResponse, 0, len(events))
	for _, e := range events {
		item := EventResponse{
			ID:        e.ID,
			Timestamp: e.Timestamp.Format(time.RFC3339),
			Type:      string(e.Type),
			DeviceID:  e.DeviceID,
			Severity:  string(e.Severity),
			Details:   e.Details,
		}

		items = append(items, item)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"object": "list",
		"data":   items,
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeJSONError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
