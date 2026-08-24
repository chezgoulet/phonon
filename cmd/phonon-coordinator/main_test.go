package main

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/chezgoulet/phonon/internal/config"
)

func TestHealthEndpoint(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok","version":"0.1.0"}`))
	})

	server := httptest.NewServer(mux)
	defer server.Close()

	resp, err := http.Get(server.URL + "/health")
	if err != nil {
		t.Fatalf("health request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var body map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if body["status"] != "ok" {
		t.Errorf("expected status ok, got %q", body["status"])
	}
	if body["version"] != "0.1.0" {
		t.Errorf("expected version 0.1.0, got %q", body["version"])
	}
}

// TestWiring_TraceMiddlewareOutermostStripsAuthClaims pins the #311
// global-strip invariant at the wiring level. It calls the SAME buildHandler()
// construction production uses in main() (main.go: Handler: buildHandler(mux,
// cfg, logger)) and asserts that a request carrying an upstream-injected
// X-Auth-Claims has that header removed before it reaches an auth-free mount
// shaped like /api/v1/sidecar/*. The strip must NOT depend on auth middleware
// being present: if this test fails after a wiring change, TraceMiddleware is
// no longer outermost and the strip is no longer global. Because the test and
// production share buildHandler (#328), a rewiring cannot silently diverge
// from what this test pins.
func TestWiring_TraceMiddlewareOutermostStripsAuthClaims(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	mux := http.NewServeMux()
	var sawClaims string
	mux.Handle("/api/v1/sidecar/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawClaims = r.Header.Get("X-Auth-Claims")
		w.WriteHeader(http.StatusOK)
	}))

	// Shared with production via buildHandler — TraceMiddleware wraps the whole mux.
	cfg := &config.Config{
		Cluster: config.ClusterConfig{
			Networking: config.NetworkingConfig{
				CORSOrigins: []string{"*"},
			},
		},
	}
	handler := buildHandler(mux, cfg, logger)
	srv := httptest.NewServer(handler)
	defer srv.Close()

	req, err := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/sidecar/pair", http.NoBody)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-upstream-proxy"}`)
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	if sawClaims != "" {
		t.Errorf("#311: X-Auth-Claims survived the top-level handler on an auth-free mount: %q", sawClaims)
	}
}
