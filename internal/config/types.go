package config

// OverheatConfig defines thresholds for temperature-based exclusion.
type OverheatConfig struct {
	Threshold       float64 `yaml:"threshold"`       // °C — removed from pool above this (default 45)
	ReentryThreshold float64 `yaml:"reentry_threshold"` // °C — re-entered below this (default 40)
}

// BatteryConfig defines thresholds for low-battery exclusion with hysteresis.
type BatteryConfig struct {
	LowThreshold      float64 `yaml:"low_threshold"`       // % — removed when below AND not charging (default 15)
	ReentryThreshold  float64 `yaml:"reentry_threshold"`   // % — re-entered above this (default 30)
	CapacityThreshold float64 `yaml:"capacity_threshold"`  // % — "charger-dependent" marked below this (default 80)
}

// HealthConfig controls health monitoring and automatic actions.
type HealthConfig struct {
	Overheat       OverheatConfig `yaml:"overheat"`
	Battery        BatteryConfig  `yaml:"battery"`
	OfflineTimeout string         `yaml:"offline_timeout"` // e.g. "60s" (default 60s)
}

// MDNSConfig controls mDNS auto-discovery behavior.
type MDNSConfig struct {
	Disabled bool  `yaml:"disabled"`  // default: false (mDNS enabled)
	Port     int   `yaml:"port"`      // listen port (default 0 = auto)
}

// DiscoveryConfig controls how sidecars are discovered.
type DiscoveryConfig struct {
	MDNS   MDNSConfig `yaml:"mdns"`
}

// EventLogConfig controls the SQLite-backed event log.
type EventLogConfig struct {
	Path          string `yaml:"path"`           // database file path (default "phonon.db")
	RetentionDays int    `yaml:"retention_days"` // auto-purge events older than this (default 90)
}

// QueueConfig controls backpressure for inference requests.
type QueueConfig struct {
	MaxPerNode int `yaml:"max_per_node"` // max requests queued per phone before returning 429 (default 3)
}

// TLSConfig defines optional TLS/mTLS settings.
type TLSConfig struct {
	Enabled  bool   `yaml:"enabled"`   // enable HTTPS
	CertFile string `yaml:"cert_file"` // path to TLS certificate (PEM)
	KeyFile  string `yaml:"key_file"`  // path to TLS private key (PEM)
	// Self-signed certs are acceptable for LAN deployments.
	// Generate with: openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
	//   -keyout phonon.key -out phonon.crt -subj "/CN=phonon.local"
}

// ClusterConfig defines the top-level cluster settings.
type ClusterConfig struct {
	Name       string           `yaml:"name"`
	Auth       AuthConfig       `yaml:"auth"`
	TLS        TLSConfig        `yaml:"tls"`
	Networking NetworkingConfig `yaml:"networking"`
	Discovery  DiscoveryConfig  `yaml:"discovery"`
	Health     HealthConfig     `yaml:"health"`
	EventLog   EventLogConfig   `yaml:"event_log"`
	Queue      QueueConfig      `yaml:"queue"`
}

// AuthConfig defines authentication for the coordinator API.
type AuthConfig struct {
	Mode     string `yaml:"mode"`      // "oidc" or "none"
	Issuer   string `yaml:"issuer"`    // OIDC issuer URL
	ClientID string `yaml:"client_id"` // OIDC client ID
}

// NetworkingConfig controls how phones connect to the coordinator.
type NetworkingConfig struct {
	Prefer string `yaml:"prefer"` // "ethernet" or "wifi"
}

// GroupMode represents the inference mode: pool or shard.
type GroupMode string

const (
	ModePool  GroupMode = "pool"
	ModeShard GroupMode = "shard"
)

// Runtime represents the inference runtime engine.
type Runtime string

const (
	RuntimeLitert Runtime = "litert"
	RuntimePrima  Runtime = "prima"
)

// GroupConfig defines a single inference group.
type GroupConfig struct {
	Name        string    `yaml:"name"`
	Mode        GroupMode `yaml:"mode"`
	Model       string    `yaml:"model"`
	Runtime     Runtime   `yaml:"runtime"`
	Phones      []string  `yaml:"phones"`
	Standby     []string  `yaml:"standby,omitempty"`
	DownloadURL string    `yaml:"download_url,omitempty"`
	Checksum    string    `yaml:"checksum,omitempty"`
}

// Config is the top-level parsed configuration.
type Config struct {
	Cluster ClusterConfig `yaml:"cluster"`
	Groups  []GroupConfig `yaml:"groups"`
}
