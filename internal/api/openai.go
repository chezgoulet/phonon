// Package api provides HTTP handlers for the coordinator REST API.
package api

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"math/rand"
	"net/http"
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
}

// NewOpenAIHandler creates an OpenAI-compatible handler.
func NewOpenAIHandler(reg *registry.Registry) *OpenAIHandler {
	h := &OpenAIHandler{
		reg: reg,
		log: slog.With("component", "openai"),
	}
	h.inferenceProxy = h.defaultInferenceProxy
	return h
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

	// Streaming not yet supported
	if req.Stream {
		writeJSON(w, http.StatusNotImplemented, map[string]any{
			"error": map[string]string{
				"message": "streaming is not yet supported",
				"type":    "not_implemented",
			},
		})
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
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"error": map[string]string{
				"message": fmt.Sprintf("no available phone with model %q loaded", req.Model),
				"type":    "server_error",
			},
		})
		return
	}

	// Build inference URL from phone's address
	phoneURL := fmt.Sprintf("http://%s/infer", phone)

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

// selectPhone finds an online phone with the requested model loaded.
func (h *OpenAIHandler) selectPhone(modelName string) (string, *registry.Node, error) {
	nodes := h.reg.List()

	var candidates []*registry.Node
	for _, node := range nodes {
		if node.State != registry.NodeStateOnline {
			continue
		}
		if node.ModelStatus.Loaded && node.ModelStatus.Name == modelName {
			candidates = append(candidates, node)
		}
	}

	if len(candidates) == 0 {
		return "", nil, fmt.Errorf("no online node has model %q loaded", modelName)
	}

	// Random selection (weighted by load later)
	selected := candidates[rand.Intn(len(candidates))]
	return selected.IPAddress, selected, nil
}

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
	completionID int64
)

func generateCompletionID() string {
	completionMu.Lock()
	defer completionMu.Unlock()
	completionID++
	return fmt.Sprintf("chatcmpl-%08x%08x", time.Now().Unix(), completionID)
}
