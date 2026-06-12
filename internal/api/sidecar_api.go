package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
	"github.com/google/uuid"
)

// SidecarHandler handles REST API endpoints for sidecar communication.
type SidecarHandler struct {
	reg  *registry.Registry
	log  *slog.Logger
	coordPubKey string // hex-encoded coordinator Ed25519 public key (for pairing info)
}

// NewSidecarHandler creates a new handler with the given node registry.
func NewSidecarHandler(reg *registry.Registry) *SidecarHandler {
	return &SidecarHandler{
		reg: reg,
		log: slog.With("component", "sidecar-api"),
	}
}

// SetCoordinatorKey sets the coordinator's public key for pairing handshake responses.
func (h *SidecarHandler) SetCoordinatorKey(pubKeyHex string) {
	h.coordPubKey = pubKeyHex
}

// RegisterRoutes registers all sidecar REST endpoints on the given mux.
func (h *SidecarHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/sidecar/register", h.handleRegister)
	mux.HandleFunc("POST /api/v1/sidecar/heartbeat", h.handleHeartbeat)
	mux.HandleFunc("POST /api/v1/sidecar/model-status", h.handleModelStatus)
	mux.HandleFunc("POST /api/v1/sidecar/pair", h.handlePair)
}

// --- Registration ---

type registerRequest struct {
	DeviceID        string `json:"device_id"`
	DeviceModel     string `json:"device_model"`
	DevicePubKey    string `json:"device_pubkey,omitempty"` // hex-encoded Ed25519 public key (pairing)
	AndroidVersion  string `json:"android_version"`
	IPAddress       string `json:"ip_address"`
	NetworkInterface string `json:"network_interface"`
}

type registerResponse struct {
	Status          string `json:"status"`
	NodeName        string `json:"node_name"`
	AssignedTo      string `json:"assigned_to,omitempty"`
	PairingRequired bool   `json:"pairing_required,omitempty"` // true if pubkey was sent but device isn't paired
	PairingEndpoint string `json:"pairing_endpoint,omitempty"` // URL for pair request
	CoordinatorKey  string `json:"coordinator_key,omitempty"` // coordinator's public key hex
}

func (h *SidecarHandler) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	// Auto-generate name: device_model + last 4 of device_id
	shortID := req.DeviceID
	if len(shortID) > 4 {
		shortID = shortID[len(shortID)-4:]
	}
	modelShort := strings.ReplaceAll(strings.ToLower(req.DeviceModel), " ", "-")
	autoName := modelShort + "-" + shortID

	err := h.reg.Register(req.DeviceID, autoName, req.IPAddress)
	if err != nil {
		// Already registered — return the existing node name
		if node, ok := h.reg.Get(req.DeviceID); ok {
			resp := registerResponse{
				Status:   "existing",
				NodeName: node.Name,
			}
			// If device sent a pubkey and is still unpaired, tell them to pair
			if req.DevicePubKey != "" && node.State == registry.NodeStateUnpaired {
				resp.PairingRequired = true
				resp.PairingEndpoint = "/api/v1/sidecar/pair/request"
				resp.CoordinatorKey = h.coordPubKey
			}
			h.log.Info("sidecar re-registered", "device_id", req.DeviceID, "name", node.Name,
				"pairing_required", resp.PairingRequired)
			writeJSON(w, http.StatusOK, resp)
			return
		}
		writeError(w, http.StatusConflict, err.Error())
		return
	}

	resp := registerResponse{
		Status:   "registered",
		NodeName: autoName,
	}

	// If device provided a pubkey, include pairing info
	if req.DevicePubKey != "" && h.coordPubKey != "" {
		resp.PairingRequired = true
		resp.PairingEndpoint = "/api/v1/sidecar/pair/request"
		resp.CoordinatorKey = h.coordPubKey
	}

	h.log.Info("sidecar registered", "device_id", req.DeviceID, "name", autoName,
		"pairing_required", resp.PairingRequired)
	writeJSON(w, http.StatusCreated, resp)
}

// --- Heartbeat ---

type batteryTelemetry struct {
	Level       float64 `json:"level"`
	Charging    bool    `json:"charging"`
	Cycles      int     `json:"cycles,omitempty"`
	CapacityPct float64 `json:"capacity_pct,omitempty"`
}

type thermalTelemetry struct {
	SoCTempC float64 `json:"soc_temp_c"`
}

type storageTelemetry struct {
	TotalGB float64 `json:"total_gb"`
	FreeGB  float64 `json:"free_gb"`
}

type modelInfo struct {
	Loaded string   `json:"loaded,omitempty"`
	Cached []string `json:"cached,omitempty"`
}

type heartbeatRequest struct {
	DeviceID   string            `json:"device_id"`
	Battery    batteryTelemetry  `json:"battery"`
	Thermal    thermalTelemetry  `json:"thermal"`
	Storage    storageTelemetry  `json:"storage"`
	Model      *modelInfo        `json:"model,omitempty"`
	QueueDepth int               `json:"queue_depth"`
	Network    string            `json:"network"`
	Timestamp  string            `json:"timestamp"`
}

func (h *SidecarHandler) handleHeartbeat(w http.ResponseWriter, r *http.Request) {
	var req heartbeatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	telemetry := registry.HealthTelemetry{
		BatteryLevel:       req.Battery.Level,
		BatteryCapacityPct: req.Battery.CapacityPct,
		ThermalTempC:       req.Thermal.SoCTempC,
		IsCharging:         req.Battery.Charging,
		QueueDepth:         req.QueueDepth,
	}

	err := h.reg.UpdateHeartbeat(req.DeviceID, telemetry)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	// Persist model status from heartbeat if present
	if req.Model != nil && req.Model.Loaded != "" {
		_ = h.reg.SetModelStatus(req.DeviceID, registry.ModelStatus{
			Name:     req.Model.Loaded,
			Loaded:   true,
			LoadedAt: time.Now(),
		})
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// --- Model Status ---

type modelStatusRequest struct {
	DeviceID string   `json:"device_id"`
	Loaded   *string  `json:"loaded"`
	Cached   []string `json:"cached"`
	FreeGB   float64  `json:"free_gb"`
}

func (h *SidecarHandler) handleModelStatus(w http.ResponseWriter, r *http.Request) {
	var req modelStatusRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	_, ok := h.reg.Get(req.DeviceID)
	if !ok {
		writeError(w, http.StatusNotFound, "device not found")
		return
	}

	ms := registry.ModelStatus{
		Loaded: req.Loaded != nil && *req.Loaded != "",
	}
	if req.Loaded != nil && *req.Loaded != "" {
		ms.Name = *req.Loaded
		ms.LoadedAt = time.Now()
	}
	if err := h.reg.SetModelStatus(req.DeviceID, ms); err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	h.log.Info("model status update", "device_id", req.DeviceID, "loaded", req.Loaded)

	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// --- Pairing ---

type auditInfo struct {
	PackagesInstalled int    `json:"packages_installed"`
	RootDetected      bool   `json:"root_detected"`
	BootloaderLocked  bool   `json:"bootloader_locked"`
	AndroidVersion    string `json:"android_version"`
}

type pairRequest struct {
	DeviceID string    `json:"device_id"`
	Token    string    `json:"token"`
	Audit    auditInfo `json:"audit"`
}

type pairResponse struct {
	Status   string `json:"status"`
	NodeName string `json:"node_name"`
	PairID   string `json:"pair_id"`
}

func (h *SidecarHandler) handlePair(w http.ResponseWriter, r *http.Request) {
	var req pairRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	node, ok := h.reg.Get(req.DeviceID)
	if !ok {
		writeError(w, http.StatusNotFound, "device not found — register first")
		return
	}

	if err := h.reg.Pair(req.DeviceID); err != nil {
		writeError(w, http.StatusConflict, err.Error())
		return
	}

	pairID := uuid.New().String()
	h.log.Info("sidecar paired",
		"device_id", req.DeviceID,
		"name", node.Name,
		"pair_id", pairID,
		"root", req.Audit.RootDetected,
	)

	writeJSON(w, http.StatusOK, pairResponse{
		Status:   "paired",
		NodeName: node.Name,
		PairID:   pairID,
	})
}

// --- Helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
