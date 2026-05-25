package registry

import "time"

// NodeState tracks the lifecycle of a phone in the cluster.
type NodeState string

const (
	NodeStateUnpaired NodeState = "unpaired"
	NodeStatePaired   NodeState = "paired"
	NodeStateOnline   NodeState = "online"
	NodeStateOffline  NodeState = "offline"
)

// HealthTelemetry captures battery and thermal state from heartbeats.
type HealthTelemetry struct {
	BatteryLevel      float64   `json:"battery_level"`
	ThermalTempC      float64   `json:"thermal_temp_c"`
	IsCharging        bool      `json:"is_charging"`
	HeartbeatRecorded time.Time `json:"-"`
}

// ModelStatus describes what model is loaded and its state.
type ModelStatus struct {
	Name       string `json:"name"`
	Loaded     bool   `json:"loaded"`
	LoadError  string `json:"load_error,omitempty"`
	LoadedAt   time.Time `json:"loaded_at,omitempty"`
}

// Node represents a single phone in the cluster.
type Node struct {
	DeviceID    string         `json:"device_id"`    // hardware serial
	Name        string         `json:"name"`          // human-friendly (auto-gen or operator-set)
	DeviceModel string         `json:"device_model"` // e.g. "Pixel 9 Pro"
	Group       string         `json:"group"`         // group name, empty if unassigned
	State       NodeState      `json:"state"`
	Telemetry   HealthTelemetry `json:"telemetry"`
	ModelStatus ModelStatus    `json:"model_status"`
	RegisteredAt time.Time    `json:"registered_at"`
	PairedAt    time.Time     `json:"paired_at,omitempty"`
	LastHeartbeat time.Time   `json:"last_heartbeat,omitempty"`
	IPAddress   string         `json:"ip_address,omitempty"`
	ExcludeReason string      `json:"exclude_reason,omitempty"` // empty = healthy; "overheating", "low-battery", "degraded"
}
