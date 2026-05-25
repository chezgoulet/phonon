package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
	"github.com/gorilla/websocket"
)

func setupWSTest(t *testing.T) (*WSHandler, *registry.Registry, *httptest.Server, *websocket.Conn) {
	t.Helper()
	reg := registry.New()
	ws := NewWSHandler(reg)

	mux := http.NewServeMux()
	ws.RegisterRoutes(mux)

	server := httptest.NewServer(mux)

	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/api/v1/sidecar/ws?device_id=TEST-001"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		server.Close()
		t.Fatalf("websocket dial failed: %v", err)
	}

	return ws, reg, server, conn
}

func readCommand(t *testing.T, conn *websocket.Conn, timeout time.Duration) WSCommand {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(timeout))
	_, msg, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read message error: %v", err)
	}
	var cmd WSCommand
	if err := json.Unmarshal(msg, &cmd); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}
	return cmd
}

func sendAck(t *testing.T, conn *websocket.Conn, cmdID, status string) {
	t.Helper()
	if err := conn.WriteJSON(map[string]string{
		"type": "ack", "command_id": cmdID, "status": status,
	}); err != nil {
		t.Fatalf("write ack error: %v", err)
	}
}

func pendingCount(t *testing.T, ws *WSHandler, deviceID string) int {
	t.Helper()
	ws.mu.RLock()
	defer ws.mu.RUnlock()
	return len(ws.pending[deviceID])
}

func TestWS_ConnectAndCommandFlow(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()
	defer conn.Close()

	ws.reg.Register("TEST-001", "pixel-test", "192.168.1.10")

	cmdID, err := ws.SendModelPush("TEST-001", "gemma-4-E2B-it", "http://coord:8080/models/gemma", "sha256:abc", 2000000000)
	if err != nil {
		t.Fatalf("SendModelPush() error: %v", err)
	}
	if cmdID == "" {
		t.Fatal("expected non-empty command ID")
	}

	cmd := readCommand(t, conn, time.Second)
	if cmd.Type != "model_push" {
		t.Errorf("expected type model_push, got %q", cmd.Type)
	}
	if cmd.CommandID != cmdID {
		t.Errorf("expected command_id %q, got %q", cmdID, cmd.CommandID)
	}

	// Ack as completed, verify removed from pending
	sendAck(t, conn, cmdID, "completed")
	time.Sleep(30 * time.Millisecond)

	if n := pendingCount(t, ws, "TEST-001"); n != 0 {
		t.Errorf("expected 0 pending after completed, got %d", n)
	}
}

func TestWS_AllCommandTypes(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()
	defer conn.Close()

	ws.reg.Register("TEST-001", "pixel-test", "")

	tests := []struct {
		name    string
		cmdType string
		send    func() (string, error)
	}{
		{"model_push", "model_push", func() (string, error) { return ws.SendModelPush("TEST-001", "m", "u", "c", 1) }},
		{"model_load", "model_load", func() (string, error) { return ws.SendModelLoad("TEST-001", "gemma-4-E2B-it") }},
		{"model_unload", "model_unload", func() (string, error) { return ws.SendModelUnload("TEST-001") }},
		{"mode_change", "mode_change", func() (string, error) { return ws.SendModeChange("TEST-001", "pool", "litert") }},
		{"standby_promote", "standby_promote", func() (string, error) { return ws.SendStandbyPromote("TEST-001", "g", "u", "c") }},
		{"shutdown", "shutdown", func() (string, error) { return ws.SendShutdown("TEST-001", "testing") }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cmdID, err := tt.send()
			if err != nil {
				t.Fatalf("send error: %v", err)
			}

			cmd := readCommand(t, conn, 3*time.Second)
			if cmd.Type != tt.cmdType {
				t.Errorf("expected type %q, got %q", tt.cmdType, cmd.Type)
			}
			if cmd.CommandID != cmdID {
				t.Errorf("expected command_id %q, got %q", cmdID, cmd.CommandID)
			}

			sendAck(t, conn, cmdID, "accepted")
			sendAck(t, conn, cmdID, "completed")
		})
	}
}

func TestWS_CommandLifecycle(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()
	defer conn.Close()

	ws.reg.Register("TEST-001", "pixel-test", "")

	cmdID, err := ws.SendModelLoad("TEST-001", "gemma-4-E2B-it")
	if err != nil {
		t.Fatalf("send error: %v", err)
	}

	cmd := readCommand(t, conn, time.Second)
	if cmd.CommandID != cmdID {
		t.Fatalf("expected command_id %q", cmdID)
	}

	// accepted
	sendAck(t, conn, cmdID, "accepted")
	time.Sleep(30 * time.Millisecond)

	ws.mu.RLock()
	pc := ws.pending["TEST-001"][cmdID]
	ws.mu.RUnlock()
	if pc == nil {
		t.Fatal("command should still be pending")
	}
	if pc.Status != "accepted" {
		t.Errorf("expected 'accepted', got %q", pc.Status)
	}

	// completed — should remove
	sendAck(t, conn, cmdID, "completed")
	time.Sleep(30 * time.Millisecond)

	if n := pendingCount(t, ws, "TEST-001"); n != 0 {
		t.Errorf("expected 0 pending after completed, got %d", n)
	}
}

func TestWS_CommandFailed(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()
	defer conn.Close()

	ws.reg.Register("TEST-001", "pixel-test", "")

	cmdID, _ := ws.SendModelLoad("TEST-001", "gemma-4-E2B-it")
	_ = readCommand(t, conn, time.Second)

	sendAck(t, conn, cmdID, "failed")
	time.Sleep(30 * time.Millisecond)

	if n := pendingCount(t, ws, "TEST-001"); n != 0 {
		t.Errorf("expected 0 pending after failed, got %d", n)
	}
}

func TestWS_HasConnection(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()
	defer conn.Close()

	if !ws.HasConnection("TEST-001") {
		t.Error("expected HasConnection true for connected device")
	}
	if ws.HasConnection("NONEXISTENT") {
		t.Error("expected HasConnection false for nonexistent device")
	}
}

func TestWS_ConnectedDevices(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()
	defer conn.Close()

	devices := ws.ConnectedDevices()
	if len(devices) != 1 {
		t.Fatalf("expected 1 connected device, got %d", len(devices))
	}
	if devices[0] != "TEST-001" {
		t.Errorf("expected TEST-001, got %q", devices[0])
	}
}

func TestWS_MissingDeviceID(t *testing.T) {
	ws := NewWSHandler(registry.New())
	mux := http.NewServeMux()
	ws.RegisterRoutes(mux)
	server := httptest.NewServer(mux)
	defer server.Close()

	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/api/v1/sidecar/ws"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		resp.Body.Close()
		t.Fatal("expected error for missing device_id")
	}
}

func TestWS_SendToOfflineDevice(t *testing.T) {
	ws, _, server, _ := setupWSTest(t)
	defer server.Close()

	ws.reg.Register("OFFLINE-001", "offline", "")
	cmdID, err := ws.SendModelLoad("OFFLINE-001", "gemma-4-E2B-it")
	if err != nil {
		t.Fatalf("send to offline device should not error: %v", err)
	}
	if cmdID == "" {
		t.Fatal("expected non-empty command ID")
	}

	// Command should be pending (queued for when device connects)
	if n := pendingCount(t, ws, "OFFLINE-001"); n != 1 {
		t.Errorf("expected 1 pending command, got %d", n)
	}
}

func TestWS_ReconnectResendsPending(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)

	ws.reg.Register("TEST-001", "pixel-test", "")

	// Send a command
	_, err := ws.SendModelLoad("TEST-001", "gemma-4-E2B-it")
	if err != nil {
		t.Fatalf("send error: %v", err)
	}

	// Read the command (don't ack it)
	_ = readCommand(t, conn, time.Second)

	// Close old connection
	conn.Close()
	server.Close()

	// Give goroutine time to clean up
	time.Sleep(100 * time.Millisecond)

	// Verify device is no longer connected but command is still pending
	if ws.HasConnection("TEST-001") {
		t.Error("device should be disconnected")
	}
	if n := pendingCount(t, ws, "TEST-001"); n != 1 {
		t.Fatalf("expected 1 pending command after disconnect, got %d", n)
	}

	// Reconnect with the same handler
	server2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws.handleWebSocket(w, r)
	}))
	defer server2.Close()

	wsURL2 := "ws" + strings.TrimPrefix(server2.URL, "http") + "/api/v1/sidecar/ws?device_id=TEST-001"
	conn2, _, err := websocket.DefaultDialer.Dial(wsURL2, nil)
	if err != nil {
		t.Fatalf("reconnect dial failed: %v", err)
	}
	defer conn2.Close()

	// Should receive the pending command re-sent
	cmd := readCommand(t, conn2, 3*time.Second)
	if cmd.Type != "model_load" {
		t.Errorf("expected model_load on reconnect, got %q", cmd.Type)
	}
}

func TestWS_ReconnectMultiplePending(t *testing.T) {
	ws, _, server, conn := setupWSTest(t)
	defer server.Close()

	ws.reg.Register("TEST-001", "pixel-test", "")

	// Send two commands
	ws.SendModelLoad("TEST-001", "gemma-4-E2B-it")
	ws.SendModeChange("TEST-001", "pool", "litert")

	// Read both (don't ack)
	cmd1 := readCommand(t, conn, time.Second)
	cmd2 := readCommand(t, conn, time.Second)
	_ = cmd1
	_ = cmd2

	// Close
	conn.Close()
	server.Close()
	time.Sleep(100 * time.Millisecond)

	// Reconnect
	server2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws.handleWebSocket(w, r)
	}))
	defer server2.Close()

	wsURL2 := "ws" + strings.TrimPrefix(server2.URL, "http") + "/api/v1/sidecar/ws?device_id=TEST-001"
	conn2, _, err := websocket.DefaultDialer.Dial(wsURL2, nil)
	if err != nil {
		t.Fatalf("reconnect dial failed: %v", err)
	}
	defer conn2.Close()

	// Read both pending commands
	re1 := readCommand(t, conn2, 3*time.Second)
	re2 := readCommand(t, conn2, time.Second)

	if re1.CommandID == re2.CommandID {
		t.Error("got duplicate command IDs")
	}
	if re1.CommandID != cmd1.CommandID && re1.CommandID != cmd2.CommandID {
		t.Error("first re-sent command doesn't match either original")
	}
}
