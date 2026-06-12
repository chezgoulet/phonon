package api

import (
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/chezgoulet/phonon/internal/config"
	"github.com/chezgoulet/phonon/internal/model"
	"github.com/chezgoulet/phonon/internal/registry"
)

// PreflightHandler performs readiness checks for group activation.
// Before a group can serve traffic, all phones must be registered, online,
// and have the model cached. For shard groups, inter-node latency
// validation is performed when the upstream runtime supports it.
type PreflightHandler struct {
	reg   *registry.Registry
	cache *model.Cache
	cfg   *config.Config
	log   *slog.Logger
}

// NewPreflightHandler creates a new pre-flight readiness handler.
func NewPreflightHandler(reg *registry.Registry, cache *model.Cache, cfg *config.Config) *PreflightHandler {
	return &PreflightHandler{
		reg:   reg,
		cache: cache,
		cfg:   cfg,
		log:   slog.With("component", "preflight"),
	}
}

// RegisterRoutes adds pre-flight endpoints to the given mux.
func (h *PreflightHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/cluster/preflight", h.handlePreflight)
}

// PreflightResponse is the full pre-flight report.
type PreflightResponse struct {
	Overall   string                     `json:"overall"`   // "ready", "not_ready"
	Timestamp time.Time                  `json:"timestamp"`
	Groups    map[string]GroupCheckResult `json:"groups"`
	Errors    []string                   `json:"errors,omitempty"`
}

// GroupCheckResult captures readiness for a single group.
type GroupCheckResult struct {
	Name    string          `json:"name"`
	Model   string          `json:"model"`
	Status  string          `json:"status"`  // "ready", "not_ready", "unknown"
	Checks  []CheckDetail   `json:"checks"`
	Summary string          `json:"summary"`
}

// CheckDetail is a single check result within a group.
type CheckDetail struct {
	Check   string `json:"check"`   // e.g. "phones_registered", "model_cached", "node_online"
	Status  string `json:"status"`  // "pass", "fail", "warn"
	Detail  string `json:"detail,omitempty"`
}

func (h *PreflightHandler) handlePreflight(w http.ResponseWriter, r *http.Request) {
	// Optional target group filter
	targetGroup := r.URL.Query().Get("group")

	resp := PreflightResponse{
		Overall:   "ready",
		Timestamp: time.Now(),
		Groups:    make(map[string]GroupCheckResult),
	}

	for i := range h.cfg.Groups {
		g := &h.cfg.Groups[i]
		if targetGroup != "" && g.Name != targetGroup {
			continue
		}

		result := h.checkGroup(g)
		resp.Groups[g.Name] = result

		if result.Status != "ready" {
			resp.Overall = "not_ready"
			resp.Errors = append(resp.Errors,
				fmt.Sprintf("group %q: %s", g.Name, result.Summary))
		}
	}

	if targetGroup != "" && len(resp.Groups) == 0 {
		writeJSON(w, http.StatusNotFound, map[string]string{
			"error":   "not_found",
			"message": fmt.Sprintf("group %q not found in config", targetGroup),
		})
		return
	}

	if resp.Overall == "ready" {
		writeJSON(w, http.StatusOK, resp)
	} else {
		writeJSON(w, http.StatusOK, resp) // 200 with not_ready — let the operator decide
	}
}

func (h *PreflightHandler) checkGroup(g *config.GroupConfig) GroupCheckResult {
	result := GroupCheckResult{
		Name:   g.Name,
		Model:  g.Model,
		Status: "ready",
	}

	// 1. Check that the model is known
	modelCached := h.cache.Has(g.Model)
	modelResolvable := g.DownloadURL != "" || modelCached

	if !modelCached {
		result.Checks = append(result.Checks, CheckDetail{
			Check:  "model_cached",
			Status: "warn",
			Detail: fmt.Sprintf("model %q not cached on coordinator; will download from upstream on activation", g.Model),
		})
		// Not a hard failure — reconciler can fetch it
	}
	if !modelResolvable {
		result.Checks = append(result.Checks, CheckDetail{
			Check:  "model_resolvable",
			Status: "fail",
			Detail: fmt.Sprintf("model %q not cached and no download_url configured", g.Model),
		})
		result.Status = "not_ready"
	}

	// 2. For shard mode, mark as experimental warning
	if g.Mode == config.ModeShard {
		result.Checks = append(result.Checks, CheckDetail{
			Check:  "shard_mode",
			Status: "warn",
			Detail: "shard mode is experimental; upstream runtime not yet shipped",
		})
	}

	// 3. Check each phone
	allPhones := append([]string{}, g.Phones...)
	allPhones = append(allPhones, g.Standby...)

	if len(g.Phones) == 0 {
		result.Checks = append(result.Checks, CheckDetail{
			Check:  "phones_configured",
			Status: "fail",
			Detail: "no phones configured in group",
		})
		result.Status = "not_ready"
	}

	onlineCount := 0
	offlineCount := 0
	unregisteredCount := 0
	modelReadyCount := 0

	for _, phoneID := range allPhones {
		node, ok := h.reg.Get(phoneID)
		if !ok {
			unregisteredCount++
			result.Checks = append(result.Checks, CheckDetail{
				Check:  "phone_registered",
				Status: "fail",
				Detail: fmt.Sprintf("phone %q not registered", phoneID),
			})
			result.Status = "not_ready"
			continue
		}

		if node.State != registry.NodeStateOnline {
			offlineCount++
			result.Checks = append(result.Checks, CheckDetail{
				Check:  "phone_online",
				Status: "fail",
				Detail: fmt.Sprintf("phone %q state is %s (expected %s)", phoneID, node.State, registry.NodeStateOnline),
			})
			result.Status = "not_ready"
			continue
		}

		onlineCount++

		// Check model state
		if node.ModelStatus.Loaded && node.ModelStatus.Name == g.Model {
			modelReadyCount++
		} else if node.ModelStatus.Loaded {
			result.Checks = append(result.Checks, CheckDetail{
				Check:  "model_loaded",
				Status: "warn",
				Detail: fmt.Sprintf("phone %q loaded model %q instead of desired %q; will reconcile", phoneID, node.ModelStatus.Name, g.Model),
			})
		} else {
			result.Checks = append(result.Checks, CheckDetail{
				Check:  "model_loaded",
				Status: "warn",
				Detail: fmt.Sprintf("phone %q has no model loaded; reconciler will push", phoneID),
			})
		}

		// Check for stale heartbeat (no heartbeat in 2x offline_timeout)
		offlineTimeout := 60 * time.Second
		if h.cfg.Cluster.Health.OfflineTimeout != "" {
			if d, err := time.ParseDuration(h.cfg.Cluster.Health.OfflineTimeout); err == nil {
				offlineTimeout = d * 2
			}
		}
		if !node.LastHeartbeat.IsZero() && time.Since(node.LastHeartbeat) > offlineTimeout {
			result.Checks = append(result.Checks, CheckDetail{
				Check:  "heartbeat_fresh",
				Status: "warn",
				Detail: fmt.Sprintf("phone %q last heartbeat %v ago", phoneID, time.Since(node.LastHeartbeat).Truncate(time.Second)),
			})
		}

		// Check exclusion reasons
		if node.ExcludeReason != "" {
			result.Checks = append(result.Checks, CheckDetail{
				Check:  "phone_healthy",
				Status: "warn",
				Detail: fmt.Sprintf("phone %q excluded: %s", phoneID, node.ExcludeReason),
			})
		}
	}

	// Summary
	switch {
	case unregisteredCount > 0:
		result.Summary = fmt.Sprintf("%d phone(s) not registered", unregisteredCount)
	case offlineCount > 0:
		result.Summary = fmt.Sprintf("%d phone(s) offline", offlineCount)
	case onlineCount == 0:
		result.Summary = "no phones online"
	case !modelResolvable:
		result.Summary = "model not cached and no download URL configured"
	case result.Status == "ready":
		result.Summary = fmt.Sprintf("%d phone(s) online, model ready", onlineCount)
	default:
		result.Summary = "checks failed (see details)"
	}

	return result
}
