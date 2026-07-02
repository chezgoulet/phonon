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
	Overheat          OverheatConfig `yaml:"overheat"`
	Battery           BatteryConfig  `yaml:"battery"`
	DrainingThreshold float64        `yaml:"draining_threshold"` // % — enter draining when unplugged below this (default 50)
	OfflineTimeout    string         `yaml:"offline_timeout"`    // e.g. "60s" (default 60s)
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

// RateLimitConfig controls the global token-bucket rate limiter applied to
// the protected /api/v1/ routes (sidecar routes are exempt).
type RateLimitConfig struct {
	Enabled         bool    `yaml:"enabled"`           // default false
	TokensPerSecond float64 `yaml:"tokens_per_second"` // refill rate (default 10)
	Burst           int     `yaml:"burst"`             // bucket size (default 20)
}

// ModelsConfig controls model management endpoints.
type ModelsConfig struct {
	// UploadMaxBytes caps POST /api/v1/models/upload file size
	// (default 8 GiB).
	UploadMaxBytes int64 `yaml:"upload_max_bytes"`
}

// RedisConfig controls connection to a Redis server for HA state sharing.
type RedisConfig struct {
	Enabled  bool   `yaml:"enabled"`   // enable Redis-backed pairing store (HA mode)
	Addr     string `yaml:"addr"`      // host:port (default "localhost:6379")
	Password string `yaml:"password"`  // optional AUTH password
	DB       int    `yaml:"db"`        // Redis DB number (default 0)
	Key      string `yaml:"key"`       // Redis key for paired devices (default "phonon:paired")
}

// PairingConfig controls device pairing state persistence.
type PairingConfig struct {
	Redis RedisConfig `yaml:"redis"` // HA mode: Redis-backed store
}

// TLSConfig defines optional TLS/mTLS settings.
type TLSConfig struct {
	Enabled       bool   `yaml:"enabled"`        // enable HTTPS
	CertFile      string `yaml:"cert_file"`      // path to TLS certificate (PEM)
	KeyFile       string `yaml:"key_file"`       // path to TLS private key (PEM)
	ClientCAFile  string `yaml:"client_ca_file"` // path to CA cert for mTLS client verification (PEM)

	// SelfSigned enables auto-generation of a self-signed server cert
	// signed by the coordinator's Ed25519-derived CA. Ignored when
	// cert_file and key_file are explicitly set.
	// Self-signed certs are acceptable for LAN deployments.
	SelfSigned bool `yaml:"self_signed"`
}

// ClusterConfig defines the top-level cluster settings.
type ClusterConfig struct {
	Name       string           `yaml:"name"`
	Bind       string           `yaml:"bind"`       // listen address, e.g. ":8080" or "127.0.0.1:8080" (default ":8080")
	Auth       AuthConfig       `yaml:"auth"`
	TLS        TLSConfig        `yaml:"tls"`
	Networking NetworkingConfig `yaml:"networking"`
	Discovery  DiscoveryConfig  `yaml:"discovery"`
	Health     HealthConfig     `yaml:"health"`
	EventLog   EventLogConfig   `yaml:"event_log"`
	Queue      QueueConfig      `yaml:"queue"`
	Pairing    PairingConfig    `yaml:"pairing"`

	// RateLimiting throttles the protected API routes (see RateLimitConfig).
	RateLimiting RateLimitConfig `yaml:"rate_limiting"`

	// Models controls model management endpoints (see ModelsConfig).
	Models ModelsConfig `yaml:"models"`

	// InferencePort is the port the sidecar's inference server listens on.
	// Must match the sidecar's PHONON_INFERENCE_PORT override (default 9876).
	InferencePort int `yaml:"inference_port"`
}

// AuthConfig defines authentication for the coordinator API.
type AuthConfig struct {
	Mode     string `yaml:"mode"`      // "oidc", "psk", or "none"
	Issuer   string `yaml:"issuer"`    // OIDC issuer URL
	ClientID string `yaml:"client_id"` // OIDC client ID
	PSK      string `yaml:"psk"`       // pre-shared key for "psk" mode (or set PHONON_PSK env var)
}

// NetworkingConfig controls how phones connect to the coordinator.
type NetworkingConfig struct {
	Prefer string   `yaml:"prefer"` // "ethernet" or "wifi"

	// CORSOrigins is an explicit allowlist for Access-Control-Allow-Origin.
	// When set, only requests whose Origin matches an entry are allowed.
	// Wildcards are not supported except as a single explicit "*" entry
	// (which restores the insecure default). When empty, defaults to "*"
	// with a startup warning.
	//
	// Examples:
	//   cors_origins:
	//     - https://phonon.example.com
	//     - http://localhost:5173
	CORSOrigins []string `yaml:"cors_origins"`
}

// GroupMode represents the inference mode: pool or shard.
// Shard mode is experimental and deferred until the upstream runtime ships.
type GroupMode string

const (
	ModePool  GroupMode = "pool"
	ModeShard GroupMode = "shard" // experimental — upstream runtime not yet shipped
)

// Runtime represents the inference runtime engine.
type Runtime string

const (
	RuntimeLitert Runtime = "litert"
	RuntimePrima  Runtime = "prima"
)

// Backend selects the hardware accelerator for inference on the phone.
//
// "auto" (the default) lets the sidecar pick the best available backend
// for its hardware, falling back NPU → GPU → CPU. Explicit values pin the
// backend; if initialization fails the sidecar still falls back to CPU and
// reports the active backend in heartbeats, so a misconfigured group
// degrades to working-but-slow rather than dead.
type Backend string

const (
	BackendAuto Backend = "auto"
	BackendNPU  Backend = "npu"
	BackendGPU  Backend = "gpu"
	BackendCPU  Backend = "cpu"
)

// GroupConfig defines a single inference group.
type GroupConfig struct {
	Name        string    `yaml:"name"`
	Mode        GroupMode `yaml:"mode"`
	Model       string    `yaml:"model"`
	Runtime     Runtime   `yaml:"runtime"`
	Backend     Backend   `yaml:"backend,omitempty"` // accelerator: auto (default), npu, gpu, cpu
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
