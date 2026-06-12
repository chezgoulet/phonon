package api

import (
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/chezgoulet/phonon/internal/pair"
	"github.com/chezgoulet/phonon/internal/registry"
)

// PairingHandler manages device pairing REST endpoints.
type PairingHandler struct {
	pm  *pair.Manager
	reg *registry.Registry
	log *slog.Logger
}

// NewPairingHandler creates a new pairing handler.
func NewPairingHandler(pm *pair.Manager, reg *registry.Registry) *PairingHandler {
	return &PairingHandler{
		pm:  pm,
		reg: reg,
		log: slog.With("component", "pairing-api"),
	}
}

// RegisterSidecarRoutes registers sidecar-facing pairing endpoints on the given mux.
func (h *PairingHandler) RegisterSidecarRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/sidecar/pair/request", h.handlePairRequest)
	mux.HandleFunc("GET /api/v1/sidecar/pair/status", h.handlePairStatus)
}

// RegisterOperatorRoutes registers operator-facing pairing endpoints on the given mux.
// These are intended to be behind auth middleware.
func (h *PairingHandler) RegisterOperatorRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/pair/confirm", h.handleConfirmPairing)
	mux.HandleFunc("GET /api/v1/pair/pending", h.handleListPending)
	mux.HandleFunc("GET /api/v1/pair/paired", h.handleListPaired)
	mux.HandleFunc("POST /api/v1/pair/unpair", h.handleUnpair)
}

// --- Sidecar-facing: pairing request ---

type pairRequestJSON struct {
	DeviceID    string `json:"device_id"`
	DeviceModel string `json:"device_model"`
	DevicePubKey string `json:"device_pubkey"` // hex-encoded Ed25519 public key
}

type pairRequestResponse struct {
	Status string `json:"status"` // "pending" or "already_paired"
	Code   string `json:"code,omitempty"` // 6-digit code, only for phones with a screen
}

// handlePairRequest initiates a pairing request from the sidecar.
func (h *PairingHandler) handlePairRequest(w http.ResponseWriter, r *http.Request) {
	var req pairRequestJSON
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}
	if req.DevicePubKey == "" {
		writeError(w, http.StatusBadRequest, "device_pubkey is required")
		return
	}

	// Check if already paired
	if h.pm.IsPaired(req.DeviceID) {
		writeJSON(w, http.StatusOK, pairRequestResponse{Status: "already_paired"})
		return
	}

	// Decode device public key
	pubKey, err := hex.DecodeString(req.DevicePubKey)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid device_pubkey: not valid hex")
		return
	}

	// Get device IP from registry
	ip := ""
	if node, ok := h.reg.Get(req.DeviceID); ok {
		ip = node.IPAddress
	}

	code, err := h.pm.StartPairing(req.DeviceID, req.DeviceModel, ip, pubKey)
	if err != nil {
		h.log.Error("failed to start pairing", "device_id", req.DeviceID, "error", err)
		writeError(w, http.StatusInternalServerError, "pairing failed")
		return
	}

	h.log.Debug("pairing started",
		"device_id", req.DeviceID,
		"device_model", req.DeviceModel,
		"code", code,
	)

	// Return the code. Headless phones can display it; phones with screens
	// show it to the user for the operator to type into the coordinator UI.
	writeJSON(w, http.StatusCreated, pairRequestResponse{
		Status: "pending",
		Code:   code,
	})
}

// --- Sidecar-facing: poll pairing status ---

type pairStatusResponse struct {
	Status string `json:"status"` // "paired", "pending", "expired"
	Name   string `json:"name,omitempty"`
}

// handlePairStatus lets the sidecar poll whether pairing has been confirmed.
func (h *PairingHandler) handlePairStatus(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("device_id")
	if deviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id query parameter required")
		return
	}

	if d := h.pm.PairedDevice(deviceID); d != nil {
		writeJSON(w, http.StatusOK, pairStatusResponse{
			Status: "paired",
			Name:   d.Name,
		})
		return
	}

	// Check pending — if no pending either, likely expired or never started
	pendings := h.pm.ListPending()
	for _, p := range pendings {
		if p.DeviceID == deviceID {
			writeJSON(w, http.StatusOK, pairStatusResponse{Status: "pending"})
			return
		}
	}

	writeJSON(w, http.StatusOK, pairStatusResponse{Status: "expired"})
}

// --- Operator-facing: confirm pairing ---

type confirmRequest struct {
	DeviceID string `json:"device_id"`
	Code     string `json:"code"`     // 6-digit code shown on the phone (empty = auto-approve for headless)
	Name     string `json:"name"`     // optional human-friendly name override
}

type confirmResponse struct {
	Status   string `json:"status"`
	NodeName string `json:"node_name"`
}

func (h *PairingHandler) handleConfirmPairing(w http.ResponseWriter, r *http.Request) {
	var req confirmRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	// Resolve name
	name := req.Name
	if name == "" {
		if node, ok := h.reg.Get(req.DeviceID); ok {
			name = node.Name
		} else {
			name = req.DeviceID
		}
	}

	// For headless phones, an empty code means "auto-approve."
	// Generate a virtual one-shot code that can't collide.
	if req.Code == "" {
		// List pending to find the device
		pendings := h.pm.ListPending()
		found := false
		for _, p := range pendings {
			if p.DeviceID == req.DeviceID {
				req.Code = p.Code // use the existing code
				found = true
				break
			}
		}
		if !found {
			writeError(w, http.StatusNotFound, "no pending pairing for this device")
			return
		}
	}

	paired, err := h.pm.ConfirmPairing(req.DeviceID, req.Code, name)
	if err != nil {
		h.log.Warn("pairing confirmation failed",
			"device_id", req.DeviceID,
			"error", err,
		)
		writeError(w, http.StatusConflict, err.Error())
		return
	}

	// Also update the registry to paired state
	_ = h.reg.SetDeviceModel(paired.DeviceID, paired.DeviceModel)
	if err := h.reg.Pair(paired.DeviceID); err != nil {
		// Non-fatal — pairing manager already confirmed
		h.log.Warn("registry pair state transition failed (already paired?)",
			"device_id", paired.DeviceID, "error", err)
	}

	h.log.Debug("device paired",
		"device_id", paired.DeviceID,
		"name", paired.Name,
		"device_key", hex.EncodeToString(paired.DeviceKey)[:16]+"...",
	)

	writeJSON(w, http.StatusOK, confirmResponse{
		Status:   "paired",
		NodeName: paired.Name,
	})
}

// --- Operator-facing: list pending pairings ---

type pendingPairJSON struct {
	DeviceID    string `json:"device_id"`
	DeviceModel string `json:"device_model"`
	Code        string `json:"code"`
	IPAddress   string `json:"ip_address"`
	CreatedAt   string `json:"created_at"`
	ExpiresAt   string `json:"expires_at"`
}

func (h *PairingHandler) handleListPending(w http.ResponseWriter, _ *http.Request) {
	pendings := h.pm.ListPending()
	result := make([]pendingPairJSON, 0, len(pendings))
	for _, p := range pendings {
		result = append(result, pendingPairJSON{
			DeviceID:    p.DeviceID,
			DeviceModel: p.DeviceModel,
			Code:        p.Code,
			IPAddress:   p.IPAddress,
			CreatedAt:   p.CreatedAt.Format(timeFormat),
			ExpiresAt:   p.ExpiresAt.Format(timeFormat),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"pending": result})
}

// --- Operator-facing: list paired devices ---

type pairedDeviceJSON struct {
	DeviceID    string `json:"device_id"`
	DeviceModel string `json:"device_model"`
	Name        string `json:"name"`
	DeviceKey   string `json:"device_key"` // hex-encoded
	IPAddress   string `json:"ip_address"`
	PairedAt    string `json:"paired_at"`
}

func (h *PairingHandler) handleListPaired(w http.ResponseWriter, _ *http.Request) {
	devices := h.pm.ListPaired()
	result := make([]pairedDeviceJSON, 0, len(devices))
	for _, d := range devices {
		result = append(result, pairedDeviceJSON{
			DeviceID:    d.DeviceID,
			DeviceModel: d.DeviceModel,
			Name:        d.Name,
			DeviceKey:   hex.EncodeToString(d.DeviceKey),
			IPAddress:   d.IPAddress,
			PairedAt:    d.PairedAt.Format(timeFormat),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"paired": result})
}

// --- Operator-facing: unpair ---

type unpairResponse struct {
	Status string `json:"status"`
}

func (h *PairingHandler) handleUnpair(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("device_id")
	if deviceID == "" {
		writeError(w, http.StatusBadRequest, "device_id query parameter required")
		return
	}

	h.pm.RemovePaired(deviceID)
	h.log.Info("device unpaired", "device_id", deviceID)
	writeJSON(w, http.StatusOK, unpairResponse{Status: "unpaired"})
}

const timeFormat = time.RFC3339
