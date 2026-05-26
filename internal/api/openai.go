// Package api provides HTTP handlers for the coordinator REST API.
package api

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"math/rand"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

// --- OpenAI-compatible types ---

// ChatCompletionRequest is an OpenAI-compatible chat completion request.
type ChatCompletionRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Stream      bool      `json:"stream,omitempty"`
	Temperature float64   `json:"temperature,omitempty"`
	MaxTokens   int       `json:"max_tokens,omitempty"`
	TopP        float64   `json:"top_p,omitempty"`
	Seed        *int64    `json:"seed,omitempty"`
	Stop        any       `json:"stop,omitempty"` // string or []string
}

// Message represents a single message in the chat history.
type Message struct {
	Role    string `json:"role"`    // "system", "user", "assistant"
	Content string `json:"content"`
}

// ChatCompletionResponse is an OpenAI-compatible chat completion response.
type ChatCompletionResponse struct {
	ID      string   `json:"id"`
	Object  string   `json:"object"`
	Created int64    `json:"created"`
	Model   string   `json:"model"`
	Choices []Choice `json:"choices"`
	Usage   Usage    `json:"usage"`
}

// Choice represents a single completion choice.
type Choice struct {
	Index        int     `json:"index"`
	Message      Message `json:"message"`
	FinishReason string  `json:"finish_reason"`
}

// Usage tracks token counts.
type Usage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

// ModelListResponse is the OpenAI-compatible model listing response.
type ModelListResponse struct {
	Object string      `json:"object"`
	Data   []ModelInfo `json:"data"`
}

// ModelInfo describes a single model entry.
type ModelInfo struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	Created int64  `json:"created"`
	OwnedBy string `json:"owned_by"`
}

// PhoneInferenceRequest is sent from the coordinator to a phone for inference.
type PhoneInferenceRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Temperature float64   `json:"temperature"`
	MaxTokens   int       `json:"max_tokens"`
}

// PhoneInferenceResponse is returned by the phone after inference.
type PhoneInferenceResponse struct {
	Text     string `json:"text"`
	Tokens   int    `json:"tokens"`
	Duration int    `json:"duration_ms"`
}

// OpenAIHandler manages the OpenAI-compatible API endpoints.
type OpenAIHandler struct {
	reg    *registry.Registry
	log    *slog.Logger
	models map[string]ModelInfo // available models (from cache/registry)
	modelsMu sync.RWMutex

	// inferenceProxy sends requests to phones. Override for testing.
	inferenceProxy func(phoneURL string, req PhoneInferenceRequest) (*PhoneInferenceResponse, error)

	// streamInferenceProxy sends streaming requests to phones. The callback is
	// called once per delta chunk (content string). Returns the full text for
	// token counting. Override for testing.
	streamInferenceProxy func(phoneURL string, req PhoneInferenceRequest, onChunk func(string)) (string, error)

	// maxQueuePerNode is the max queue depth before returning 429. 0 = unlimited.
	maxQueuePerNode int
}

// NewOpenAIHandler creates an OpenAI-compatible handler.
func NewOpenAIHandler(reg *registry.Registry, opts ...OpenAIOption) *OpenAIHandler {
	h := &OpenAIHandler{
		reg:             reg,
		log:             slog.With("component", "openai"),
		maxQueuePerNode: 3, // default
	}
	for _, opt := range opts {
		opt(h)
	}
	h.inferenceProxy = h.defaultInferenceProxy
	h.streamInferenceProxy = h.defaultStreamInferenceProxy
	return h
}

// OpenAIOption configures an OpenAIHandler.
type OpenAIOption func(*OpenAIHandler)

// WithMaxQueuePerNode sets the maximum queue depth per node before 429.
func WithMaxQueuePerNode(n int) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.maxQueuePerNode = n
	}
}

// RegisterRoutes adds OpenAI-compatible endpoints to the given mux.
func (h *OpenAIHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/chat/completions", h.handleChatCompletion)
	mux.HandleFunc("GET /v1/models", h.handleListModels)
}

// SetModels sets the available model list (for wiring from cache).
func (h *OpenAIHandler) SetModels(models []ModelInfo) {
	h.modelsMu.Lock()
	defer h.modelsMu.Unlock()
	h.models = make(map[string]ModelInfo, len(models))
	for _, m := range models {
		h.models[m.ID] = m
	}
}

// AddModel adds a single model to the available list.
func (h *OpenAIHandler) AddModel(id, ownedBy string) {
	h.modelsMu.Lock()
	defer h.modelsMu.Unlock()
	if h.models == nil {
		h.models = make(map[string]ModelInfo)
	}
	h.models[id] = ModelInfo{
		ID:      id,
		Object:  "model",
		Created: time.Now().Unix(),
		OwnedBy: ownedBy,
	}
}

// --- Handlers ---

func (h *OpenAIHandler) handleListModels(w http.ResponseWriter, _ *http.Request) {
	h.modelsMu.RLock()
	defer h.modelsMu.RUnlock()

	data := make([]ModelInfo, 0, len(h.models))
	for _, m := range h.models {
		data = append(data, m)
	}

	if data == nil {
		data = []ModelInfo{}
	}

	resp := ModelListResponse{
		Object: "list",
		Data:   data,
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *OpenAIHandler) handleChatCompletion(w http.ResponseWriter, r *http.Request) {
	var req ChatCompletionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": map[string]string{
				"message": "invalid request body: " + err.Error(),
				"type":    "invalid_request_error",
			},
		})
		return
	}

	// Validate request
	if req.Model == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": map[string]string{
				"message": "model is required",
				"type":    "invalid_request_error",
			},
		})
		return
	}
	if len(req.Messages) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": map[string]string{
				"message": "messages is required",
				"type":    "invalid_request_error",
			},
		})
		return
	}

	// Streaming path
	if req.Stream {
		h.handleStreamingChatCompletion(w, r, req)
		return
	}

	// Set defaults
	if req.MaxTokens <= 0 {
		req.MaxTokens = 2048
	}
	if req.Temperature == 0 {
		req.Temperature = 0.7
	}

	// Select a phone for inference
	phone, phoneNode, err := h.selectPhone(req.Model)
	if err != nil {
		h.log.Warn("no available phone for inference", "model", req.Model, "error", err)
		if strings.Contains(err.Error(), "at capacity") {
			w.Header().Set("Retry-After", "5")
			writeJSON(w, http.StatusTooManyRequests, map[string]any{
				"error": map[string]string{
					"message": "all inference nodes are busy, try again later",
					"type":    "rate_limit_error",
				},
			})
		} else {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"error": map[string]string{
					"message": fmt.Sprintf("no available phone with model %q loaded", req.Model),
					"type":    "server_error",
				},
			})
		}
		return
	}

	// Build inference URL from phone's address
	phoneURL := fmt.Sprintf("http://%s:%d/v1/chat/completions", phone, defaultInferencePort)

	// Send inference request to phone
	inferResp, err := h.inferenceProxy(phoneURL, PhoneInferenceRequest{
		Model:       req.Model,
		Messages:    req.Messages,
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
	})
	if err != nil {
		h.log.Error("phone inference failed", "phone", phoneURL, "error", err)
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error": map[string]string{
				"message": "phone inference failed: " + err.Error(),
				"type":    "server_error",
			},
		})
		return
	}

	// Set response headers
	w.Header().Set("X-Phonon-Device", phoneNode.DeviceID)
	w.Header().Set("X-Phonon-Group", phoneNode.Group)
	w.Header().Set("X-Phonon-Queue-Depth", fmt.Sprintf("%d", phoneNode.Telemetry.QueueDepth))

	// Build OpenAI-compatible response
	promptText := buildPrompt(req.Messages)
	promptTokens := estimateTokens(promptText)
	completionTokens := inferResp.Tokens
	if completionTokens <= 0 {
		completionTokens = estimateTokens(inferResp.Text)
	}

	resp := ChatCompletionResponse{
		ID:      generateCompletionID(),
		Object:  "chat.completion",
		Created: time.Now().Unix(),
		Model:   req.Model,
		Choices: []Choice{
			{
				Index: 0,
				Message: Message{
					Role:    "assistant",
					Content: inferResp.Text,
				},
				FinishReason: "stop",
			},
		},
		Usage: Usage{
			PromptTokens:     promptTokens,
			CompletionTokens: completionTokens,
			TotalTokens:      promptTokens + completionTokens,
		},
	}

	writeJSON(w, http.StatusOK, resp)
}

// selectPhone finds the healthiest online phone with the requested model loaded.
// Candidates are sorted by: queue depth (asc) → temperature (asc) → battery (desc).
func (h *OpenAIHandler) selectPhone(modelName string) (string, registry.Node, error) {
	nodes := h.reg.List()

	candidates := make([]registry.Node, 0)
	for _, node := range nodes {
		if node.State != registry.NodeStateOnline {
			continue
		}
		if node.ModelStatus.Loaded && node.ModelStatus.Name == modelName {
			// Check backpressure: skip phones at capacity
			if h.maxQueuePerNode > 0 && node.Telemetry.QueueDepth >= h.maxQueuePerNode {
				continue
			}
			candidates = append(candidates, node)
		}
	}

	if len(candidates) == 0 {
		// Check if the model is loaded somewhere but all nodes are at capacity
		for _, node := range nodes {
			if node.State == registry.NodeStateOnline && node.ModelStatus.Loaded && node.ModelStatus.Name == modelName {
				return "", registry.Node{}, fmt.Errorf("all nodes at capacity (queue depth >= %d)", h.maxQueuePerNode)
			}
		}
		return "", registry.Node{}, fmt.Errorf("no online node has model %q loaded", modelName)
	}

	// Sort by health: least queue depth, coolest temperature, most battery
	slices.SortFunc(candidates, func(a, b registry.Node) int {
		// Queue depth (ascending — lower is better)
		if a.Telemetry.QueueDepth != b.Telemetry.QueueDepth {
			if a.Telemetry.QueueDepth < b.Telemetry.QueueDepth {
				return -1
			}
			return 1
		}
		// Temperature (ascending — lower is better)
		if a.Telemetry.ThermalTempC != b.Telemetry.ThermalTempC {
			if a.Telemetry.ThermalTempC < b.Telemetry.ThermalTempC {
				return -1
			}
			return 1
		}
		// Battery level (descending — higher is better)
		if a.Telemetry.BatteryLevel != b.Telemetry.BatteryLevel {
			if a.Telemetry.BatteryLevel > b.Telemetry.BatteryLevel {
				return -1
			}
			return 1
		}
		return 0
	})

	selected := candidates[0]
	return selected.IPAddress, selected, nil
}

// Default port for the sidecar's InferenceServer. Must match sidecar/app/.../InferenceServer.kt.
const defaultInferencePort = 9876

// defaultInferenceProxy sends an inference request to a phone's local endpoint.
func (h *OpenAIHandler) defaultInferenceProxy(phoneURL string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
	// Phone inference is not yet implemented on the sidecar side.
	// This returns a placeholder response for development.
	return &PhoneInferenceResponse{
		Text:     "Inference endpoint not yet available. Phone URL: " + phoneURL,
		Tokens:   10,
		Duration: 0,
	}, nil
}

func (h *OpenAIHandler) handleStreamingChatCompletion(w http.ResponseWriter, r *http.Request, req ChatCompletionRequest) {
	// Set defaults
	if req.MaxTokens <= 0 {
		req.MaxTokens = 2048
	}
	if req.Temperature == 0 {
		req.Temperature = 0.7
	}

	// Select a phone
	phone, phoneNode, err := h.selectPhone(req.Model)
	if err != nil {
		h.log.Warn("no available phone for streaming", "model", req.Model, "error", err)
		if strings.Contains(err.Error(), "at capacity") {
			w.Header().Set("Retry-After", "5")
			writeJSON(w, http.StatusTooManyRequests, map[string]any{
				"error": map[string]string{
					"message": "all inference nodes are busy, try again later",
					"type":    "rate_limit_error",
				},
			})
		} else {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"error": map[string]string{
					"message": fmt.Sprintf("no available phone with model %q loaded", req.Model),
					"type":    "server_error",
				},
			})
		}
		return
	}

	phoneURL := fmt.Sprintf("http://%s:%d/v1/chat/completions", phone, defaultInferencePort)
	completionID := generateCompletionID()

	// SSE headers
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Phonon-Device", phoneNode.DeviceID)
	w.Header().Set("X-Phonon-Group", phoneNode.Group)
	w.Header().Set("X-Phonon-Queue-Depth", fmt.Sprintf("%d", phoneNode.Telemetry.QueueDepth))

	flusher, ok := w.(http.Flusher)
	if !ok {
		h.log.Error("streaming not supported by ResponseWriter")
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"error": map[string]string{
				"message": "streaming not supported",
				"type":    "server_error",
			},
		})
		return
	}

	// Role stanza
	roleChunk := fmt.Sprintf(`data: {"id":"%s","object":"chat.completion.chunk","created":%d,"model":"%s","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}`,
		completionID, time.Now().Unix(), req.Model)
	fmt.Fprintf(w, "%s\n\n", roleChunk)
	flusher.Flush()

	// Stream content from phone
	fullText, err := h.streamInferenceProxy(phoneURL, PhoneInferenceRequest{
		Model:       req.Model,
		Messages:    req.Messages,
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
	}, func(content string) {
		chunk := fmt.Sprintf(`data: {"id":"%s","object":"chat.completion.chunk","created":%d,"model":"%s","choices":[{"index":0,"delta":{"content":%s},"finish_reason":null}]}`,
			completionID, time.Now().Unix(), req.Model, jsonString(content))
		fmt.Fprintf(w, "%s\n\n", chunk)
		flusher.Flush()
	})

	if err != nil {
		h.log.Error("phone streaming inference failed", "phone", phoneURL, "error", err)
		// Write error as an SSE event so the client can handle it
		errChunk := fmt.Sprintf(`data: {"error":"inference failed: %s"}`,
			escapeJSON(err.Error()))
		fmt.Fprintf(w, "%s\n\n", errChunk)
		fmt.Fprintf(w, "data: [DONE]\n\n")
		flusher.Flush()
		return
	}

	// Final stanza with usage and finish_reason
	tokens := estimateTokens(fullText)
	finalChunk := fmt.Sprintf(`data: {"id":"%s","object":"chat.completion.chunk","created":%d,"model":"%s","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":0,"completion_tokens":%d,"total_tokens":%d}}`,
		completionID, time.Now().Unix(), req.Model, tokens, tokens)
	fmt.Fprintf(w, "%s\n\n", finalChunk)

	// End-of-stream marker
	fmt.Fprintf(w, "data: [DONE]\n\n")
	flusher.Flush()
}

// defaultStreamInferenceProxy is a placeholder that simulates a streaming response.
func (h *OpenAIHandler) defaultStreamInferenceProxy(phoneURL string, req PhoneInferenceRequest, onChunk func(string)) (string, error) {
	// Simulate streaming tokens for development
	placeholder := fmt.Sprintf("Simulated streaming response from %s. Model: %s.", phoneURL, req.Model)
	words := strings.Fields(placeholder)
	for _, word := range words {
		onChunk(word + " ")
	}
	return placeholder, nil
}

// jsonString returns a valid JSON string literal (quoted and escaped).
func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// escapeJSON escapes a string for embedding in a JSON value (no surrounding quotes).
func escapeJSON(s string) string {
	b, _ := json.Marshal(s)
	// Strip surrounding quotes
	if len(b) >= 2 && b[0] == '"' && b[len(b)-1] == '"' {
		return string(b[1 : len(b)-1])
	}
	return string(b)
}

// --- Helpers ---

func buildPrompt(messages []Message) string {
	var b strings.Builder
	for _, msg := range messages {
		b.WriteString(msg.Role)
		b.WriteString(": ")
		b.WriteString(msg.Content)
		b.WriteString("\n")
	}
	return b.String()
}

// estimateTokens does a rough character-based token estimate (~4 chars per token).
func estimateTokens(text string) int {
	return (len(text) + 3) / 4
}

var (
	completionMu sync.Mutex
	completionID int64 = rand.Int63n(1 << 48) // random offset per boot avoids collisions after restart
)

func generateCompletionID() string {
	completionMu.Lock()
	defer completionMu.Unlock()
	completionID++
	return fmt.Sprintf("chatcmpl-%08x%08x", time.Now().Unix(), completionID)
}
