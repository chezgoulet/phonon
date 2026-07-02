// Package api provides HTTP handlers for the coordinator REST API.
package api

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand"
	"net"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/health"
	phononlog "github.com/chezgoulet/phonon/internal/log"
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
	Stream      bool      `json:"stream,omitempty"`

	// TimeoutMs is the per-request inference deadline in milliseconds,
	// derived from the client's max_tokens (see computeInferenceTimeout).
	// It is sent to the phone for informational/server-side enforcement
	// and enforced client-side by the coordinator's proxies.
	TimeoutMs int `json:"timeout_ms,omitempty"`

	// TraceID correlates the phone request with the coordinator-side
	// request trace (see TraceMiddleware).
	TraceID string `json:"trace_id,omitempty"`

	// AuthToken is the per-device secret established at pairing. It is
	// sent to the phone as an Authorization header (never in the JSON
	// body) so the phone can verify the request came from its paired
	// coordinator. Populated via the WithDeviceTokenLookup option.
	AuthToken string `json:"-"`
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

	// deviceTokenLookup returns the pairing auth token for a device so
	// inference requests to the phone can be authenticated. Nil or ""
	// results omit the Authorization header (the phone will refuse).
	deviceTokenLookup func(deviceID string) string

	// breaker gates routing to devices with recent inference failures.
	breaker CircuitBreaker

	// inferencePort is the sidecar inference server port (default 9876,
	// configurable via cluster.inference_port).
	inferencePort int

	// events receives inference lifecycle events (nil = disabled).
	events *phononlog.EventLog

	// metrics receives inference latency/error metrics (nil = disabled).
	metrics *health.Metrics
}

// NewOpenAIHandler creates an OpenAI-compatible handler.
func NewOpenAIHandler(reg *registry.Registry, opts ...OpenAIOption) *OpenAIHandler {
	h := &OpenAIHandler{
		reg:             reg,
		log:             slog.With("component", "openai"),
		maxQueuePerNode: 3, // default
		inferencePort:   defaultInferencePort,
	}
	for _, opt := range opts {
		opt(h)
	}
	if h.breaker == nil {
		h.breaker = NewDeviceCircuitBreaker()
	}
	// Defaults only when no option supplied a proxy — previously the
	// defaults unconditionally overwrote WithInferenceProxy /
	// WithStreamInferenceProxy options.
	if h.inferenceProxy == nil {
		h.inferenceProxy = h.defaultInferenceProxy
	}
	if h.streamInferenceProxy == nil {
		h.streamInferenceProxy = h.defaultStreamInferenceProxy
	}
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

// WithDeviceTokenLookup wires the pairing manager's token lookup so the
// coordinator can authenticate itself to phones on inference requests.
// fn returns the per-device auth token, or "" for unpaired devices.
func WithDeviceTokenLookup(fn func(deviceID string) string) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.deviceTokenLookup = fn
	}
}

// WithCircuitBreaker overrides the default per-device circuit breaker.
func WithCircuitBreaker(cb CircuitBreaker) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.breaker = cb
	}
}

// WithInferencePort sets the sidecar inference server port used when
// building phone inference URLs. Values <= 0 keep the default (9876).
func WithInferencePort(port int) OpenAIOption {
	return func(h *OpenAIHandler) {
		if port > 0 {
			h.inferencePort = port
		}
	}
}

// WithEventLog wires the cluster event log so inference lifecycle events
// (started/routed/result/failed/retried) are recorded with trace IDs.
func WithEventLog(el *phononlog.EventLog) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.events = el
	}
}

// WithMetrics wires the Prometheus metrics instance (from the health
// monitor's registry) so inference latency, throughput, errors, and
// retries are recorded.
func WithMetrics(m *health.Metrics) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.metrics = m
	}
}

// logEvent writes an inference lifecycle event with structured fields
// serialized into the details column. No-op when no event log is wired.
func (h *OpenAIHandler) logEvent(t phononlog.EventType, deviceID string, sev phononlog.Severity, traceID string, fields map[string]any) {
	if h.events == nil {
		return
	}
	details := ""
	if len(fields) > 0 {
		if b, err := json.Marshal(fields); err == nil {
			details = string(b)
		}
	}
	if err := h.events.WriteEvent(phononlog.Event{
		Type:     t,
		DeviceID: deviceID,
		Severity: sev,
		Details:  details,
		TraceID:  traceID,
	}); err != nil {
		h.log.Warn("event log write failed", "event_type", t, "error", err)
	}
}

// classifyInferenceError maps a proxy error to the error_type label of
// phonon_inference_errors_total.
func classifyInferenceError(err error) string {
	switch {
	case err == nil:
		return ""
	case isTimeoutErr(err):
		return "timeout"
	case strings.Contains(err.Error(), "phone returned HTTP"):
		return "phone_error"
	default:
		return "connection"
	}
}

// recordInferenceError bumps the error counter. No-op without metrics.
func (h *OpenAIHandler) recordInferenceError(errType string) {
	if h.metrics != nil {
		h.metrics.InferenceErrors.WithLabelValues(errType).Inc()
	}
}

// recordInferenceSuccess observes duration and throughput histograms.
func (h *OpenAIHandler) recordInferenceSuccess(duration time.Duration, completionTokens int) {
	if h.metrics == nil {
		return
	}
	ms := float64(duration) / float64(time.Millisecond)
	h.metrics.InferenceDuration.Observe(ms)
	if secs := duration.Seconds(); secs > 0 && completionTokens > 0 {
		h.metrics.InferenceTokensPerSecond.Observe(float64(completionTokens) / secs)
	}
}

// trackActiveRequest increments the active-requests gauge and returns the
// matching decrement, for use with defer.
func (h *OpenAIHandler) trackActiveRequest() func() {
	if h.metrics == nil {
		return func() {}
	}
	h.metrics.RequestsActive.Inc()
	return h.metrics.RequestsActive.Dec
}

// WithInferenceProxy overrides the default inference proxy with a custom
// implementation. Useful for testing or wiring real phone inference.
func WithInferenceProxy(fn func(phoneURL string, req PhoneInferenceRequest) (*PhoneInferenceResponse, error)) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.inferenceProxy = fn
	}
}

// WithStreamInferenceProxy overrides the default streaming inference proxy
// with a custom implementation.
func WithStreamInferenceProxy(fn func(phoneURL string, req PhoneInferenceRequest, onChunk func(string)) (string, error)) OpenAIOption {
	return func(h *OpenAIHandler) {
		h.streamInferenceProxy = fn
	}
}

// RegisterRoutes adds OpenAI-compatible endpoints to the given mux.
//
// Routes are registered both at the OpenAI-standard /v1/ prefix and at
// /api/v1/ — the coordinator mounts the protected mux under /api/v1/
// without stripping the prefix, so the aliases make the endpoints
// reachable there while /v1/ remains the documented client-facing path.
func (h *OpenAIHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/chat/completions", h.handleChatCompletion)
	mux.HandleFunc("GET /v1/models", h.handleListModels)
	mux.HandleFunc("POST /api/v1/chat/completions", h.handleChatCompletion)
	mux.HandleFunc("GET /api/v1/models", h.handleListModels)
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
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
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
		h.handleStreamingChatCompletion(w, r, &req)
		return
	}

	// Set defaults
	if req.MaxTokens <= 0 {
		req.MaxTokens = 2048
	}
	if req.Temperature == 0 {
		req.Temperature = 0.7
	}

	timeout := computeInferenceTimeout(req.MaxTokens)
	traceID := TraceIDFromContext(r.Context())
	defer h.trackActiveRequest()()

	h.logEvent(phononlog.EventInferenceStarted, "", phononlog.SeverityInfo, traceID, map[string]any{
		"model":         req.Model,
		"prompt_tokens": estimateTokens(buildPrompt(req.Messages)),
		"stream":        false,
	})

	// Retry with fallback: original attempt + 1 retry on the
	// next-healthiest phone. Failed devices are excluded from the
	// fallback selection and recorded in the circuit breaker.
	const maxAttempts = 2
	exclude := make(map[string]bool, maxAttempts)

	var (
		inferResp  *PhoneInferenceResponse
		phoneNode  registry.Node
		lastErr    error
		lastDevice string
	)

	for attempt := 0; attempt < maxAttempts; attempt++ {
		phone, node, err := h.selectPhoneExcluding(req.Model, exclude)
		if err != nil {
			if attempt == 0 {
				h.log.Warn("no available phone for inference", "model", req.Model, "error", err)
				if strings.Contains(err.Error(), "circuit-broken") {
					h.recordInferenceError("circuit_breaker")
				}
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
			// A previous attempt failed and no fallback phone exists —
			// report the original failure below.
			break
		}

		if attempt > 0 {
			if h.metrics != nil {
				h.metrics.InferenceRetries.Inc()
			}
			h.logEvent(phononlog.EventInferenceRetried, node.DeviceID, phononlog.SeverityWarning, traceID, map[string]any{
				"from_device_id": lastDevice,
				"to_device_id":   node.DeviceID,
			})
		}

		h.logEvent(phononlog.EventInferenceRouted, node.DeviceID, phononlog.SeverityInfo, traceID, map[string]any{
			"device_id":   node.DeviceID,
			"queue_depth": node.Telemetry.QueueDepth,
		})

		phoneURL := h.phoneInferenceURL(phone)
		authToken := ""
		if h.deviceTokenLookup != nil {
			authToken = h.deviceTokenLookup(node.DeviceID)
		}

		attemptStart := time.Now()
		resp, err := h.inferenceProxy(phoneURL, PhoneInferenceRequest{
			Model:       req.Model,
			Messages:    req.Messages,
			Temperature: req.Temperature,
			MaxTokens:   req.MaxTokens,
			TimeoutMs:   int(timeout / time.Millisecond),
			TraceID:     traceID,
			AuthToken:   authToken,
		})
		if err != nil {
			h.breaker.RecordFailure(node.DeviceID)
			exclude[node.DeviceID] = true
			lastErr = err
			lastDevice = node.DeviceID
			h.recordInferenceError(classifyInferenceError(err))
			h.logEvent(phononlog.EventInferenceFailed, node.DeviceID, phononlog.SeverityError, traceID, map[string]any{
				"device_id": node.DeviceID,
				"error":     err.Error(),
			})
			h.log.Error("phone inference failed",
				"phone", phoneURL, "device_id", node.DeviceID,
				"attempt", attempt+1, "error", err)
			continue
		}

		h.breaker.RecordSuccess(node.DeviceID)
		duration := time.Since(attemptStart)
		completionTok := resp.Tokens
		if completionTok <= 0 {
			completionTok = estimateTokens(resp.Text)
		}
		h.recordInferenceSuccess(duration, completionTok)
		h.logEvent(phononlog.EventInferenceResult, node.DeviceID, phononlog.SeverityInfo, traceID, map[string]any{
			"device_id":         node.DeviceID,
			"completion_tokens": completionTok,
			"duration_ms":       duration.Milliseconds(),
		})
		inferResp = resp
		phoneNode = node
		break
	}

	if inferResp == nil {
		if isTimeoutErr(lastErr) {
			w.Header().Set("X-Phonon-Error", "timeout")
			writeJSON(w, http.StatusGatewayTimeout, map[string]any{
				"error": map[string]string{
					"message": "phone inference timed out: " + lastErr.Error(),
					"type":    "timeout_error",
				},
			})
			return
		}
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error": map[string]string{
				"message": "phone inference failed: " + lastErr.Error(),
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
	return h.selectPhoneExcluding(modelName, nil)
}

// selectPhoneExcluding is selectPhone with an exclusion set (device IDs of
// phones that already failed this request) used for retry fallback.
// Devices with an open circuit breaker are skipped; for half-open devices,
// selection claims the single probe slot — callers must send the request
// and record the outcome via the breaker.
func (h *OpenAIHandler) selectPhoneExcluding(modelName string, exclude map[string]bool) (string, registry.Node, error) {
	nodes := h.reg.List()

	candidates := make([]registry.Node, 0)
	for i := range nodes {
		node := &nodes[i]
		if node.State != registry.NodeStateOnline {
			continue
		}
		if exclude[node.DeviceID] {
			continue
		}
		if node.ModelStatus.Loaded && node.ModelStatus.Name == modelName {
			// Check backpressure: skip phones at capacity
			if h.maxQueuePerNode > 0 && node.Telemetry.QueueDepth >= h.maxQueuePerNode {
				continue
			}
			candidates = append(candidates, *node)
		}
	}

	if len(candidates) == 0 {
		// Check if the model is loaded somewhere but all nodes are at capacity
		for i := range nodes {
			node := &nodes[i]
			if node.State == registry.NodeStateOnline && node.ModelStatus.Loaded && node.ModelStatus.Name == modelName && !exclude[node.DeviceID] {
				return "", registry.Node{}, fmt.Errorf("all nodes at capacity (queue depth >= %d)", h.maxQueuePerNode)
			}
		}
		return "", registry.Node{}, fmt.Errorf("no online node has model %q loaded", modelName)
	}

	// Sort by health: least queue depth, coolest temperature, most battery.
	// Backpressure weighting: a phone whose reported queue depth exceeds
	// 50% of maxQueuePerNode has its routing weight halved — modeled here
	// as a doubled effective queue depth — so load spreads to less-busy
	// phones before a node saturates.
	slices.SortFunc(candidates, func(a, b registry.Node) int {
		aq := h.effectiveQueueDepth(a.Telemetry.QueueDepth)
		bq := h.effectiveQueueDepth(b.Telemetry.QueueDepth)
		// Effective queue depth (ascending — lower is better)
		if aq != bq {
			if aq < bq {
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

	// Pick the healthiest candidate whose circuit breaker admits traffic.
	// Allow() is side-effect free for closed breakers; for half-open ones
	// it claims the single probe slot.
	for i := range candidates {
		if h.breaker.Allow(candidates[i].DeviceID) {
			return candidates[i].IPAddress, candidates[i], nil
		}
	}
	return "", registry.Node{}, fmt.Errorf("all candidate nodes for model %q are circuit-broken", modelName)
}

// effectiveQueueDepth applies the backpressure weighting: queue depths
// above 50% of maxQueuePerNode count double, halving that phone's routing
// weight relative to less-loaded peers.
func (h *OpenAIHandler) effectiveQueueDepth(queueDepth int) int {
	if h.maxQueuePerNode > 0 && 2*queueDepth > h.maxQueuePerNode {
		return queueDepth * 2
	}
	return queueDepth
}

// phoneInferenceURL builds the sidecar inference endpoint URL for a phone IP.
func (h *OpenAIHandler) phoneInferenceURL(ip string) string {
	port := h.inferencePort
	if port <= 0 {
		port = defaultInferencePort
	}
	return fmt.Sprintf("http://%s:%d/v1/chat/completions", ip, port)
}

// computeInferenceTimeout derives the per-request inference deadline from
// the requested max_tokens: 50ms per token plus a 10s base allowance for
// model warm-up and prompt processing.
func computeInferenceTimeout(maxTokens int) time.Duration {
	if maxTokens <= 0 {
		maxTokens = 2048
	}
	return time.Duration(maxTokens)*50*time.Millisecond + 10*time.Second
}

// isTimeoutErr reports whether an inference proxy error was caused by the
// request deadline (context deadline or a net-level timeout).
func isTimeoutErr(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}

// Default port for the sidecar's InferenceServer. Must match sidecar/app/.../InferenceServer.kt.
const defaultInferencePort = 9876

// Note: the inference URL passed to the proxies already includes the
// sidecar's OpenAI-compatible path ("/v1/chat/completions"), built at the
// call sites in handleChatCompletion / handleStreamingChatCompletion.
// Must match sidecar/app/.../InferenceServer.kt.

// InferenceHTTPClient is the shared HTTP client for outbound inference requests.
// Exposed as a variable so tests can override it with a short-lived client.
var InferenceHTTPClient = &http.Client{
	Timeout: 120 * time.Second,
}

// defaultInferenceProxy sends an inference request to a phone's local endpoint
// and returns the parsed response.
func (h *OpenAIHandler) defaultInferenceProxy(phoneURL string, req PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
	payload, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal inference request: %w", err)
	}

	ctx := context.Background()
	if req.TimeoutMs > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(req.TimeoutMs)*time.Millisecond)
		defer cancel()
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, phoneURL, strings.NewReader(string(payload)))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-Phonon-Proxy", "coordinator")
	if req.AuthToken != "" {
		httpReq.Header.Set("Authorization", "Bearer "+req.AuthToken)
	}

	resp, err := InferenceHTTPClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("phone request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("phone returned HTTP %d: %s", resp.StatusCode, string(body))
	}

	var phoneResp PhoneInferenceResponse
	if err := json.NewDecoder(resp.Body).Decode(&phoneResp); err != nil {
		return nil, fmt.Errorf("decode phone response: %w", err)
	}

	return &phoneResp, nil
}

func (h *OpenAIHandler) handleStreamingChatCompletion(w http.ResponseWriter, r *http.Request, req *ChatCompletionRequest) {
	// Set defaults
	if req.MaxTokens <= 0 {
		req.MaxTokens = 2048
	}
	if req.Temperature == 0 {
		req.Temperature = 0.7
	}

	timeout := computeInferenceTimeout(req.MaxTokens)
	traceID := TraceIDFromContext(r.Context())
	defer h.trackActiveRequest()()

	h.logEvent(phononlog.EventInferenceStarted, "", phononlog.SeverityInfo, traceID, map[string]any{
		"model":         req.Model,
		"prompt_tokens": estimateTokens(buildPrompt(req.Messages)),
		"stream":        true,
	})

	// Select the first phone before committing to SSE so selection
	// failures can still return a regular JSON error status.
	phone, phoneNode, err := h.selectPhoneExcluding(req.Model, nil)
	if err != nil {
		h.log.Warn("no available phone for streaming", "model", req.Model, "error", err)
		if strings.Contains(err.Error(), "circuit-broken") {
			h.recordInferenceError("circuit_breaker")
		}
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

	completionID := generateCompletionID()

	// SSE headers
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Phonon-Device", phoneNode.DeviceID)
	w.Header().Set("X-Phonon-Group", phoneNode.Group)
	w.Header().Set("X-Phonon-Queue-Depth", fmt.Sprintf("%d", phoneNode.Telemetry.QueueDepth))

	// Role stanza
	roleChunk := fmt.Sprintf(`data: {"id":%q,"object":"chat.completion.chunk","created":%d,"model":%q,"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}`,
		completionID, time.Now().Unix(), req.Model)
	fmt.Fprintf(w, "%s\n\n", roleChunk)
	flusher.Flush()

	// Stream from the phone with fallback: if the connection drops (before
	// or mid-stream), retry once on the next-healthiest phone. Phones are
	// stateless, so the fallback regenerates from scratch; the prefix that
	// was already delivered to the client is skipped (best-effort — sampling
	// is nondeterministic, so the splice may not be seamless).
	const maxAttempts = 2
	exclude := make(map[string]bool, maxAttempts)
	var accumulated strings.Builder
	var lastErr error
	var lastDevice string

	for attempt := 0; attempt < maxAttempts; attempt++ {
		if attempt > 0 {
			phone, phoneNode, err = h.selectPhoneExcluding(req.Model, exclude)
			if err != nil {
				lastErr = fmt.Errorf("%w (no fallback phone: %v)", lastErr, err)
				break
			}
			if h.metrics != nil {
				h.metrics.InferenceRetries.Inc()
			}
			h.logEvent(phononlog.EventInferenceRetried, phoneNode.DeviceID, phononlog.SeverityWarning, traceID, map[string]any{
				"from_device_id": lastDevice,
				"to_device_id":   phoneNode.DeviceID,
			})
			h.log.Info("streaming failover", "to_device", phoneNode.DeviceID, "delivered_bytes", accumulated.Len())
		}

		h.logEvent(phononlog.EventInferenceRouted, phoneNode.DeviceID, phononlog.SeverityInfo, traceID, map[string]any{
			"device_id":   phoneNode.DeviceID,
			"queue_depth": phoneNode.Telemetry.QueueDepth,
		})

		phoneURL := h.phoneInferenceURL(phone)
		authToken := ""
		if h.deviceTokenLookup != nil {
			authToken = h.deviceTokenLookup(phoneNode.DeviceID)
		}

		// Bytes already delivered to the client that the fallback phone's
		// regenerated output must skip. Byte-based (not rune-based): the
		// regenerated text differs anyway, so a mid-rune boundary is no
		// worse than the inherent nondeterminism.
		skip := accumulated.Len()

		attemptStart := time.Now()
		_, err = h.streamInferenceProxy(phoneURL, PhoneInferenceRequest{
			Model:       req.Model,
			Messages:    req.Messages,
			Temperature: req.Temperature,
			MaxTokens:   req.MaxTokens,
			Stream:      true,
			TimeoutMs:   int(timeout / time.Millisecond),
			TraceID:     traceID,
			AuthToken:   authToken,
		}, func(content string) {
			if skip > 0 {
				if len(content) <= skip {
					skip -= len(content)
					return
				}
				content = content[skip:]
				skip = 0
			}
			accumulated.WriteString(content)
			chunk := fmt.Sprintf(`data: {"id":%q,"object":"chat.completion.chunk","created":%d,"model":%q,"choices":[{"index":0,"delta":{"content":%s},"finish_reason":null}]}`,
				completionID, time.Now().Unix(), req.Model, jsonString(content))
			fmt.Fprintf(w, "%s\n\n", chunk)
			flusher.Flush()
		})

		if err != nil {
			h.breaker.RecordFailure(phoneNode.DeviceID)
			exclude[phoneNode.DeviceID] = true
			lastErr = err
			lastDevice = phoneNode.DeviceID
			h.recordInferenceError(classifyInferenceError(err))
			h.logEvent(phononlog.EventInferenceFailed, phoneNode.DeviceID, phononlog.SeverityError, traceID, map[string]any{
				"device_id": phoneNode.DeviceID,
				"error":     err.Error(),
			})
			h.log.Error("phone streaming inference failed",
				"phone", phoneURL, "device_id", phoneNode.DeviceID,
				"attempt", attempt+1, "error", err)
			continue
		}

		h.breaker.RecordSuccess(phoneNode.DeviceID)
		duration := time.Since(attemptStart)
		completionTok := estimateTokens(accumulated.String())
		h.recordInferenceSuccess(duration, completionTok)
		h.logEvent(phononlog.EventInferenceResult, phoneNode.DeviceID, phononlog.SeverityInfo, traceID, map[string]any{
			"device_id":         phoneNode.DeviceID,
			"completion_tokens": completionTok,
			"duration_ms":       duration.Milliseconds(),
		})
		lastErr = nil
		break
	}

	if lastErr != nil {
		// All attempts failed. The partial text already streamed is echoed
		// in the error metadata so clients can distinguish a truncated
		// response from a complete one.
		errPayload, _ := json.Marshal(map[string]any{
			"error": map[string]any{
				"message": fmt.Sprintf("inference failed: %s", lastErr.Error()),
				"type":    "inference_error",
				"metadata": map[string]any{
					"partial":      accumulated.Len() > 0,
					"partial_text": accumulated.String(),
				},
			},
		})
		fmt.Fprintf(w, "data: %s\n\n", errPayload)
		fmt.Fprintf(w, "data: [DONE]\n\n")
		flusher.Flush()
		return
	}

	// Final stanza with usage and finish_reason
	tokens := estimateTokens(accumulated.String())
	finalChunk := fmt.Sprintf(`data: {"id":%q,"object":"chat.completion.chunk","created":%d,"model":%q,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":0,"completion_tokens":%d,"total_tokens":%d}}`,
		completionID, time.Now().Unix(), req.Model, tokens, tokens)
	fmt.Fprintf(w, "%s\n\n", finalChunk)

	// End-of-stream marker
	fmt.Fprintf(w, "data: [DONE]\n\n")
	flusher.Flush()
}

// StreamingHTTPClient is the shared HTTP client for outbound *streaming*
// inference requests. Unlike InferenceHTTPClient it has no global timeout —
// a healthy stream may legitimately run for minutes. The overall deadline
// comes from the request context (PhoneInferenceRequest.TimeoutMs) and
// stalled phones are detected by the per-chunk timeout below.
var StreamingHTTPClient = &http.Client{}

// streamChunkTimeout is the maximum silence tolerated between reads of the
// phone's SSE stream before the phone is treated as stalled. The sidecar
// emits keepalive comments every 2s while generating, so 5s of silence
// means the phone (or the network path to it) is gone.
const streamChunkTimeout = 5 * time.Second

// defaultStreamInferenceProxy sends a streaming inference request to a phone
// and feeds each SSE content delta to the onChunk callback. It reads the
// response incrementally (no buffering of the full body), forwards deltas as
// they arrive, honors the phone's [DONE] marker, and fails if the stream
// stalls for more than streamChunkTimeout.
func (h *OpenAIHandler) defaultStreamInferenceProxy(phoneURL string, req PhoneInferenceRequest, onChunk func(string)) (string, error) {
	payload, err := json.Marshal(req)
	if err != nil {
		return "", fmt.Errorf("marshal inference request: %w", err)
	}

	ctx := context.Background()
	if req.TimeoutMs > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(req.TimeoutMs)*time.Millisecond)
		defer cancel()
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, phoneURL, strings.NewReader(string(payload)))
	if err != nil {
		return "", fmt.Errorf("create request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-Phonon-Proxy", "coordinator")
	if req.AuthToken != "" {
		httpReq.Header.Set("Authorization", "Bearer "+req.AuthToken)
	}
	httpReq.Header.Set("Accept", "text/event-stream")

	resp, err := StreamingHTTPClient.Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("phone streaming request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return "", fmt.Errorf("phone returned HTTP %d: %s", resp.StatusCode, string(body))
	}

	// Pump lines from the body in a goroutine so the consumer loop can
	// enforce the per-chunk stall timeout with a select.
	lines := make(chan string)
	scanErr := make(chan error, 1)
	done := make(chan struct{})
	defer close(done)

	go func() {
		defer close(lines)
		scanner := bufio.NewScanner(resp.Body)
		for scanner.Scan() {
			select {
			case lines <- scanner.Text():
			case <-done:
				return
			}
		}
		scanErr <- scanner.Err()
	}()

	var fullText strings.Builder
	timer := time.NewTimer(streamChunkTimeout)
	defer timer.Stop()

	for {
		select {
		case line, ok := <-lines:
			if !ok {
				// Stream ended without [DONE] — surface any read error.
				select {
				case err := <-scanErr:
					if err != nil {
						return fullText.String(), fmt.Errorf("stream read error: %w", err)
					}
				default:
				}
				return fullText.String(), nil
			}
			timer.Reset(streamChunkTimeout)

			if !strings.HasPrefix(line, "data: ") {
				continue // SSE comments (keepalives) and blank lines
			}
			data := strings.TrimPrefix(line, "data: ")
			if data == "[DONE]" {
				return fullText.String(), nil
			}
			// Parse SSE JSON chunk for content delta
			var chunk struct {
				Choices []struct {
					Delta struct {
						Content string `json:"content"`
					} `json:"delta"`
				} `json:"choices"`
			}
			if err := json.Unmarshal([]byte(data), &chunk); err != nil {
				continue // skip malformed chunks
			}
			for _, choice := range chunk.Choices {
				if choice.Delta.Content != "" {
					onChunk(choice.Delta.Content)
					fullText.WriteString(choice.Delta.Content)
				}
			}

		case <-timer.C:
			// No bytes from the phone within the chunk timeout.
			resp.Body.Close() // unblocks the scanner goroutine
			return fullText.String(), fmt.Errorf("phone stream stalled: no data for %s: %w",
				streamChunkTimeout, context.DeadlineExceeded)
		}
	}
}

// jsonString returns a valid JSON string literal (quoted and escaped).
func jsonString(s string) string {
	b, _ := json.Marshal(s)
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
