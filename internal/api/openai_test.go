package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/chezgoulet/phonon/internal/registry"
)


const testChatBody = `{"model":"test-model","messages":[{"role":"user","content":"hello"}]}`


func TestNewOpenAIHandler(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)
	if h == nil {
		t.Fatal("expected handler, got nil")
	}
	if h.inferenceProxy == nil {
		t.Error("expected default inference proxy")
	}
}

func TestListModelsEmpty(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var resp ModelListResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.Object != "list" {
		t.Errorf("expected list, got %s", resp.Object)
	}
	if len(resp.Data) != 0 {
		t.Errorf("expected 0 models, got %d", len(resp.Data))
	}
}

func TestListModelsWithModels(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)
	h.AddModel("llama-3.2-3b", "meta")
	h.AddModel("mistral-7b", "mistral")

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var resp ModelListResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(resp.Data) != 2 {
		t.Errorf("expected 2 models, got %d", len(resp.Data))
	}

	// Check model IDs
	ids := make(map[string]bool)
	for _, m := range resp.Data {
		ids[m.ID] = true
	}
	if !ids["llama-3.2-3b"] || !ids["mistral-7b"] {
		t.Errorf("expected llama-3.2-3b and mistral-7b, got %v", ids)
	}
}

func TestSetModels(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)
	h.SetModels([]ModelInfo{
		{ID: "model-a", Object: "model", Created: 100, OwnedBy: "test"},
		{ID: "model-b", Object: "model", Created: 200, OwnedBy: "test"},
	})

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	var resp ModelListResponse
	json.NewDecoder(w.Body).Decode(&resp)
	if len(resp.Data) != 2 {
		t.Errorf("expected 2 models, got %d", len(resp.Data))
	}
}

func TestChatCompletionMissingModel(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := `{"messages":[{"role":"user","content":"hello"}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func TestChatCompletionMissingMessages(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := `{"model":"test-model"}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func TestChatCompletionStream(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "test-phone", "10.0.0.5")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{})
	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "test-model", Loaded: true})

	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	// Override stream proxy to emit test chunks
	h.streamInferenceProxy = func(_ string, _ PhoneInferenceRequest, onChunk func(string)) (string, error) {
		onChunk("Hello")
		onChunk(" world!")
		return "Hello world!", nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := `{"model":"test-model","messages":[{"role":"user","content":"hi"}],"stream":true}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	bodyStr := w.Body.String()
	t.Logf("SSE body:\n%s", bodyStr)

	if !strings.Contains(bodyStr, "data:") {
		t.Errorf("expected SSE data events")
	}
	if !strings.Contains(bodyStr, "chat.completion.chunk") {
		t.Errorf("expected chat.completion.chunk object")
	}
	if !strings.Contains(bodyStr, "[DONE]") {
		t.Errorf("expected [DONE] sentinel")
	}
	if !strings.Contains(bodyStr, `"Hello"`) {
		t.Errorf("expected chunk with Hello")
	}
	if !strings.Contains(bodyStr, `" world!"`) {
		t.Errorf("expected chunk with ' world!'")
	}
	if v := w.Header().Get("X-Phonon-Device"); v != "phone-01" {
		t.Errorf("expected X-Phonon-Device: phone-01, got %s", v)
	}
	if v := w.Header().Get("Content-Type"); v != "text/event-stream" {
		t.Errorf("expected text/event-stream, got %s", v)
	}
}

func TestChatCompletionNoPhoneAvailable(t *testing.T) {
	reg := registry.New()

	// Register a node but don't put it online
	reg.Register("phone-01", "test-phone", "10.0.0.1")

	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := testChatBody
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("expected 503, got %d", w.Code)
	}
}

func TestChatCompletionWithOnlinePhone(t *testing.T) {
	reg := registry.New()

	// Register and pair a phone
	reg.Register("phone-01", "test-phone", "10.0.0.5")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{
		BatteryLevel: 85,
		ThermalTempC: 30,
		QueueDepth:   2, // below default maxQueuePerNode (3)
	})

	// Set model loaded
	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "test-model", Loaded: true})

	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	// Override inference proxy to return a controlled response
	h.inferenceProxy = func(phoneURL string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		if !strings.Contains(phoneURL, "10.0.0.5:9876/v1/chat/completions") {
			t.Errorf("expected phone URL to contain 10.0.0.5:9876/v1/chat/completions, got %s", phoneURL)
		}
		return &PhoneInferenceResponse{
			Text:     "Hello! How can I help you?",
			Tokens:   6,
			Duration: 150,
		}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := `{"model":"test-model","messages":[{"role":"user","content":"Say hello"}]}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	// Check response headers
	if v := w.Header().Get("X-Phonon-Device"); v != "phone-01" {
		t.Errorf("expected X-Phonon-Device: phone-01, got %s", v)
	}
	if v := w.Header().Get("X-Phonon-Queue-Depth"); v != "2" {
		t.Errorf("expected X-Phonon-Queue-Depth: 2, got %s", v)
	}
	if v := w.Header().Get("X-Phonon-Group"); v != "" {
		t.Errorf("expected empty X-Phonon-Group, got %s", v)
	}

	var resp ChatCompletionResponse
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}

	if resp.Object != "chat.completion" {
		t.Errorf("expected chat.completion, got %s", resp.Object)
	}
	if resp.Model != "test-model" {
		t.Errorf("expected test-model, got %s", resp.Model)
	}
	if len(resp.Choices) != 1 {
		t.Errorf("expected 1 choice, got %d", len(resp.Choices))
	}
	if resp.Choices[0].Message.Content != "Hello! How can I help you?" {
		t.Errorf("unexpected content: %s", resp.Choices[0].Message.Content)
	}
	if resp.Choices[0].Message.Role != "assistant" {
		t.Errorf("expected assistant role, got %s", resp.Choices[0].Message.Role)
	}
	if resp.Usage.TotalTokens <= 0 {
		t.Errorf("expected positive total tokens, got %d", resp.Usage.TotalTokens)
	}
}

func TestChatCompletionPhoneFails(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "test-phone", "10.0.0.5")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{})

	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "test-model", Loaded: true})

	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	// Override proxy to fail
	h.inferenceProxy = func(_ string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		return nil, fmt.Errorf("connection refused")
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := testChatBody
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Errorf("expected 502, got %d", w.Code)
	}
}

func TestChatCompletionBackpressure(t *testing.T) {
	reg := registry.New()

	// Phone at capacity (QueueDepth = 3, default max = 3)
	reg.Register("phone-01", "test-phone", "10.0.0.5")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{
		QueueDepth: 3,
	})
	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "test-model", Loaded: true})

	// Pass maxQueuePerNode = 2 to force backpressure
	h := NewOpenAIHandler(reg, WithMaxQueuePerNode(2))
	h.AddModel("test-model", "test")

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := testChatBody
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusTooManyRequests {
		t.Errorf("expected 429, got %d", w.Code)
	}

	if v := w.Header().Get("Retry-After"); v != "5" {
		t.Errorf("expected Retry-After: 5, got %s", v)
	}

	var bodyMap map[string]any
	if err := json.NewDecoder(w.Body).Decode(&bodyMap); err != nil {
		t.Fatalf("decode: %v", err)
	}
	errObj, ok := bodyMap["error"].(map[string]any)
	if !ok {
		t.Fatal("expected error object")
	}
	if errObj["type"] != "rate_limit_error" {
		t.Errorf("expected rate_limit_error, got %s", errObj["type"])
	}
}

func TestSelectPhoneWithModel(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "phone-a", "10.0.0.1")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{})

	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "my-model", Loaded: true})

	h := NewOpenAIHandler(reg)

	phone, _, err := h.selectPhone("my-model")
	if err != nil {
		t.Fatalf("selectPhone: %v", err)
	}
	if phone != "10.0.0.1" {
		t.Errorf("expected 10.0.0.1, got %s", phone)
	}
}

func TestSelectPhoneNoMatch(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)

	_, _, err := h.selectPhone("nonexistent-model")
	if err == nil {
		t.Error("expected error for nonexistent model")
	}
}

func TestModelRouteRegistration(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)
	h.AddModel("a", "test")
	h.AddModel("b", "test")

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	// GET /v1/models should work
	req := httptest.NewRequest(http.MethodGet, "/v1/models", http.NoBody)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	// POST /v1/chat/completions should work (with 400 for missing fields)
	req2 := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(`{}`))
	req2.Header.Set("Content-Type", "application/json")
	w2 := httptest.NewRecorder()
	mux.ServeHTTP(w2, req2)
	if w2.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w2.Code)
	}
}

func TestBuildPrompt(t *testing.T) {
	msgs := []Message{
		{Role: "system", Content: "Be helpful."},
		{Role: "user", Content: "Hi!"},
	}
	result := buildPrompt(msgs)
	if !strings.Contains(result, "system: Be helpful.") {
		t.Errorf("expected system message in prompt, got: %s", result)
	}
	if !strings.Contains(result, "user: Hi!") {
		t.Errorf("expected user message in prompt, got: %s", result)
	}
}

func TestEstimateTokens(t *testing.T) {
	text := "hello world"
	tokens := estimateTokens(text)
	// (11+3)/4 = 3
	if tokens != 3 {
		t.Errorf("expected 3 tokens for 'hello world' (11 chars), got %d", tokens)
	}

	if estimateTokens("") != 0 {
		t.Error("expected 0 tokens for empty string")
	}

	// Single character => 1
	if estimateTokens("a") != 1 {
		t.Errorf("expected 1 token for 'a', got %d", estimateTokens("a"))
	}
}

func TestGenerateCompletionID(t *testing.T) {
	id1 := generateCompletionID()
	id2 := generateCompletionID()
	if id1 == id2 {
		t.Errorf("expected unique IDs, got %s and %s", id1, id2)
	}
	if !strings.HasPrefix(id1, "chatcmpl-") {
		t.Errorf("expected chatcmpl- prefix, got %s", id1)
	}
}

func TestDefaultInferenceProxy(t *testing.T) {
	reg := registry.New()
	h := NewOpenAIHandler(reg)

	resp, err := h.defaultInferenceProxy("http://10.0.0.1:8080", PhoneInferenceRequest{
		Model: "test", Messages: []Message{{Role: "user", Content: "hi"}},
	})
	if err != nil {
		t.Fatalf("defaultInferenceProxy: %v", err)
	}
	if resp.Tokens != 10 {
		t.Errorf("expected 10 tokens, got %d", resp.Tokens)
	}
	if !strings.Contains(resp.Text, "10.0.0.1") {
		t.Errorf("expected phone URL in response, got %s", resp.Text)
	}
}

func TestChatCompletionRequestJSON(t *testing.T) {
	// Verify proper JSON round-trip
	body := `{
		"model": "llama-3.2-3b",
		"messages": [{"role": "system", "content": "You are helpful."}, {"role": "user", "content": "What is 2+2?"}],
		"temperature": 0.5,
		"max_tokens": 100,
		"top_p": 0.9,
		"seed": 42
	}`

	var req ChatCompletionRequest
	if err := json.Unmarshal([]byte(body), &req); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}

	if req.Model != "llama-3.2-3b" {
		t.Errorf("expected llama-3.2-3b, got %s", req.Model)
	}
	if len(req.Messages) != 2 {
		t.Errorf("expected 2 messages, got %d", len(req.Messages))
	}
	if req.Temperature != 0.5 {
		t.Errorf("expected 0.5, got %f", req.Temperature)
	}
	if req.MaxTokens != 100 {
		t.Errorf("expected 100, got %d", req.MaxTokens)
	}
	if req.Seed == nil || *req.Seed != 42 {
		t.Errorf("expected seed 42")
	}
}

func TestOpenAIResponseJSON(t *testing.T) {
	resp := ChatCompletionResponse{
		ID:      "chatcmpl-test",
		Object:  "chat.completion",
		Created: 12345,
		Model:   "test-model",
		Choices: []Choice{{Index: 0, Message: Message{Role: "assistant", Content: "Hi!"}, FinishReason: "stop"}},
		Usage:   Usage{PromptTokens: 10, CompletionTokens: 5, TotalTokens: 15},
	}

	data, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}

	var back ChatCompletionResponse
	if err := json.Unmarshal(data, &back); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}

	if back.ID != "chatcmpl-test" {
		t.Errorf("ID mismatch")
	}
	if back.Choices[0].Message.Content != "Hi!" {
		t.Errorf("content mismatch")
	}
	if back.Usage.TotalTokens != 15 {
		t.Errorf("token mismatch")
	}
}

func TestModelListResponseJSON(t *testing.T) {
	resp := ModelListResponse{
		Object: "list",
		Data: []ModelInfo{
			{ID: "model-a", Object: "model", Created: 100, OwnedBy: "test"},
		},
	}

	data, err := json.Marshal(resp)
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}

	var back ModelListResponse
	if err := json.Unmarshal(data, &back); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}

	if len(back.Data) != 1 || back.Data[0].ID != "model-a" {
		t.Errorf("model list round-trip failed")
	}
}

func TestSelectPhoneWithMultipleCandidates(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "a", "10.0.0.1")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{})
	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "llama", Loaded: true})

	reg.Register("phone-02", "b", "10.0.0.2")
	reg.Pair("phone-02")
	reg.UpdateHeartbeat("phone-02", registry.HealthTelemetry{})
	reg.SetModelStatus("phone-02", registry.ModelStatus{Name: "llama", Loaded: true})

	h := NewOpenAIHandler(reg)

	// With equal health metrics, order is deterministic (first registered wins on tie)
	phone, node, err := h.selectPhone("llama")
	if err != nil {
		t.Fatalf("selectPhone: %v", err)
	}
	if phone == "" {
		t.Error("expected a phone to be selected")
	}
	_ = node
}

func TestStreamChatCompletionProxyError(t *testing.T) {
	reg := registry.New()

	reg.Register("phone-01", "test-phone", "10.0.0.5")
	reg.Pair("phone-01")
	reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{})
	reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "test-model", Loaded: true})

	h := NewOpenAIHandler(reg)
	h.AddModel("test-model", "test")

	// Override stream proxy to fail
	h.streamInferenceProxy = func(_ string, _ PhoneInferenceRequest, _ func(string)) (string, error) {
		return "", fmt.Errorf("broken pipe: \"quote\" and \\backslash")
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body := `{"model":"test-model","messages":[{"role":"user","content":"hi"}],"stream":true}`
	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 (SSE), got %d", w.Code)
	}

	bodyStr := w.Body.String()
	t.Logf("SSE error body:\n%s", bodyStr)

	// Must contain a JSON error payload
	if !strings.Contains(bodyStr, `"error"`) {
		t.Errorf("expected error object in SSE, got: %s", bodyStr)
	}
	if !strings.Contains(bodyStr, `"inference failed: broken pipe`) {
		t.Errorf("expected inference failed message, got: %s", bodyStr)
	}
	// The error message contains real quotes which are JSON-escaped in the SSE payload
	if !strings.Contains(bodyStr, `\"quote\"`) {
		t.Errorf("expected escaped quote in error message, got: %s", bodyStr)
	}
	if !strings.Contains(bodyStr, `"type":"inference_error"`) {
		t.Errorf("expected inference_error type, got: %s", bodyStr)
	}
	if !strings.Contains(bodyStr, "[DONE]") {
		t.Errorf("expected [DONE] sentinel after error event")
	}

	// Verify the error chunk is valid JSON
	for _, line := range strings.Split(bodyStr, "\n") {
		if strings.HasPrefix(line, "data: ") {
			payload := strings.TrimPrefix(line, "data: ")
			if payload == "[DONE]" {
				continue
			}
			var parsed map[string]any
			if err := json.Unmarshal([]byte(payload), &parsed); err != nil {
				t.Errorf("invalid JSON in SSE data: %v\nline: %s", err, line)
			}
		}
	}
}

func TestSelectPhoneHealthAware(t *testing.T) {
	tests := []struct{
		name string
		phone1Health registry.HealthTelemetry
		phone2Health registry.HealthTelemetry
		expectedIP string
	}{
		{
			name: "lower queue depth wins",
			phone1Health: registry.HealthTelemetry{QueueDepth: 5, ThermalTempC: 30, BatteryLevel: 80},
			phone2Health: registry.HealthTelemetry{QueueDepth: 1, ThermalTempC: 30, BatteryLevel: 80},
			expectedIP: "10.0.0.2",
		},
		{
			name: "cooler temperature wins",
			phone1Health: registry.HealthTelemetry{QueueDepth: 3, ThermalTempC: 35, BatteryLevel: 80},
			phone2Health: registry.HealthTelemetry{QueueDepth: 3, ThermalTempC: 28, BatteryLevel: 80},
			expectedIP: "10.0.0.2",
		},
		{
			name: "higher battery wins",
			phone1Health: registry.HealthTelemetry{QueueDepth: 2, ThermalTempC: 30, BatteryLevel: 60},
			phone2Health: registry.HealthTelemetry{QueueDepth: 2, ThermalTempC: 30, BatteryLevel: 90},
			expectedIP: "10.0.0.2",
		},
		{
			name: "combined: queue depth overrides temperature",
			phone1Health: registry.HealthTelemetry{QueueDepth: 0, ThermalTempC: 40, BatteryLevel: 50},
			phone2Health: registry.HealthTelemetry{QueueDepth: 5, ThermalTempC: 25, BatteryLevel: 90},
			expectedIP: "10.0.0.1", // phone-01 has lower queue depth
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			reg := registry.New()

			reg.Register("phone-01", "a", "10.0.0.1")
			reg.Pair("phone-01")
			reg.UpdateHeartbeat("phone-01", tt.phone1Health)
			reg.SetModelStatus("phone-01", registry.ModelStatus{Name: "llama", Loaded: true})

			reg.Register("phone-02", "b", "10.0.0.2")
			reg.Pair("phone-02")
			reg.UpdateHeartbeat("phone-02", tt.phone2Health)
			reg.SetModelStatus("phone-02", registry.ModelStatus{Name: "llama", Loaded: true})

			h := NewOpenAIHandler(reg, WithMaxQueuePerNode(0))

			phone, _, err := h.selectPhone("llama")
			if err != nil {
				t.Fatalf("selectPhone: %v", err)
			}
			if phone != tt.expectedIP {
				t.Errorf("expected %s, got %s", tt.expectedIP, phone)
			}
		})
	}
}
