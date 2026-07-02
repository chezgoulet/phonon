package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/chezgoulet/phonon/internal/health"
	phononlog "github.com/chezgoulet/phonon/internal/log"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestTraceMiddlewareAssignsIDAndHeader(t *testing.T) {
	var ctxID string
	inner := http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		ctxID = TraceIDFromContext(r.Context())
	})
	h := TraceMiddleware(inner)

	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/health", http.NoBody))

	headerID := w.Header().Get(TraceIDHeader)
	if !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(headerID) {
		t.Fatalf("trace ID should be 32 hex chars, got %q", headerID)
	}
	if ctxID != headerID {
		t.Errorf("context trace ID %q != header %q", ctxID, headerID)
	}
}

func TestTraceMiddlewareIgnoresInboundHeader(t *testing.T) {
	h := TraceMiddleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set(TraceIDHeader, "attacker-chosen\nvalue")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if got := w.Header().Get(TraceIDHeader); strings.Contains(got, "attacker") {
		t.Errorf("inbound trace IDs must not be echoed, got %q", got)
	}
}

func TestTraceIDsAreUnique(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 100; i++ {
		id := NewTraceID()
		if seen[id] {
			t.Fatalf("duplicate trace ID %s", id)
		}
		seen[id] = true
	}
}

// observedHandler builds a handler with a real event log and metrics.
func observedHandler(t *testing.T) (*OpenAIHandler, *phononlog.EventLog, *health.Metrics) {
	t.Helper()
	el, err := phononlog.New(phononlog.Opts{Path: filepath.Join(t.TempDir(), "events.db")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { el.Close() })

	m := health.NewMetrics()
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg, WithEventLog(el), WithMetrics(m))
	h.AddModel("test-model", "test")
	return h, el, m
}

func eventsOfType(t *testing.T, el *phononlog.EventLog, typ phononlog.EventType) []phononlog.Event {
	t.Helper()
	evs, err := el.Query(phononlog.Query{EventType: typ})
	if err != nil {
		t.Fatal(err)
	}
	return evs
}

func TestInferenceLifecycleEventsOnRetry(t *testing.T) {
	h, el, m := observedHandler(t)

	h.inferenceProxy = func(phoneURL string, req PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		if req.TraceID == "" {
			t.Error("trace ID must be propagated to the phone request")
		}
		if strings.Contains(phoneURL, "10.0.0.5") {
			return nil, fmt.Errorf("connection refused")
		}
		return &PhoneInferenceResponse{Text: "ok", Tokens: 5, Duration: 100}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
	// Simulate the trace middleware.
	traceID := NewTraceID()
	req = req.WithContext(ContextWithTraceID(req.Context(), traceID))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	// One event per lifecycle stage, all carrying the trace ID.
	expect := map[phononlog.EventType]int{
		phononlog.EventInferenceStarted: 1,
		phononlog.EventInferenceRouted:  2, // original + fallback
		phononlog.EventInferenceFailed:  1,
		phononlog.EventInferenceRetried: 1,
		phononlog.EventInferenceResult:  1,
	}
	for typ, n := range expect {
		evs := eventsOfType(t, el, typ)
		if len(evs) != n {
			t.Errorf("%s: expected %d events, got %d", typ, n, len(evs))
			continue
		}
		for _, e := range evs {
			if e.TraceID != traceID {
				t.Errorf("%s: trace_id = %q, want %q", typ, e.TraceID, traceID)
			}
		}
	}

	// The retried event names both devices.
	retried := eventsOfType(t, el, phononlog.EventInferenceRetried)[0]
	var fields map[string]any
	if err := json.Unmarshal([]byte(retried.Details), &fields); err != nil {
		t.Fatalf("retried details not JSON: %v", err)
	}
	if fields["from_device_id"] != "phone-01" || fields["to_device_id"] != "phone-02" {
		t.Errorf("retried fields = %v", fields)
	}

	// The result event carries token count and duration.
	result := eventsOfType(t, el, phononlog.EventInferenceResult)[0]
	if !strings.Contains(result.Details, "completion_tokens") || !strings.Contains(result.Details, "duration_ms") {
		t.Errorf("result details missing fields: %s", result.Details)
	}
	if result.DeviceID != "phone-02" {
		t.Errorf("result device = %q", result.DeviceID)
	}

	// Metrics: one retry, one connection error, one duration sample.
	if got := testutil.ToFloat64(m.InferenceRetries); got != 1 {
		t.Errorf("phonon_inference_retries_total = %v, want 1", got)
	}
	if got := testutil.ToFloat64(m.InferenceErrors.WithLabelValues("connection")); got != 1 {
		t.Errorf("phonon_inference_errors_total{connection} = %v, want 1", got)
	}
	if got := testutil.CollectAndCount(m.InferenceDuration); got != 1 {
		t.Errorf("expected the duration histogram to be collectable, got %d", got)
	}
	if got := testutil.ToFloat64(m.RequestsActive); got != 0 {
		t.Errorf("phonon_requests_active should return to 0, got %v", got)
	}
}

func TestTimeoutErrorClassifiedInMetrics(t *testing.T) {
	h, el, m := observedHandler(t)
	h.inferenceProxy = func(string, PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		return nil, fmt.Errorf("phone request failed: %w", errTimeoutForTest{})
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if got := testutil.ToFloat64(m.InferenceErrors.WithLabelValues("timeout")); got != 2 {
		t.Errorf("timeout errors = %v, want 2 (both attempts)", got)
	}
	if n := len(eventsOfType(t, el, phononlog.EventInferenceFailed)); n != 2 {
		t.Errorf("expected 2 inference_failed events, got %d", n)
	}
}

// errTimeoutForTest implements net.Error with Timeout() == true.
type errTimeoutForTest struct{}

func (errTimeoutForTest) Error() string   { return "i/o timeout" }
func (errTimeoutForTest) Timeout() bool   { return true }
func (errTimeoutForTest) Temporary() bool { return true }

func TestEventLogPersistsTraceID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "events.db")
	el, err := phononlog.New(phononlog.Opts{Path: path})
	if err != nil {
		t.Fatal(err)
	}
	if err := el.WriteEvent(phononlog.Event{
		Type:     phononlog.EventInferenceStarted,
		Severity: phononlog.SeverityInfo,
		TraceID:  "cafebabe",
		Details:  `{"model":"m"}`,
	}); err != nil {
		t.Fatal(err)
	}
	el.Close()

	// Reopen and confirm the trace ID survived the round trip.
	el2, err := phononlog.New(phononlog.Opts{Path: path})
	if err != nil {
		t.Fatal(err)
	}
	defer el2.Close()
	evs, err := el2.Query(phononlog.Query{EventType: phononlog.EventInferenceStarted})
	if err != nil {
		t.Fatal(err)
	}
	if len(evs) != 1 || evs[0].TraceID != "cafebabe" {
		t.Fatalf("trace ID did not survive reload: %+v", evs)
	}
}
