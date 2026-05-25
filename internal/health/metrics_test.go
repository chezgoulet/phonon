package health

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMetrics_ScrapeContent(t *testing.T) {
	metrics := NewMetrics()
	metrics.Register()

	// Set some values so they appear in output
	metrics.NodesOnline.WithLabelValues("fast-general").Add(3)
	metrics.NodesOffline.WithLabelValues("fast-general").Add(1)
	metrics.NodesOverheating.Set(2)
	metrics.BatteryLevel.WithLabelValues("device-1").Set(85)
	metrics.ThermalTempC.WithLabelValues("device-1").Set(38)
	metrics.QueueDepth.WithLabelValues("device-1").Set(2)
	metrics.RequestsTotal.WithLabelValues("fast-general", "success").Add(42)
	metrics.RequestDuration.WithLabelValues("fast-general").Observe(150)

	mux := http.NewServeMux()
	mux.Handle("GET /metrics", metrics.Handler())

	req := httptest.NewRequest("GET", "/metrics", nil)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	body := w.Body.String()

	expected := []string{
		"phonon_nodes_online",
		"phonon_nodes_offline",
		"phonon_nodes_overheating",
		"phonon_requests_total",
		"phonon_request_duration_ms",
		"phonon_queue_depth",
		"phonon_battery_level",
		"phonon_thermal_temp_c",
	}

	for _, name := range expected {
		if !strContains(body, name) {
			t.Errorf("expected metric %q in output", name)
		}
	}

	// Verify values
	if !strContains(body, "phonon_nodes_online{group=\"fast-general\"} 3") {
		t.Errorf("expected nodes_online=3")
	}
	if !strContains(body, "phonon_nodes_overheating 2") {
		t.Errorf("expected nodes_overheating=2")
	}
	if !strContains(body, "phonon_battery_level{device_id=\"device-1\"} 85") {
		t.Errorf("expected battery_level=85")
	}
	if !strContains(body, "phonon_thermal_temp_c{device_id=\"device-1\"} 38") {
		t.Errorf("expected thermal_temp_c=38")
	}
}

func TestMetrics_RegisterIdempotent(_ *testing.T) {
	metrics := NewMetrics()
	metrics.Register()
	// Second call should be a no-op (not panic)
	metrics.Register()
}

func TestMetrics_GaugesDefaultToZero(t *testing.T) {
	metrics := NewMetrics()
	metrics.Register()

	mux := http.NewServeMux()
	mux.Handle("GET /metrics", metrics.Handler())

	req := httptest.NewRequest("GET", "/metrics", nil)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	body := w.Body.String()

	// Gauge without labels always appears with default 0
	if !strContains(body, "phonon_nodes_overheating 0") {
		t.Errorf("expected nodes_overheating to be present with value 0")
	}
}

func TestMetrics_NotRegisteredHandler(t *testing.T) {
	metrics := NewMetrics()
	// Don't register — handler should return fallback
	mux := http.NewServeMux()
	mux.Handle("GET /metrics", metrics.Handler())

	req := httptest.NewRequest("GET", "/metrics", nil)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
}

func strContains(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
