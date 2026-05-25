package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// Command types sent from coordinator to sidecar.
const (
	CmdModelPush      = "model_push"
	CmdModelLoad      = "model_load"
	CmdModelUnload    = "model_unload"
	CmdModeChange     = "mode_change"
	CmdStandbyPromote = "standby_promote"
	CmdShutdown       = "shutdown"
)

// Ack status values returned by the sidecar.
const (
	AckAccepted  = "accepted"
	AckCompleted = "completed"
	AckFailed    = "failed"
)

// WSCommand represents an outbound command sent to a sidecar.
type WSCommand struct {
	Type      string          `json:"type"`
	CommandID string          `json:"command_id"`
	Payload   json.RawMessage `json:"payload"`
}

// WSAck is an acknowledgement received from the sidecar.
type WSAck struct {
	Type      string `json:"type"`
	CommandID string `json:"command_id"`
	Status    string `json:"status"`
	Error     string `json:"error,omitempty"`
}

// pendingCommand tracks a single command's lifecycle for a device.
type pendingCommand struct {
	Cmd    WSCommand
	Status string // sent, accepted, completed, failed
	SentAt time.Time
}

// deviceConn wraps an active WebSocket connection for one device.
type deviceConn struct {
	conn     *websocket.Conn
	deviceID string
}

// WSHandler manages all sidecar WebSocket connections and command queues.
type WSHandler struct {
	reg *registry.Registry
	log *slog.Logger

	mu      sync.RWMutex
	devices map[string]*deviceConn              // device_id → active connection
	pending map[string]map[string]*pendingCommand // device_id → command_id → state

	upgrader websocket.Upgrader
}

// NewWSHandler creates a new WebSocket handler.
func NewWSHandler(reg *registry.Registry) *WSHandler {
	return &WSHandler{
		reg:     reg,
		log:     slog.With("component", "ws"),
		devices: make(map[string]*deviceConn),
		pending: make(map[string]map[string]*pendingCommand),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin:     func(_ *http.Request) bool { return true },
		},
	}
}

// RegisterRoutes registers the WebSocket endpoint on the given mux.
func (h *WSHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/sidecar/ws", h.handleWebSocket)
}

// handleWebSocket upgrades an HTTP connection to WebSocket and manages the session.
func (h *WSHandler) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("device_id")
	if deviceID == "" {
		http.Error(w, "device_id query parameter required", http.StatusBadRequest)
		return
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.log.Error("websocket upgrade failed", "device_id", deviceID, "error", err)
		return
	}

	dc := &deviceConn{conn: conn, deviceID: deviceID}

	h.mu.Lock()
	// Close any stale connection for this device
	if existing, ok := h.devices[deviceID]; ok {
		existing.conn.Close()
	}
	h.devices[deviceID] = dc
	h.mu.Unlock()

	h.log.Info("websocket connected", "device_id", deviceID)

	// Re-send any pending (unacknowledged) commands for this device
	h.resendPending(deviceID)

	defer func() {
		h.mu.Lock()
		if h.devices[deviceID] == dc {
			delete(h.devices, deviceID)
		}
		h.mu.Unlock()
		conn.Close()
		h.log.Info("websocket disconnected", "device_id", deviceID)
	}()

	for {
		_, message, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
				h.log.Warn("websocket read error", "device_id", deviceID, "error", err)
			}
			return
		}
		h.handleAck(deviceID, message)
	}
}

// handleAck processes an incoming acknowledgment from a sidecar.
func (h *WSHandler) handleAck(deviceID string, data []byte) {
	var ack WSAck
	if err := json.Unmarshal(data, &ack); err != nil {
		h.log.Warn("invalid ack message", "device_id", deviceID, "error", err)
		return
	}
	if ack.Type != "ack" {
		return
	}

	h.mu.Lock()
	defer h.mu.Unlock()

	devicePending, ok := h.pending[deviceID]
	if !ok {
		return
	}
	pc, ok := devicePending[ack.CommandID]
	if !ok {
		h.log.Debug("ack for unknown command", "device_id", deviceID, "command_id", ack.CommandID)
		return
	}

	switch ack.Status {
	case AckAccepted:
		pc.Status = "accepted"
	case AckCompleted:
		pc.Status = "completed"
		delete(devicePending, ack.CommandID)
	case AckFailed:
		pc.Status = "failed"
		delete(devicePending, ack.CommandID)
		h.log.Warn("command failed", "device_id", deviceID, "command_id", ack.CommandID, "error", ack.Error)
	}
}

// SendCommand queues a command for a device. If the device has an active
// WebSocket connection, the command is sent immediately.
// Returns the command ID.
func (h *WSHandler) SendCommand(deviceID, cmdType string, payload any) (string, error) {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}

	cmd := WSCommand{
		Type:      cmdType,
		CommandID: uuid.New().String(),
		Payload:   payloadBytes,
	}

	h.mu.Lock()
	if h.pending[deviceID] == nil {
		h.pending[deviceID] = make(map[string]*pendingCommand)
	}
	h.pending[deviceID][cmd.CommandID] = &pendingCommand{
		Cmd:    cmd,
		Status: "sent",
		SentAt: time.Now(),
	}
	dc := h.devices[deviceID]
	h.mu.Unlock()

	// Send immediately if connected
	if dc != nil {
		if err := dc.conn.WriteJSON(cmd); err != nil {
			h.log.Error("failed to send command", "device_id", deviceID, "command_id", cmd.CommandID, "error", err)
			return cmd.CommandID, err
		}
	}

	return cmd.CommandID, nil
}

// SendModelPush sends a model_push command to a device.
func (h *WSHandler) SendModelPush(deviceID, model, url, checksum string, sizeBytes int64) (string, error) {
	return h.SendCommand(deviceID, CmdModelPush, map[string]any{
		"model": model, "url": url, "checksum": checksum, "size_bytes": sizeBytes,
	})
}

// SendModelLoad sends a model_load command.
func (h *WSHandler) SendModelLoad(deviceID, model string) (string, error) {
	return h.SendCommand(deviceID, CmdModelLoad, map[string]string{"model": model})
}

// SendModelUnload sends a model_unload command.
func (h *WSHandler) SendModelUnload(deviceID string) (string, error) {
	return h.SendCommand(deviceID, CmdModelUnload, map[string]any{})
}

// SendModeChange sends a mode_change command.
func (h *WSHandler) SendModeChange(deviceID, mode, runtime string) (string, error) {
	return h.SendCommand(deviceID, CmdModeChange, map[string]string{"mode": mode, "runtime": runtime})
}

// SendStandbyPromote sends a standby_promote command.
func (h *WSHandler) SendStandbyPromote(deviceID, model, url, checksum string) (string, error) {
	return h.SendCommand(deviceID, CmdStandbyPromote, map[string]any{
		"model": model, "url": url, "checksum": checksum,
	})
}

// SendShutdown sends a shutdown command.
func (h *WSHandler) SendShutdown(deviceID, reason string) (string, error) {
	return h.SendCommand(deviceID, CmdShutdown, map[string]string{"reason": reason})
}

// resendPending re-sends all unacknowledged commands for a device that
// just reconnected. Collects commands under the write lock to avoid data
// races with handleAck, then sends outside the lock.
func (h *WSHandler) resendPending(deviceID string) {
	type pendingCmd struct {
		cmd   WSCommand
		cmdID string
	}

	h.mu.Lock()
	devicePending := h.pending[deviceID]
	dc := h.devices[deviceID]

	if dc == nil || len(devicePending) == 0 {
		h.mu.Unlock()
		return
	}

	toResend := make([]pendingCmd, 0, len(devicePending))
	toRemove := make([]string, 0, len(devicePending))

	for cmdID, pc := range devicePending {
		if pc.Status == "completed" || pc.Status == "failed" {
			toRemove = append(toRemove, cmdID)
			continue
		}
		toResend = append(toResend, pendingCmd{cmd: pc.Cmd, cmdID: cmdID})
		pc.Status = "sent"
		pc.SentAt = time.Now()
	}
	for _, id := range toRemove {
		delete(devicePending, id)
	}
	h.mu.Unlock()

	for _, pc := range toResend {
		if err := dc.conn.WriteJSON(pc.cmd); err != nil {
			h.log.Error("failed to resend command", "device_id", deviceID, "command_id", pc.cmdID, "error", err)
			return
		}
		h.log.Info("re-sent pending command", "device_id", deviceID, "command_id", pc.cmdID)
	}
}

// HasConnection reports whether a device has an active WebSocket connection.
func (h *WSHandler) HasConnection(deviceID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, exists := h.devices[deviceID]
	return exists
}

// ConnectedDevices returns all device IDs with active WebSocket connections.
func (h *WSHandler) ConnectedDevices() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()
	devices := make([]string, 0, len(h.devices))
	for id := range h.devices {
		devices = append(devices, id)
	}
	return devices
}
