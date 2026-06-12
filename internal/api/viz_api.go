package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
)

// VizHandler exposes REST endpoints for remote visualization pack control.
//
// Routes (all auth-protected, registered under /api/v1/viz/):
//   GET    /api/v1/viz/packs                  — list built-in pack manifests
//   POST   /api/v1/viz/device/{deviceId}/switch — per-device pack switch
//   POST   /api/v1/viz/switch                 — broadcast pack switch
//   POST   /api/v1/viz/device/{deviceId}/config — per-device config push
//   POST   /api/v1/viz/arrangement             — set + broadcast arrangement
//   POST   /api/v1/viz/show-numbers            — broadcast number toggle
type VizHandler struct {
	ws       *WSHandler
	log      *slog.Logger
	mu       sync.RWMutex
	arrangement []ArrangementEntry // latest arrangement, shared across handlers
}

// NewVizHandler creates a handler for the coordinator's visualization API.
func NewVizHandler(ws *WSHandler) *VizHandler {
	return &VizHandler{
		ws:  ws,
		log: slog.With("component", "viz-api"),
	}
}

// RegisterRoutes adds visualization REST endpoints to the given mux.
func (h *VizHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/viz/packs", h.handleListPacks)
	mux.HandleFunc("POST /api/v1/viz/device/{deviceId}/switch", h.handleDeviceSwitch)
	mux.HandleFunc("POST /api/v1/viz/switch", h.handleBroadcastSwitch)
	mux.HandleFunc("POST /api/v1/viz/device/{deviceId}/config", h.handleDeviceConfig)
	mux.HandleFunc("POST /api/v1/viz/arrangement", h.handleSetArrangement)
	mux.HandleFunc("POST /api/v1/viz/show-numbers", h.handleShowNumbers)
}

// ── Pack manifests ──

// PackManifest is the serializable descriptor of a built-in visualization pack.
type PackManifest struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Author      string            `json:"author"`
	Version     string            `json:"version"`
	DefaultConfig map[string]string `json:"default_config"`
}

var builtinPacks = []PackManifest{
	{
		ID:          "neon-ring",
		Name:        "Neon Ring",
		Description: "Synthwave ring visualization driven by inference activity",
		Author:      "chezgoulet",
		Version:     "1.0.0",
		DefaultConfig: map[string]string{
			"ring_color_primary":   "#38BDF8",
			"ring_color_secondary": "#D946EF",
			"ring_color_processing":"#22C55E",
			"rotation_speed":      "0.8",
			"glow_intensity":      "1.0",
		},
	},
	{
		ID:          "matrix-rain",
		Name:        "Matrix Rain",
		Description: "CRT green phosphor rain driven by inference activity",
		Author:      "chezgoulet",
		Version:     "1.0.0",
		DefaultConfig: map[string]string{
			"rain_density":    "1.0",
			"rain_speed":      "1.0",
			"char_brightness": "1.0",
			"glow_color":      "#00FF41",
		},
	},
	{
		ID:          "cyber-hud",
		Name:        "Cyber HUD",
		Description: "Tactical cyberpunk HUD visualization",
		Author:      "chezgoulet",
		Version:     "1.0.0",
		DefaultConfig: map[string]string{
			"hud_color_primary":    "#00E5FF",
			"hud_color_accent":     "#FF3D00",
			"hud_color_text":       "#00E5FF",
			"radar_range":          "0.4",
			"waveform_sensitivity": "1.0",
		},
	},
}

func (h *VizHandler) handleListPacks(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"object": "list",
		"data":   builtinPacks,
	})
}

// ── Pack switch (per-device) ──

type switchRequest struct {
	PackID string `json:"pack_id"`
}

func (h *VizHandler) handleDeviceSwitch(w http.ResponseWriter, r *http.Request) {
	deviceID := r.PathValue("deviceId")
	if deviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	var req switchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.PackID == "" {
		writeError(w, http.StatusBadRequest, "pack_id is required")
		return
	}

	cmdID, err := h.ws.SendVizSwitch(deviceID, req.PackID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	h.log.Info("viz switch sent", "device_id", deviceID, "pack_id", req.PackID, "command_id", cmdID)
	writeJSON(w, http.StatusOK, map[string]string{
		"status":     "sent",
		"command_id": cmdID,
	})
}

// ── Pack switch (broadcast) ──

func (h *VizHandler) handleBroadcastSwitch(w http.ResponseWriter, r *http.Request) {
	var req switchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.PackID == "" {
		writeError(w, http.StatusBadRequest, "pack_id is required")
		return
	}

	h.ws.BroadcastVizSwitch(req.PackID)

	h.log.Info("viz switch broadcast", "pack_id", req.PackID)
	writeJSON(w, http.StatusOK, map[string]string{
		"status": "broadcast",
	})
}

// ── Config (per-device) ──

type configRequest struct {
	Config map[string]string `json:"config"`
}

func (h *VizHandler) handleDeviceConfig(w http.ResponseWriter, r *http.Request) {
	deviceID := r.PathValue("deviceId")
	if deviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	var req configRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Config == nil {
		req.Config = make(map[string]string)
	}

	cmdID, err := h.ws.SendVizConfig(deviceID, req.Config)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	h.log.Info("viz config sent", "device_id", deviceID, "command_id", cmdID)
	writeJSON(w, http.StatusOK, map[string]string{
		"status":     "sent",
		"command_id": cmdID,
	})
}

// ── Arrangement ──

type arrangementRequest struct {
	Entries []ArrangementEntry `json:"entries"`
}

func (h *VizHandler) handleSetArrangement(w http.ResponseWriter, r *http.Request) {
	var req arrangementRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	// Store in memory
	h.mu.Lock()
	h.arrangement = req.Entries
	h.mu.Unlock()

	// Broadcast to all connected devices
	h.ws.BroadcastVizArrangement(req.Entries)

	h.log.Info("viz arrangement set + broadcast", "count", len(req.Entries))
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "broadcast",
		"count":  len(req.Entries),
	})
}

// GetArrangement returns the current arrangement (thread-safe).
func (h *VizHandler) GetArrangement() []ArrangementEntry {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]ArrangementEntry, len(h.arrangement))
	copy(out, h.arrangement)
	return out
}

// ── Show Numbers ──

type showNumbersRequest struct {
	Visible bool `json:"visible"`
}

func (h *VizHandler) handleShowNumbers(w http.ResponseWriter, r *http.Request) {
	var req showNumbersRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	h.ws.BroadcastVizShowNumbers(req.Visible)

	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "broadcast",
		"visible": req.Visible,
	})
}
