package api

import (
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

const (
	// wsReadLimit is the maximum WebSocket message size in bytes.
	// Sidecar acks are tiny by design (< 1 KB), including model_push
	// orchestration messages that carry a URL + checksum.
	wsReadLimit = 64 * 1024

	// wsPongWait is how long the coordinator waits for a pong before
	// considering the connection dead.
	wsPongWait = 30 * time.Second

	// wsWriteTimeout is applied to each WriteJSON call to prevent a slow
	// consumer from stalling the handler while holding mu.
	wsWriteTimeout = 10 * time.Second
)

// Command types sent from coordinator to sidecar.
const (
	CmdModelPush      = "model_push"
	CmdModelLoad      = "model_load"
	CmdModelUnload    = "model_unload"
	CmdModeChange     = "mode_change"
	CmdStandbyPromote = "standby_promote"
	CmdShutdown       = "shutdown"

	// Visualization pack commands
	CmdVizSwitch      = "viz_switch"
	CmdVizConfig      = "viz_config"
	CmdVizArrangement = "viz_arrangement"
	CmdVizShowNumbers = "viz_show_numbers"
)

// Ack status values returned by the sidecar.
const (
	AckAccepted  = "accepted"
	AckCompleted = "completed"
	AckFailed    = "failed"
)

// WSCommand represents an outbound command sent to a sidecar.
//
// Wire format documented in SPEC.md §5.0 — the JSON field names below
// (json tags) are the canonical wire keys. If you rename a Go field,
// update the json tag and the schema doc; TestWS_WireFormat will catch
// any drift.
type WSCommand struct {
	Type      string          `json:"type"`
	CommandID string          `json:"command_id"`
	Payload   json.RawMessage `json:"payload"`
}

// WSAck is an acknowledgement received from the sidecar.
//
// Wire format documented in SPEC.md §5.0. The Kotlin sidecar reads
// these exact JSON keys. See sidecar/.../models.kt for the companion
// Kotlin type. TestWS_WireFormat validates the serialized output.
type WSAck struct {
	AckType   string `json:"ack_type"`
	CommandID string `json:"command_id"`
	Status    string `json:"status"`
	Error     string `json:"error,omitempty"`
}

// pendingCommand tracks a single command's lifecycle for a device.
type pendingCommand struct {
	Cmd    WSCommand
	Status string // sent, accepted, completed, failed
	Error  string // populated on failed acks
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

	// deviceAuth gates the command channel: only paired devices that
	// present their auth token may connect. Nil disables enforcement
	// (tests only).
	deviceAuth DeviceAuthorizer
}

// SetDeviceAuthorizer enables pairing enforcement on the WebSocket
// command channel.
func (h *WSHandler) SetDeviceAuthorizer(a DeviceAuthorizer) {
	h.deviceAuth = a
}

// checkOrigin validates WebSocket upgrade requests against CSRF/DNS rebinding.
// Allows empty origin (app clients) and same-origin requests. Rejects
// cross-origin requests which would only come from attacker-controlled pages.
func checkOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true // app client, not a browser
	}

	// Parse origin URL properly — handles scheme, userinfo, IPv6, ports
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" {
		return false
	}

	// Reject origins with credentials (user:pass@host) — these can only
	// come from attacker-controlled pages.
	if u.User != nil {
		return false
	}

	rHost := r.Host

	// Compare schemes: require origin scheme to match the request scheme.
	// Prevents attacks that rely on scheme confusion (e.g. http vs https
	// with same host:port).
	if normalizeScheme(u.Scheme) != normalizeScheme(schemeForRequest(r)) {
		return false
	}

	// Normalize: use Hostname()/Port() which handle IPv6 correctly.
	oHost := u.Hostname()
	oPort := u.Port()
	rHostName, rPort, _ := net.SplitHostPort(rHost)
	if rHostName == "" {
		rHostName = rHost
		rPort = ""
	}

	// If ports differ, check if one is the default for a scheme
	if oPort != rPort {
		oDefault := portForScheme(u.Scheme)
		rDefault := portForScheme(schemeForRequest(r))
		if oPort == oDefault || oPort == "" && oDefault == rPort ||
			rPort == rDefault || rPort == "" && rDefault == oPort {
			// Ports differ by default only — treat as match
		} else {
			return false
		}
	}

	return oHost == rHostName
}

// portForScheme returns the default port for common schemes.
func portForScheme(scheme string) string {
	switch scheme {
	case "https", "wss":
		return "443"
	case "http", "ws":
		return "80"
	default:
		return ""
	}
}

// normalizeScheme maps WebSocket schemes to their HTTP equivalents.
func normalizeScheme(scheme string) string {
	switch scheme {
	case "ws":
		return "http"
	case "wss":
		return "https"
	default:
		return scheme
	}
}

// schemeForRequest infers a scheme from the request.
func schemeForRequest(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	// Detect reverse-proxy scenarios where TLS terminates upstream
	// but the request still arrives on the HTTPS default port.
	_, rPort, _ := net.SplitHostPort(r.Host)
	if rPort == "443" {
		return "https"
	}
	return "http"
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
			CheckOrigin:     checkOrigin,
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

	// The command channel is for paired devices only. An unauthenticated
	// connect would let anyone on the LAN claim a device_id and hijack
	// that device's command stream (model loads, shutdown, etc.).
	if h.deviceAuth != nil {
		if !h.deviceAuth.IsPaired(deviceID) {
			http.Error(w, "device is not paired", http.StatusForbidden)
			return
		}
		if !h.deviceAuth.Authorize(deviceID, r.Header.Get(DeviceTokenHeader)) {
			h.log.Warn("ws connection rejected: invalid device token",
				"device_id", deviceID, "remote", r.RemoteAddr)
			http.Error(w, "missing or invalid "+DeviceTokenHeader+" header", http.StatusUnauthorized)
			return
		}
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.log.Error("websocket upgrade failed", "device_id", deviceID, "error", err)
		return
	}

	dc := &deviceConn{conn: conn, deviceID: deviceID}

	// Enforce read limits and deadline — no anonymous pong handler needed
	// because the gorilla/websocket library fires pongs automatically for
	// every received pong frame; we just need to reset the deadline.
	conn.SetReadLimit(wsReadLimit)
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(wsPongWait))
	})
	if err := conn.SetReadDeadline(time.Now().Add(wsPongWait)); err != nil {
		h.log.Warn("failed to set ws read deadline", "device_id", deviceID, "error", err)
	}

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
	if ack.AckType != "ack" {
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
		pc.Status = AckAccepted
	case AckCompleted:
		pc.Status = AckCompleted
		delete(devicePending, ack.CommandID)
	case AckFailed:
		pc.Status = AckFailed
		pc.Error = ack.Error
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

	// Send immediately if connected (hold mu to prevent concurrent write)
	if dc != nil {
		if err := dc.conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout)); err != nil {
			h.mu.Unlock()
			return cmd.CommandID, err
		}
		if err := dc.conn.WriteJSON(cmd); err != nil {
			h.mu.Unlock()
			h.log.Error("failed to send command", "device_id", deviceID, "command_id", cmd.CommandID, "error", err)
			return cmd.CommandID, err
		}
	}
	h.mu.Unlock()

	return cmd.CommandID, nil
}

// SendModelPush sends a model_push command to a device.
func (h *WSHandler) SendModelPush(deviceID, model, url, checksum string, sizeBytes int64) (string, error) {
	return h.SendCommand(deviceID, CmdModelPush, map[string]any{
		"model": model, "url": url, "checksum": checksum, "size_bytes": sizeBytes,
	})
}

// SendModelLoad sends a model_load command. checksum is optional (empty string to skip).
func (h *WSHandler) SendModelLoad(deviceID, model, backend, checksum string) (string, error) {
	payload := map[string]string{"model": model, "backend": backend}
	if checksum != "" {
		payload["checksum"] = checksum
	}
	return h.SendCommand(deviceID, CmdModelLoad, payload)
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

// ── Visualization commands ──

// ArrangementEntry represents one device's position in the spatial layout.
type ArrangementEntry struct {
	DeviceID      string  `json:"device_id"`
	DisplayNumber int     `json:"display_number"`
	PositionX     float64 `json:"position_x"`
	PositionY     float64 `json:"position_y"`
}

// SendVizSwitch sends a viz_switch command to a single device.
func (h *WSHandler) SendVizSwitch(deviceID, packID string) (string, error) {
	return h.SendCommand(deviceID, CmdVizSwitch, map[string]string{
		"pack_id": packID,
	})
}

// SendVizConfig sends a viz_config command to a single device.
func (h *WSHandler) SendVizConfig(deviceID string, config map[string]string) (string, error) {
	return h.SendCommand(deviceID, CmdVizConfig, map[string]any{
		"config": config,
	})
}

// SendVizArrangement sends a viz_arrangement command to a single device.
func (h *WSHandler) SendVizArrangement(deviceID string, entries []ArrangementEntry) (string, error) {
	return h.SendCommand(deviceID, CmdVizArrangement, map[string]any{
		"entries": entries,
	})
}

// SendVizShowNumbers sends a viz_show_numbers command to a single device.
func (h *WSHandler) SendVizShowNumbers(deviceID string, visible bool) (string, error) {
	return h.SendCommand(deviceID, CmdVizShowNumbers, map[string]bool{
		"visible": visible,
	})
}

// BroadcastVizSwitch sends a viz_switch command to all connected devices.
func (h *WSHandler) BroadcastVizSwitch(packID string) {
	devices := h.ConnectedDevices()
	for _, deviceID := range devices {
		if _, err := h.SendVizSwitch(deviceID, packID); err != nil {
			h.log.Error("broadcast viz_switch failed", "device_id", deviceID, "error", err)
		}
	}
}

// BroadcastVizArrangement sends the arrangement to all connected devices.
func (h *WSHandler) BroadcastVizArrangement(entries []ArrangementEntry) {
	devices := h.ConnectedDevices()
	for _, deviceID := range devices {
		if _, err := h.SendVizArrangement(deviceID, entries); err != nil {
			h.log.Error("broadcast viz_arrangement failed", "device_id", deviceID, "error", err)
		}
	}
}

// BroadcastVizShowNumbers sends show-numbers toggle to all connected devices.
func (h *WSHandler) BroadcastVizShowNumbers(visible bool) {
	devices := h.ConnectedDevices()
	for _, deviceID := range devices {
		if _, err := h.SendVizShowNumbers(deviceID, visible); err != nil {
			h.log.Error("broadcast viz_show_numbers failed", "device_id", deviceID, "error", err)
		}
	}
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
	defer h.mu.Unlock()

	devicePending := h.pending[deviceID]
	dc := h.devices[deviceID]

	if dc == nil || len(devicePending) == 0 {
		return
	}

	toResend := make([]pendingCmd, 0, len(devicePending))
	toRemove := make([]string, 0, len(devicePending))

	for cmdID, pc := range devicePending {
		if pc.Status == AckCompleted || pc.Status == AckFailed {
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

	for _, pc := range toResend {
		if err := dc.conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout)); err != nil {
			h.log.Error("failed to set ws write deadline during resend", "device_id", deviceID, "error", err)
			return
		}
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
