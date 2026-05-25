package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/chezgoulet/phonon/internal/registry"
)

func TestClusterHealthEmpty(t *testing.T) {
	reg := registry.New()
	h := NewClusterHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/health", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var resp ClusterHealthResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}

	if resp.Status != "offline" {
		t.Errorf("expected offline, got %s", resp.Status)
	}
	if resp.TotalNodes != 0 {
		t.Errorf("expected 0 nodes, got %d", resp.TotalNodes)
	}
}

func TestClusterHealthWithOnlineNodes(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "a", "10.0.0.1")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{BatteryLevel: 80, ThermalTempC: 35})

	reg.Register("phone-02", "b", "10.0.0.2")
	reg.Pair("phone-02")
	reg.UpdateHeartbeat("phone-02", registry.HealthTelemetry{BatteryLevel: 90, ThermalTempC: 30})

	// Assign one to a group
	reg.AssignToGroup("phone-01", "pool-alpha")

	h := NewClusterHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/health", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var resp ClusterHealthResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}

	if resp.Status != "healthy" {
		t.Errorf("expected healthy, got %s", resp.Status)
	}
	if resp.TotalNodes != 2 {
		t.Errorf("expected 2 nodes, got %d", resp.TotalNodes)
	}
	if resp.OnlineNodes != 2 {
		t.Errorf("expected 2 online, got %d", resp.OnlineNodes)
	}
	if resp.OfflineNodes != 0 {
		t.Errorf("expected 0 offline, got %d", resp.OfflineNodes)
	}
	if resp.PairedNodes != 0 {
		t.Errorf("expected 0 paired (all online), got %d", resp.PairedNodes)
	}
	if len(resp.Groups) != 1 || resp.Groups["pool-alpha"] != 1 {
		t.Errorf("expected pool-alpha group with 1 node, got %v", resp.Groups)
	}
}

func TestClusterHealthDegraded(t *testing.T) {
	reg := registry.New()

	// One online and one unpaired
	reg.Register("phone-01", "a", "10.0.0.1")

	h := NewClusterHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/health", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	var resp ClusterHealthResponse
	json.NewDecoder(w.Body).Decode(&resp)

	if resp.Status != "degraded" {
		t.Errorf("expected degraded with unpaired nodes, got %s", resp.Status)
	}
}

func TestListNodesEmpty(t *testing.T) {
	reg := registry.New()
	h := NewClusterHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/nodes", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var resp map[string]any
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}

	if resp["object"] != "list" {
		t.Errorf("expected list, got %s", resp["object"])
	}

	data := resp["data"].([]any)
	if len(data) != 0 {
		t.Errorf("expected 0 nodes, got %d", len(data))
	}
}

func TestListNodesWithGroupFilter(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "a", "10.0.0.1")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{})
	reg.AssignToGroup("phone-01", "pool-alpha")

	reg.Register("phone-02", "b", "10.0.0.2")
	reg.Pair("phone-02")
	reg.UpdateHeartbeat("phone-02", registry.HealthTelemetry{})
	reg.AssignToGroup("phone-02", "pool-beta")

	h := NewClusterHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/nodes?group=pool-alpha", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	var resp map[string]any
	json.NewDecoder(w.Body).Decode(&resp)

	data := resp["data"].([]any)
	if len(data) != 1 {
		t.Errorf("expected 1 node, got %d", len(data))
	}
}

func TestChatCompletionWithGroupHeader(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "test-phone", "10.0.0.5")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{BatteryLevel: 85})
	reg.AssignToGroup("phone-01", "pool-prime")

	node, _ := reg.Get("phone-01")
	node.ModelStatus = registry.ModelStatus{Name: "test-model", Loaded: true}

	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")
	h.inferenceProxy = func(_ string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		return &PhoneInferenceResponse{Text: "ok", Tokens: 1, Duration: 0}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := `{"model":"test-model","messages":[{"role":"user","content":"hi"}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	if v := w.Header().Get("X-Phonon-Device"); v != "phone-01" {
		t.Errorf("expected X-Phonon-Device: phone-01, got %s", v)
	}
	if v := w.Header().Get("X-Phonon-Group"); v != "pool-prime" {
		t.Errorf("expected X-Phonon-Group: pool-prime, got %s", v)
	}
	if v := w.Header().Get("X-Phonon-Queue-Depth"); v != "0" {
		t.Errorf("expected X-Phonon-Queue-Depth: 0, got %s", v)
	}
}

func TestListNodesSortOrder(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-offline", "off", "10.0.0.1")
	reg.Register("phone-online", "on", "10.0.0.2")
	reg.Pair("phone-online")
	reg.UpdateHeartbeat("phone-online", registry.HealthTelemetry{})

	h := NewClusterHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/cluster/nodes", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	var resp map[string]any
	json.NewDecoder(w.Body).Decode(&resp)

	data := resp["data"].([]any)
	if len(data) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(data))
	}

	// Online node should come first
	first := data[0].(map[string]any)
	if first["state"] != "online" {
		t.Errorf("expected first node to be online, got %s", first["state"])
	}
}
