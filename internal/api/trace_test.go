package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestTraceMiddleware_StripsAuthClaimsOnSidecarMount verifies #302 finding 2:
// X-Auth-Claims is stripped by the outermost TraceMiddleware even on mounts
// that have no auth middleware (e.g. /api/v1/sidecar/*), restoring the strip
// invariant as global rather than per-mount.
func TestTraceMiddleware_StripsAuthClaimsOnSidecarMount(t *testing.T) {
	sidecarMux := http.NewServeMux()
	var sawClaims bool
	sidecarMux.HandleFunc("/api/v1/sidecar/pair", func(w http.ResponseWriter, r *http.Request) {
		sawClaims = r.Header.Get("X-Auth-Claims") != ""
		w.WriteHeader(http.StatusOK)
	})

	root := http.NewServeMux()
	root.Handle("/api/v1/sidecar/", sidecarMux)
	handler := TraceMiddleware(root)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/sidecar/pair", http.NoBody)
	req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-upstream-proxy"}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	if sawClaims {
		t.Error("X-Auth-Claims reached sidecar handler; outermost strip is missing")
	}
	if w.Header().Get(TraceIDHeader) == "" {
		t.Error("expected trace ID header still set")
	}
}

func TestTraceMiddleware_StripsAuthClaimsOnProtectedPath(t *testing.T) {
	var sawClaims string
	protected := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawClaims = r.Header.Get("X-Auth-Claims")
		w.WriteHeader(http.StatusOK)
	})
	handler := TraceMiddleware(protected)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models", http.NoBody)
	req.Header.Set("X-Auth-Claims", "injected")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	if sawClaims != "" {
		t.Errorf("X-Auth-Claims survived: %q", sawClaims)
	}
}

func TestTraceMiddleware_TraceIDStillWorks(t *testing.T) {
	var traceID string
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		traceID = TraceIDFromContext(r.Context())
	})
	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("X-Phonon-Trace-Id", "client-injected")
	w := httptest.NewRecorder()
	TraceMiddleware(next).ServeHTTP(w, req)

	if len(traceID) != 32 {
		t.Errorf("expected 32-char server-generated trace ID, got %q", traceID)
	}
}

// TestTraceMiddleware_GlobalStripWithoutAuthMiddleware pins the #311
// global-strip invariant as a regression tripwire: TraceMiddleware sits
// OUTERMOST on the top-level server handler, so ANY request routed through
// it has X-Auth-Claims removed before a downstream handler sees it —
// regardless of whether auth middleware covers that mount. It mirrors the
// production construction (http.Server.Handler = TraceMiddleware(mux)) and
// exercises an auth-free mount shaped like /api/v1/sidecar/* plus a bare
// nested handler. If this fails after a rewiring, a handler is being
// mounted outside TraceMiddleware and the strip is no longer global.
func TestTraceMiddleware_GlobalStripWithoutAuthMiddleware(t *testing.T) {
	mux := http.NewServeMux()

	// Auth-free sidecar-style mount: NO auth middleware anywhere on this path.
	var sidecarSawClaims string
	mux.Handle("/api/v1/sidecar/", http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		sidecarSawClaims = r.Header.Get("X-Auth-Claims")
	}))

	// Bare nested handler (also no auth middleware).
	var bareSawClaims string
	mux.Handle("/bare", http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		bareSawClaims = r.Header.Get("X-Auth-Claims")
	}))

	// Mirror the production wiring shape from main.go:
	// Handler: api.TraceMiddleware(corsMiddleware(mux, ...)) — i.e. the
	// strip middleware wraps the WHOLE mux, structurally outermost.
	srv := httptest.NewServer(TraceMiddleware(mux))
	t.Cleanup(srv.Close)

	for _, path := range []string{"/api/v1/sidecar/pair", "/bare"} {
		req, err := http.NewRequest(http.MethodGet, srv.URL+path, http.NoBody)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-upstream-proxy"}`)
		resp, err := srv.Client().Do(req)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Errorf("%s: expected 200, got %d", path, resp.StatusCode)
		}
	}

	if sidecarSawClaims != "" {
		t.Errorf("#311: X-Auth-Claims reached auth-free sidecar mount: %q — strip is not global", sidecarSawClaims)
	}
	if bareSawClaims != "" {
		t.Errorf("#311: X-Auth-Claims reached bare nested handler: %q — strip is not global", bareSawClaims)
	}
}
