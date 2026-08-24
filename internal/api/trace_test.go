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
