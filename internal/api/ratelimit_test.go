package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/chezgoulet/phonon/internal/registry"
)

func okHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
}

func TestRateLimiterAllowsWithinBurst(t *testing.T) {
	rl := NewRateLimiter(1, 5) // 1 rps, burst 5
	h := rl.Middleware(okHandler())

	for i := 0; i < 5; i++ {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/chat/completions", http.NoBody))
		if w.Code != http.StatusOK {
			t.Fatalf("request %d within burst should pass, got %d", i, w.Code)
		}
	}
}

func TestRateLimiterThrottlesNormalPriorityWith429(t *testing.T) {
	rl := NewRateLimiter(0.001, 1) // effectively no refill
	h := rl.Middleware(okHandler())

	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/chat/completions", http.NoBody))
	if w.Code != http.StatusOK {
		t.Fatalf("first request should pass, got %d", w.Code)
	}

	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/chat/completions", http.NoBody))
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("second request should be throttled, got %d", w.Code)
	}
	if w.Header().Get("Retry-After") == "" {
		t.Error("429 must carry a Retry-After header")
	}
}

func TestRateLimiterAdminSurvivesSaturation(t *testing.T) {
	rl := NewRateLimiter(0.001, 1)
	h := rl.Middleware(okHandler())

	// Exhaust the primary bucket with inference traffic.
	for i := 0; i < 3; i++ {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/chat/completions", http.NoBody))
	}

	// Admin request must still pass via the reserve bucket.
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/pair/confirm", http.NoBody))
	if w.Code != http.StatusOK {
		t.Fatalf("admin request should survive normal-traffic saturation, got %d", w.Code)
	}

	// Reserve (burst 1 at this size) is now spent too: genuinely
	// exhausted admin traffic gets 429.
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/cluster/health", http.NoBody))
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("admin request should be rejected once the reserve is exhausted, got %d", w.Code)
	}
}

func TestPriorityClassification(t *testing.T) {
	high := []struct{ method, path string }{
		{http.MethodPost, "/api/v1/pair/confirm"},
		{http.MethodGet, "/api/v1/pair/pending"},
		{http.MethodGet, "/api/v1/preflight/check"},
		{http.MethodGet, "/api/v1/cluster/health"},
		{http.MethodGet, "/api/v1/cluster/preflight"},
		{http.MethodGet, "/api/v1/models/gemma-2b/download"},
		{http.MethodGet, "/v1/models/gemma-2b/download"},
	}
	normal := []struct{ method, path string }{
		{http.MethodPost, "/api/v1/chat/completions"},
		{http.MethodPost, "/v1/chat/completions"},
		{http.MethodGet, "/api/v1/models"},
		{http.MethodGet, "/v1/models"},
		{http.MethodPost, "/api/v1/models/upload"}, // non-GET model op
	}
	for _, c := range high {
		r := httptest.NewRequest(c.method, c.path, http.NoBody)
		if !isHighPriority(r) {
			t.Errorf("%s %s should be high priority", c.method, c.path)
		}
	}
	for _, c := range normal {
		r := httptest.NewRequest(c.method, c.path, http.NoBody)
		if isHighPriority(r) {
			t.Errorf("%s %s should be normal priority", c.method, c.path)
		}
	}
}

func TestBackpressureWeightingPrefersLessLoadedPhone(t *testing.T) {
	reg := registry.New()
	setup := func(id, ip string, temp float64) {
		if err := reg.Register(id, "phone", ip); err != nil {
			t.Fatal(err)
		}
		if err := reg.Pair(id); err != nil {
			t.Fatal(err)
		}
		if err := reg.UpdateHeartbeat(id, registry.HealthTelemetry{
			BatteryLevel: 80, ThermalTempC: temp,
		}); err != nil {
			t.Fatal(err)
		}
		if err := reg.SetModelStatus(id, registry.ModelStatus{Name: "m", Loaded: true}); err != nil {
			t.Fatal(err)
		}
	}

	setup("phone-a", "10.0.0.1", 20) // ice cold
	setup("phone-b", "10.0.0.2", 45)
	setup("phone-c", "10.0.0.3", 45)

	h := NewOpenAIHandler(reg, WithMaxQueuePerNode(10))
	// Load is real coordinator in-flight depth, not phone telemetry.
	// maxQueuePerNode = 10. phone-a: 6 in-flight (>50% → weight halved →
	// effective 12) but ice cold. phone-b: 7 (>50% → effective 14).
	// phone-c: 5 (exactly 50% → no penalty → effective 5) wins overall — the
	// weighting spreads load off the busier-but-cooler phone-a.
	load := func(id string, n int) {
		for i := 0; i < n; i++ {
			h.inflight.acquire(id)
		}
	}
	load("phone-a", 6)
	load("phone-b", 7)
	load("phone-c", 5)

	_, node, err := h.selectPhone("m")
	if err != nil {
		t.Fatal(err)
	}
	if node.DeviceID != "phone-c" {
		t.Errorf("weighted selection should prefer phone-c (effective in-flight 5), got %s", node.DeviceID)
	}
}

func TestEffectiveQueueDepth(t *testing.T) {
	h := NewOpenAIHandler(registry.New(), WithMaxQueuePerNode(10))
	cases := []struct{ in, want int }{
		{0, 0}, {3, 3}, {5, 5} /* exactly 50% — no penalty */, {6, 12}, {10, 20},
	}
	for _, c := range cases {
		if got := h.effectiveQueueDepth(c.in); got != c.want {
			t.Errorf("effectiveQueueDepth(%d) = %d, want %d", c.in, got, c.want)
		}
	}
}
