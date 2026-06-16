package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
	"github.com/gorilla/websocket"
)

// setupSidecarServer creates a full coordinator test server with sidecar
// API routes and WebSocket handler registered under the same mux, simulating
// the production coordinator setup.
func setupSidecarServer(t *testing.T) (*WSHandler, *httptest.Server) {
	t.Helper()
	reg := registry.New()
	ws := NewWSHandler(reg)
	sh := NewSidecarHandler(reg)

	mux := http.NewServeMux()
	sh.RegisterRoutes(mux)
	ws.RegisterRoutes(mux)

	server := httptest.NewServer(mux)
	return ws, server
}

// sidecarRegister performs a REST registration as the Kotlin sidecar would.
func sidecarRegister(t *testing.T, baseURL, deviceID string) {
	t.Helper()
	body := map[string]any{
		"device_id":         deviceID,
		"device_model":      "pixel-test",
		"android_version":   "15",
		"ip_address":        "10.0.0.5",
		"network_interface": "wlan0",
	}
	b, _ := json.Marshal(body)
	resp, err := http.Post(baseURL+"/api/v1/sidecar/register", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatalf("register request failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		t.Fatalf("register returned HTTP %d", resp.StatusCode)
	}
}

// sidecarConnectWS connects a WebSocket as a given device, simulating the
// Kotlin sidecar's WebSocket connection flow.
func sidecarConnectWS(t *testing.T, baseURL, deviceID string) *websocket.Conn {
	t.Helper()
	wsURL := "ws" + strings.TrimPrefix(baseURL, "http") + "/api/v1/sidecar/ws?device_id=" + deviceID
	conn, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		if resp != nil {
			resp.Body.Close()
		}
		t.Fatalf("websocket dial failed for %s: %v", deviceID, err)
	}
	resp.Body.Close()
	return conn
}

// readWSCommand reads a single WSCommand from the WebSocket.
func readWSCommand(t *testing.T, conn *websocket.Conn) WSCommand {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, msg, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read ws command error: %v", err)
	}
	var cmd WSCommand
	if err := json.Unmarshal(msg, &cmd); err != nil {
		t.Fatalf("unmarshal ws command error: %v", err)
	}
	return cmd
}

// sendWSAck sends a typed ack over WebSocket.
func sendWSAck(t *testing.T, conn *websocket.Conn, cmdID, status string) {
	t.Helper()
	if err := conn.WriteJSON(map[string]string{
		"ack_type": "ack", "command_id": cmdID, "status": status,
	}); err != nil {
		t.Fatalf("write ack error: %v", err)
	}
}

// ─── Integration Tests ───

// TestIntegration_FullSidecarLifecycle verifies the complete coordinator-sidecar
// lifecycle as the Kotlin sidecar would experience it:
//
//  1. REST registration
//  2. WebSocket connection
//  3. Heartbeat delivery
//  4. Model push command receipt
//  5. Command acknowledgment
//  6. Model status query
//
// This is the cross-protocol integration test requested in issue #119.
func TestIntegration_FullSidecarLifecycle(t *testing.T) {
	ws, server := setupSidecarServer(t)
	defer server.Close()

	// Step 1: Register as a new sidecar via REST (Kotlin registerWithCoordinator)
	sidecarRegister(t, server.URL, "PHONE-001")

	// Verify the device is in the registry
	node, ok := ws.reg.Get("PHONE-001")
	if !ok {
		t.Fatal("device should be registered")
	}
	if node.Name != "pixel-test--001" {
		t.Errorf("expected name 'pixel-test--001', got %q", node.Name)
	}

	// Step 2: Connect WebSocket (Kotlin connectWebSocket)
	conn := sidecarConnectWS(t, server.URL, "PHONE-001")
	defer conn.Close()

	// Poll until handler tracks the connection
	for i := 0; i < 100; i++ {
		if ws.HasConnection("PHONE-001") {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !ws.HasConnection("PHONE-001") {
		t.Fatal("device should have WebSocket connection")
	}

	// Step 3: Send heartbeat (Kotlin sendHeartbeat)
	heartbeatBody := map[string]any{
		"device_id":  "PHONE-001",
		"battery":    map[string]any{"level": 0.85, "charging": true, "capacity_pct": 78.0},
		"thermal":    map[string]any{"soc_temp_c": 36.5},
		"storage":    map[string]any{"total_gb": 128.0, "free_gb": 64.0},
		"queue_depth": 0,
		"network":    "wlan0",
		"model":      map[string]any{"loaded": nil},
		"timestamp":  time.Now().UTC().Format(time.RFC3339),
	}
	b, _ := json.Marshal(heartbeatBody)
	resp, err := http.Post(server.URL+"/api/v1/sidecar/heartbeat", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatalf("heartbeat request failed: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("heartbeat returned HTTP %d", resp.StatusCode)
	}

	// Verify heartbeat updated the registry
	node2, _ := ws.reg.Get("PHONE-001")
	if node2.Telemetry.BatteryLevel != 0.85 {
		t.Errorf("expected battery 0.85, got %f", node2.Telemetry.BatteryLevel)
	}
	if !node2.Telemetry.IsCharging {
		t.Error("expected battery charging")
	}

	// Step 4: Coordinator sends a model push command
	cmdID, err := ws.SendModelPush("PHONE-001", "gemma-4-E2B-it",
		"http://coord:8080/models/gemma", "sha256:abc123", 2000000000)
	if err != nil {
		t.Fatalf("SendModelPush() error: %v", err)
	}
	if cmdID == "" {
		t.Fatal("expected non-empty command ID")
	}

	// Step 5: Sidecar receives the command (Kotlin handleCommand → ModelPush)
	cmd := readWSCommand(t, conn)
	if cmd.Type != "model_push" {
		t.Errorf("expected type 'model_push', got %q", cmd.Type)
	}
	if cmd.CommandID != cmdID {
		t.Errorf("expected command_id %q, got %q", cmdID, cmd.CommandID)
	}

	// Verify payload fields match what the Kotlin sidecar parses
	var payload map[string]any
	if err := json.Unmarshal(cmd.Payload, &payload); err != nil {
		t.Fatalf("unmarshal payload error: %v", err)
	}
	if model, ok := payload["model"]; !ok || model != "gemma-4-E2B-it" {
		t.Errorf("expected model 'gemma-4-E2B-it' in payload, got %v", model)
	}
	if checksum, ok := payload["checksum"]; !ok || checksum != "sha256:abc123" {
		t.Errorf("expected checksum 'sha256:abc123' in payload, got %v", checksum)
	}

	// Step 6: Sidecar acks accepted, then completed (Kotlin sendCommandAck)
	sendWSAck(t, conn, cmdID, "accepted")
	time.Sleep(30 * time.Millisecond)

	// Verify accepted state on coordinator side
	ws.mu.RLock()
	pc := ws.pending["PHONE-001"][cmdID]
	ws.mu.RUnlock()
	if pc == nil {
		t.Fatal("command should still be pending after accepted ack")
	}
	if pc.Status != "accepted" {
		t.Errorf("expected status 'accepted', got %q", pc.Status)
	}

	sendWSAck(t, conn, cmdID, "completed")
	time.Sleep(30 * time.Millisecond)

	// Verify completed command is cleaned up
	ws.mu.RLock()
	n := len(ws.pending["PHONE-001"])
	ws.mu.RUnlock()
	if n != 0 {
		t.Errorf("expected 0 pending commands after completed, got %d", n)
	}

	// Step 7: Query model status
	// POST model-status
	modelBody := map[string]any{
		"device_id": "PHONE-001",
		"loaded":    nil,
	}
	mb, _ := json.Marshal(modelBody)
	resp2, err := http.Post(server.URL+"/api/v1/sidecar/model-status", "application/json", bytes.NewReader(mb))
	if err != nil {
		t.Fatalf("model status request failed: %v", err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Errorf("model status returned HTTP %d, expected 200", resp2.StatusCode)
	}
}

// TestIntegration_RegistrationThenWebSocket verifies that a device registered
// via REST can immediately connect via WebSocket and that the handler recognizes
// the registration.
func TestIntegration_RegistrationThenWebSocket(t *testing.T) {
	ws, server := setupSidecarServer(t)
	defer server.Close()

	// Register
	sidecarRegister(t, server.URL, "PHONE-002")
	node, ok := ws.reg.Get("PHONE-002")
	if !ok {
		t.Fatal("device should be registered after REST call")
	}
	if node.State != "unpaired" {
		t.Errorf("expected state 'unpaired', got %q", node.State)
	}

	// Connect WS with the same device ID
	conn := sidecarConnectWS(t, server.URL, "PHONE-002")
	defer conn.Close()

	// Send a pending command for the offline device first, then connect
	cmdID, _ := ws.SendModelLoad("PHONE-002", "gemma-4-E2B-it", "auto")
	cmd := readWSCommand(t, conn)
	if cmd.CommandID != cmdID {
		t.Errorf("expected command_id %q on reconnect, got %q", cmdID, cmd.CommandID)
	}
}

// TestIntegration_FailedPushAck verifies that a failed ack with error message
// is properly tracked and the command cleaned up.
func TestIntegration_FailedPushAck(t *testing.T) {
	ws, server := setupSidecarServer(t)
	defer server.Close()

	sidecarRegister(t, server.URL, "PHONE-003")
	conn := sidecarConnectWS(t, server.URL, "PHONE-003")
	defer conn.Close()

	// Wait for connection registration
	for i := 0; i < 100; i++ {
		if ws.HasConnection("PHONE-003") {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	cmdID, _ := ws.SendModelPush("PHONE-003", "gemma-4-E2B-it",
		"http://coord:8080/models/gemma", "sha256:abc", 2000000000)
	_ = readWSCommand(t, conn)

	// Sidecar reports failure with error reason (as Kotlin sendCommandAck does)
	if err := conn.WriteJSON(map[string]string{
		"ack_type": "ack", "command_id": cmdID, "status": "failed",
		"error": "checksum mismatch",
	}); err != nil {
		t.Fatalf("write ack error: %v", err)
	}
	time.Sleep(30 * time.Millisecond)

	// Command should be removed from pending
	ws.mu.RLock()
	n := len(ws.pending["PHONE-003"])
	ws.mu.RUnlock()
	if n != 0 {
		t.Errorf("expected 0 pending commands after failed ack, got %d", n)
	}
}
