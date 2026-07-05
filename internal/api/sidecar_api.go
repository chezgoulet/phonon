package api

import (
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

// SidecarHandler handles REST API endpoints for sidecar communication.
type SidecarHandler struct {
	reg         *registry.Registry
	log         *slog.Logger
	coordPubKey string // hex-encoded coordinator Ed25519 public key (for pairing info)

	// deviceAuth enforces per-device token auth for paired devices.
	// Nil disables enforcement (tests only).
	deviceAuth DeviceAuthorizer

	// groupMap maps device_id → group name, populated from config groups.
	// When non-nil, register and heartbeat handlers call reg.AssignToGroup
	// so pool-scoped routing and health counts actually work.
	groupMap map[string]string

	// maxUnpaired caps the number of unpaired devices in the registry.
	// When exceeded, handleRegister rejects new registrations with 429
	// to prevent memory-growth DoS from unbounded sidecar registration.
	// 0 means unlimited (default, for backward compatibility).
	maxUnpaired int
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

// SetDeviceAuthorizer enables per-device token enforcement for paired
// devices on heartbeat and model-status endpoints.
func (h *SidecarHandler) SetDeviceAuthorizer(a DeviceAuthorizer) {
	h.deviceAuth = a
}

// SetGroupMapping sets the device_id → group name mapping derived from
// config groups. When set, every register and heartbeat handler call
// reg.AssignToGroup so pool-scoped routing (GetHealthyByGroup) and the
// X-Phonon-Group header reflect actual groups instead of empty strings.
func (h *SidecarHandler) SetGroupMapping(m map[string]string) {
	h.groupMap = m
}

// SetMaxUnpaired caps the number of unpaired devices the registry will
// accept. When exceeded, new registrations return 429. 0 means unlimited.
// Set this to, e.g., 2× the configured phone count to allow for pairing
// churn without opening a memory-DoS vector.
func (h *SidecarHandler) SetMaxUnpaired(n int) {
	h.maxUnpaired = n
}

// RegisterRoutes registers all sidecar REST endpoints on the given mux.
func (h *SidecarHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/sidecar/register", h.handleRegister)
	mux.HandleFunc("POST /api/v1/sidecar/heartbeat", h.handleHeartbeat)
	mux.HandleFunc("POST /api/v1/sidecar/model-status", h.handleModelStatus)
}

// --- Registration ---

type registerRequest struct {
	DeviceID         string `json:"device_id"`
	DeviceModel      string `json:"device_model"`
	DevicePubKey     string `json:"device_pubkey,omitempty"` // hex-encoded Ed25519 public key (pairing)
	AndroidVersion   string `json:"android_version"`
	IPAddress        string `json:"ip_address"`
	NetworkInterface string `json:"network_interface"`
}

type registerResponse struct {
	Status          string `json:"status"`
	NodeName        string `json:"node_name"`
	AssignedTo      string `json:"assigned_to,omitempty"`
	PairingRequired bool   `json:"pairing_required,omitempty"` // true if pubkey was sent but device isn't paired
	PairingEndpoint string `json:"pairing_endpoint,omitempty"` // URL for pair request
	CoordinatorKey  string `json:"coordinator_key,omitempty"`  // coordinator's public key hex
}

func (h *SidecarHandler) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	// Gate: if maxUnpaired is set, reject new registrations for unknown
	// devices when the registry has too many unpaired nodes. Already-known
	// devices always pass through (they're re-registering).
	if h.maxUnpaired > 0 {
		if _, exists := h.reg.Get(req.DeviceID); !exists {
			unpaired := 0
			for _, n := range h.reg.List() {
				if n.State == registry.NodeStateUnpaired {
					unpaired++
				}
			}
			if unpaired >= h.maxUnpaired {
				h.log.Warn("register rejected: too many unpaired devices",
					"unpaired", unpaired, "max", h.maxUnpaired)
				writeJSON(w, http.StatusTooManyRequests, map[string]any{
					"error":   "too many unpaired devices",
					"message": "pair existing devices before registering new ones",
				})
				return
			}
		}
	}

	// Auto-generate name: device_model + last 4 of device_id
	shortID := req.DeviceID
	if len(shortID) > 4 {
		shortID = shortID[len(shortID)-4:]
	}
	modelShort := strings.ReplaceAll(strings.ToLower(req.DeviceModel), " ", "-")
	autoName := modelShort + "-" + shortID

	// Derive IP from RemoteAddr — ignore the body's ip_address field
	// (a malicious LAN device could use it to register a different target).
	clientIP := deriveClientIP(r.RemoteAddr)
	err := h.reg.Register(req.DeviceID, autoName, clientIP)
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
	h.assignGroup(req.DeviceID)
	writeJSON(w, http.StatusCreated, resp)
}

// assignGroup looks up the device ID in the group mapping and assigns it
// if found. Best-effort (logs failures, never returns an error) so grouping
// is additive and never blocks registration or heartbeat processing.
func (h *SidecarHandler) assignGroup(deviceID string) {
	if h.groupMap == nil {
		return
	}
	group, ok := h.groupMap[deviceID]
	if !ok || group == "" {
		return
	}
	if err := h.reg.AssignToGroup(deviceID, group); err != nil {
		h.log.Warn("group assignment failed",
			"device_id", deviceID, "group", group, "error", err)
	}
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
	Loaded  string   `json:"loaded,omitempty"`
	Cached  []string `json:"cached,omitempty"`
	Backend string   `json:"backend,omitempty"` // active accelerator: npu/gpu/cpu
}

type heartbeatRequest struct {
	DeviceID   string           `json:"device_id"`
	Battery    batteryTelemetry `json:"battery"`
	Thermal    thermalTelemetry `json:"thermal"`
	Storage    storageTelemetry `json:"storage"`
	Model      *modelInfo       `json:"model,omitempty"`
	QueueDepth int              `json:"queue_depth"`
	Network    string           `json:"network"`
	Timestamp  string           `json:"timestamp"`
}

func (h *SidecarHandler) handleHeartbeat(w http.ResponseWriter, r *http.Request) {
	var req heartbeatRequest
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}
	if !authorizeDevice(w, r, h.deviceAuth, req.DeviceID) {
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

	// Re-apply group assignment on heartbeat so nodes that register before
	// the handler has its group mapping (e.g. early mDNS discovery) get their
	// group on the next heartbeat cycle.
	h.assignGroup(req.DeviceID)

	// Persist model status from heartbeat if present
	if req.Model != nil && req.Model.Loaded != "" {
		_ = h.reg.SetModelStatus(req.DeviceID, registry.ModelStatus{
			Name:     req.Model.Loaded,
			Loaded:   true,
			LoadedAt: time.Now(),
			Backend:  req.Model.Backend,
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
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}
	if !authorizeDevice(w, r, h.deviceAuth, req.DeviceID) {
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

// --- Helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// deriveClientIP extracts the client IP from http.Request.RemoteAddr,
// stripping the port. Returns the raw value if parsing fails.
func deriveClientIP(remoteAddr string) string {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		return remoteAddr
	}
	return host
}
