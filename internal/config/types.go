package config

// ClusterConfig defines the top-level cluster settings.
type ClusterConfig struct {
	Name       string           `yaml:"name"`
	Auth       AuthConfig       `yaml:"auth"`
	Networking NetworkingConfig `yaml:"networking"`
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
