package api

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/chezgoulet/phonon/internal/registry"
)

// fakeSidecar is an httptest server that speaks the real coordinator↔sidecar
// wire protocol documented in docs/PHONE-API.md and implemented by the Android
// sidecar's InferenceServer.kt: Bearer auth, an OpenAI chat.completion body for
// non-streaming, SSE chat.completion.chunk deltas for streaming, and a /health
// probe. It lets the Go coordinator exercise its *real* HTTP proxies against a
// server that matches the Kotlin side — the two halves are otherwise only ever
// tested with in-process mocks, which is how the non-streaming response-shape
// mismatch went unnoticed.
func fakeSidecar(t *testing.T, token, reply string) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok","model_loaded":true,"model":"test-model","backend":"litert-lm"}`))
	})

	mux.HandleFunc("POST /v1/chat/completions", func(w http.ResponseWriter, r *http.Request) {
		// Auth contract: only the paired coordinator's Bearer token is served.
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}

		var body struct {
			Model    string `json:"model"`
			Stream   bool   `json:"stream"`
			Messages []struct {
				Role    string `json:"role"`
				Content string `json:"content"`
			} `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || len(body.Messages) == 0 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		if body.Stream {
			w.Header().Set("Content-Type", "text/event-stream")
			flusher := w.(http.Flusher)
			// A keepalive comment (ignored by SSE parsers) then word deltas.
			fmt.Fprint(w, ": keepalive\n\n")
			flusher.Flush()
			for _, word := range strings.Fields(reply) {
				chunk := fmt.Sprintf(`{"choices":[{"delta":{"content":%q},"index":0}]}`, word+" ")
				fmt.Fprintf(w, "data: %s\n\n", chunk)
				flusher.Flush()
			}
			fmt.Fprint(w, "data: [DONE]\n\n")
			flusher.Flush()
			return
		}

		// Non-streaming: OpenAI chat.completion object (what InferenceServer.kt
		// actually emits — NOT the flat {text,tokens} the coordinator used to
		// decode).
		resp := map[string]any{
			"id":      "chatcmpl-test",
			"object":  "chat.completion",
			"created": 1,
			"model":   body.Model,
			"choices": []any{map[string]any{
				"index":         0,
				"message":       map[string]any{"role": "assistant", "content": reply},
				"finish_reason": "stop",
			}},
			"usage": map[string]any{"prompt_tokens": 3, "completion_tokens": 5, "total_tokens": 8},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	})

	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// registerPhoneAt registers, pairs, and marks online a phone pointing at the
// given host, with the model loaded.
func registerPhoneAt(t *testing.T, reg *registry.Registry, id, host string) {
	t.Helper()
	if err := reg.Register(id, "test-phone", host); err != nil {
		t.Fatal(err)
	}
	if err := reg.Pair(id); err != nil {
		t.Fatal(err)
	}
	if err := reg.UpdateHeartbeat(id, registry.HealthTelemetry{BatteryLevel: 80, ThermalTempC: 30}); err != nil {
		t.Fatal(err)
	}
	if err := reg.SetModelStatus(id, registry.ModelStatus{Name: "test-model", Loaded: true}); err != nil {
		t.Fatal(err)
	}
}

func hostPort(t *testing.T, rawURL string) (string, int) {
	t.Helper()
	host, portStr, err := net.SplitHostPort(strings.TrimPrefix(rawURL, "http://"))
	if err != nil {
		t.Fatalf("split %q: %v", rawURL, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("port %q: %v", portStr, err)
	}
	return host, port
}

// TestCoordinatorSidecarContract drives the coordinator's real inference
// proxies (not the mock) against a sidecar that speaks the documented wire
// protocol, end to end.
func TestCoordinatorSidecarContract(t *testing.T) {
	const token = "device-token-abc"
	const reply = "the quick brown fox"

	srv := fakeSidecar(t, token, reply)
	host, port := hostPort(t, srv.URL)

	reg := registry.New()
	registerPhoneAt(t, reg, "phone-01", host)

	h := NewOpenAIHandler(reg,
		WithInferencePort(port),
		WithDeviceTokenLookup(func(string) string { return token }),
	)
	h.AddModel("test-model", "test")
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	t.Run("non-streaming delivers the phone's content", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
		}
		var resp ChatCompletionResponse
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("decode client response: %v", err)
		}
		if len(resp.Choices) == 0 || resp.Choices[0].Message.Content != reply {
			t.Fatalf("client content = %q, want %q (non-streaming decode contract)",
				firstContent(resp), reply)
		}
	})

	t.Run("streaming delivers the phone's content", func(t *testing.T) {
		body := `{"model":"test-model","stream":true,"messages":[{"role":"user","content":"hi"}]}`
		req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(body))
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
		}
		got := w.Body.String()
		for _, word := range strings.Fields(reply) {
			if !strings.Contains(got, word) {
				t.Errorf("streamed body missing %q\nbody: %s", word, got)
			}
		}
		if !strings.Contains(got, "[DONE]") {
			t.Errorf("streamed body missing [DONE] terminator")
		}
	})

	t.Run("phone rejects a wrong device token", func(t *testing.T) {
		reg2 := registry.New()
		registerPhoneAt(t, reg2, "phone-01", host)
		bad := NewOpenAIHandler(reg2,
			WithInferencePort(port),
			WithDeviceTokenLookup(func(string) string { return "wrong-token" }),
		)
		bad.AddModel("test-model", "test")
		mux2 := http.NewServeMux()
		bad.RegisterRoutes(mux2)

		req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
		w := httptest.NewRecorder()
		mux2.ServeHTTP(w, req)

		// The phone 401s; the coordinator surfaces it as an upstream failure.
		if w.Code != http.StatusBadGateway {
			t.Fatalf("expected 502 when the phone rejects the token, got %d: %s", w.Code, w.Body.String())
		}
	})
}

func firstContent(r ChatCompletionResponse) string {
	if len(r.Choices) == 0 {
		return ""
	}
	return r.Choices[0].Message.Content
}
