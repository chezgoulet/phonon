package api

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/chezgoulet/phonon/internal/registry"
)

// resilienceTestRegistry sets up two online phones with the same model.
func resilienceTestRegistry(t *testing.T) *registry.Registry {
	t.Helper()
	reg := registry.New()
	for i, ip := range []string{"10.0.0.5", "10.0.0.6"} {
		id := fmt.Sprintf("phone-%02d", i+1)
		if err := reg.Register(id, "test-phone", ip); err != nil {
			t.Fatal(err)
		}
		if err := reg.Pair(id); err != nil {
			t.Fatal(err)
		}
		if err := reg.UpdateHeartbeat(id, registry.HealthTelemetry{
			BatteryLevel: 85,
			ThermalTempC: 30,
			QueueDepth:   i, // phone-01 is healthiest (queue 0)
		}); err != nil {
			t.Fatal(err)
		}
		if err := reg.SetModelStatus(id, registry.ModelStatus{Name: "test-model", Loaded: true}); err != nil {
			t.Fatal(err)
		}
	}
	return reg
}

func TestChatCompletionRetriesOnFallbackPhone(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	var attempts []string
	h.inferenceProxy = func(phoneURL string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		attempts = append(attempts, phoneURL)
		if strings.Contains(phoneURL, "10.0.0.5") {
			return nil, fmt.Errorf("phone request failed: connection refused")
		}
		return &PhoneInferenceResponse{Text: "fallback says hi", Tokens: 4}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 after fallback, got %d: %s", w.Code, w.Body.String())
	}
	if len(attempts) != 2 {
		t.Fatalf("expected 2 attempts (original + fallback), got %d: %v", len(attempts), attempts)
	}
	if !strings.Contains(attempts[1], "10.0.0.6") {
		t.Errorf("fallback should target the second phone, got %s", attempts[1])
	}
	var resp ChatCompletionResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if resp.Choices[0].Message.Content != "fallback says hi" {
		t.Errorf("expected fallback response text, got %q", resp.Choices[0].Message.Content)
	}
	if got := w.Header().Get("X-Phonon-Device"); got != "phone-02" {
		t.Errorf("X-Phonon-Device should identify the serving phone, got %q", got)
	}
}

func TestChatCompletionBothPhonesFail(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	calls := 0
	h.inferenceProxy = func(string, PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		calls++
		return nil, fmt.Errorf("boom")
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 when all attempts fail, got %d", w.Code)
	}
	if calls != 2 {
		t.Fatalf("expected exactly 2 attempts (max retries), got %d", calls)
	}
}

func TestChatCompletionTimeoutReturns504(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	h.inferenceProxy = func(string, PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		return nil, fmt.Errorf("phone request failed: %w", context.DeadlineExceeded)
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusGatewayTimeout {
		t.Fatalf("expected 504 on timeout, got %d", w.Code)
	}
	if got := w.Header().Get("X-Phonon-Error"); got != "timeout" {
		t.Errorf("expected X-Phonon-Error: timeout, got %q", got)
	}
}

func TestChatCompletionPropagatesTimeoutToPhone(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	var gotTimeout int
	h.inferenceProxy = func(_ string, req PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		gotTimeout = req.TimeoutMs
		return &PhoneInferenceResponse{Text: "ok", Tokens: 1}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	body := `{"model":"test-model","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	// 100 tokens * 50ms + 10s = 15000ms
	if gotTimeout != 15000 {
		t.Errorf("expected timeout_ms=15000 for max_tokens=100, got %d", gotTimeout)
	}
}

func TestBreakerOpensAfterRepeatedHandlerFailures(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	h.inferenceProxy = func(string, PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		return nil, fmt.Errorf("down")
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	// Two failing requests = 4 failures spread over both phones (2 each).
	// A third request adds the 3rd failure per phone, opening both breakers.
	for i := 0; i < 3; i++ {
		req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
	}

	if st := h.breaker.State("phone-01"); st != BreakerOpen {
		t.Errorf("phone-01 breaker should be open, got %s", st)
	}
	if st := h.breaker.State("phone-02"); st != BreakerOpen {
		t.Errorf("phone-02 breaker should be open, got %s", st)
	}

	// With both breakers open, selection must fail.
	if _, _, err := h.selectPhone("test-model"); err == nil {
		t.Error("selection should fail when all breakers are open")
	}
}

func TestSelectPhoneSkipsExcludedDevices(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)

	_, node, err := h.selectPhoneExcluding("test-model", map[string]bool{"phone-01": true})
	if err != nil {
		t.Fatal(err)
	}
	if node.DeviceID != "phone-02" {
		t.Errorf("expected phone-02 after excluding phone-01, got %s", node.DeviceID)
	}
}

func TestInferencePortOption(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg, WithInferencePort(12345))
	h.AddModel("test-model", "test")

	var gotURL string
	h.inferenceProxy = func(phoneURL string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		gotURL = phoneURL
		return &PhoneInferenceResponse{Text: "ok", Tokens: 1}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if !strings.Contains(gotURL, ":12345/") {
		t.Errorf("expected custom inference port in URL, got %s", gotURL)
	}
}

func TestOpenAIRoutesRegisteredUnderAPIPrefix(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("GET /api/v1/models should be routable, got %d", w.Code)
	}
}

func TestStreamingFailoverContinuesStream(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	attempt := 0
	h.streamInferenceProxy = func(phoneURL string, _ PhoneInferenceRequest, onChunk func(string)) (string, error) {
		attempt++
		if attempt == 1 {
			// Deliver a partial stream, then die mid-stream.
			onChunk("Hello ")
			return "Hello ", fmt.Errorf("stream read error: connection reset")
		}
		// Fallback regenerates from scratch; the handler must skip the
		// prefix already delivered.
		onChunk("Hello ")
		onChunk("world")
		return "Hello world", nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	body := `{"model":"test-model","stream":true,"messages":[{"role":"user","content":"hi"}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
	if attempt != 2 {
		t.Fatalf("expected 2 streaming attempts, got %d", attempt)
	}

	// Reassemble delivered content deltas.
	var content strings.Builder
	sawDone := false
	scanner := bufio.NewScanner(strings.NewReader(w.Body.String()))
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		data := strings.TrimPrefix(line, "data: ")
		if data == "[DONE]" {
			sawDone = true
			continue
		}
		var chunk struct {
			Choices []struct {
				Delta struct {
					Content string `json:"content"`
				} `json:"delta"`
			} `json:"choices"`
			Error *struct{} `json:"error"`
		}
		if err := json.Unmarshal([]byte(data), &chunk); err != nil {
			t.Fatalf("malformed SSE chunk %q: %v", data, err)
		}
		if chunk.Error != nil {
			t.Fatalf("stream should succeed after failover, got error event: %s", data)
		}
		for _, c := range chunk.Choices {
			content.WriteString(c.Delta.Content)
		}
	}
	if content.String() != "Hello world" {
		t.Errorf("client should receive exactly %q (no duplicated prefix), got %q", "Hello world", content.String())
	}
	if !sawDone {
		t.Error("stream should terminate with [DONE]")
	}
}

func TestStreamingBothPhonesFailEmitsPartialMetadata(t *testing.T) {
	reg := resilienceTestRegistry(t)
	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	h.streamInferenceProxy = func(_ string, _ PhoneInferenceRequest, onChunk func(string)) (string, error) {
		onChunk("partial")
		return "partial", fmt.Errorf("stream read error: reset")
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)
	body := `{"model":"test-model","stream":true,"messages":[{"role":"user","content":"hi"}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	out := w.Body.String()
	if !strings.Contains(out, `"partial":true`) {
		t.Errorf("error event should carry partial metadata, got:\n%s", out)
	}
	if !strings.Contains(out, "data: [DONE]") {
		t.Error("stream must terminate with [DONE] after error")
	}
}
