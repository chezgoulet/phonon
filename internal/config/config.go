package config

import (
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// LoadFile parses phonon.yaml from a file path.
func LoadFile(path string) (*Config, *ValidationResult, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, nil, fmt.Errorf("read config: %w", err)
	}
	return Load(data)
}

// setDefaults applies default values for unset config fields.
func (c *Config) setDefaults() {
	// Health defaults
	h := &c.Cluster.Health
	if h.Overheat.Threshold == 0 {
		h.Overheat.Threshold = 45
	}
	if h.Overheat.ReentryThreshold == 0 {
		h.Overheat.ReentryThreshold = 40
	}
	if h.Battery.LowThreshold == 0 {
		h.Battery.LowThreshold = 15
	}
	if h.Battery.ReentryThreshold == 0 {
		h.Battery.ReentryThreshold = 30
	}
	if h.Battery.CapacityThreshold == 0 {
		h.Battery.CapacityThreshold = 80
	}
	if h.OfflineTimeout == "" {
		h.OfflineTimeout = "60s"
	}

	// Queue defaults
	if c.Cluster.Queue.MaxPerNode == 0 {
		c.Cluster.Queue.MaxPerNode = 3
	}

	// Pairing / Redis defaults
	p := &c.Cluster.Pairing
	if p.Redis.Addr == "" {
		p.Redis.Addr = "localhost:6379"
	}
	if p.Redis.Key == "" {
		p.Redis.Key = "phonon:paired"
	}

	// Bind default — :8080
	if c.Cluster.Bind == "" {
		port := os.Getenv("PHONON_PORT")
		if port == "" {
			port = "8080"
		}
		c.Cluster.Bind = ":" + port
	}
}

// OfflineTimeoutDuration parses the offline timeout string into a Duration.
// Returns 60s if unset or invalid.
func (h *HealthConfig) OfflineTimeoutDuration() time.Duration {
	d, err := time.ParseDuration(h.OfflineTimeout)
	if err != nil || d <= 0 {
		return 60 * time.Second
	}
	return d
}

// Load parses and validates a phonon.yaml byte slice.
func Load(data []byte) (*Config, *ValidationResult, error) {
	result := &ValidationResult{}

	cfg := &Config{}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, result, fmt.Errorf("parse yaml: %w", err)
	}

	cfg.setDefaults()

	if err := cfg.Validate(result); err != nil {
		return nil, result, err
	}

	return cfg, result, nil
}

// ValidationResult holds warnings produced during validation.
type ValidationResult struct {
	Warnings []string
}

// KnownModels is the built-in set of recognized model names.
// Extended automatically from upstream registries at startup.
var KnownModels = map[string]bool{
	"gemma-4-E2B-it":   true,
	"gemma-4-27b-q4":   true,
	"gemma-4-9b-q4":    true,
	"gemma-4-2b-q4":    true,
	"qwen-coder-8b-q4": true,
	"qwen-2.5-7b-q4":   true,
	"llama-3.2-3b-q4":  true,
	"llama-3.2-1b-q4":  true,
	"phi-3.5-mini-q4":  true,
}

// RegisterModels adds model names to the known set.
func RegisterModels(models ...string) {
	for _, m := range models {
		KnownModels[m] = true
	}
}

// Validate checks all validation rules against the config.
func (c *Config) Validate(result *ValidationResult) error {
	if len(c.Groups) == 0 {
		return fmt.Errorf("at least one group must be defined")
	}

	// Track which groups each phone appears in and whether as standby.
	type phoneEntry struct {
		groupIndex int
		isStandby  bool
	}
	phoneMap := make(map[string][]phoneEntry)

	for gi := range c.Groups {
		if err := validateGroup(gi, &c.Groups[gi], result); err != nil {
			return err
		}

		registerPhones := func(phones []string, standby bool) {
			for _, p := range phones {
				phoneMap[p] = append(phoneMap[p], phoneEntry{
					groupIndex: gi,
					isStandby:  standby,
				})
			}
		}
		registerPhones(c.Groups[gi].Phones, false)
		registerPhones(c.Groups[gi].Standby, true)
	}

	// Cross-group phone overlap checks.
	for phone, entries := range phoneMap {
		activeGroups := make(map[int]struct{})
		for _, e := range entries {
			if !e.isStandby {
				activeGroups[e.groupIndex] = struct{}{}
			}
		}

		// Active in more than one group.
		if len(activeGroups) > 1 {
			names := make([]string, 0, len(activeGroups))
			for gi := range activeGroups {
				names = append(names, c.Groups[gi].Name)
			}
			return fmt.Errorf("phone %q appears in multiple groups: %s", phone, strings.Join(names, ", "))
		}

		// Standby in one group but active in another.
		var activeIn, standbyIn int = -1, -1
		for _, e := range entries {
			if !e.isStandby {
				activeIn = e.groupIndex
			} else {
				standbyIn = e.groupIndex
			}
		}
		if activeIn >= 0 && standbyIn >= 0 && activeIn != standbyIn {
			return fmt.Errorf(
				"phone %q is active in group %q and standby in group %q — standby cannot be active elsewhere",
				phone, c.Groups[activeIn].Name, c.Groups[standbyIn].Name,
			)
		}
	}

	// Auth mode validation and warnings.
	switch c.Cluster.Auth.Mode {
	case "":
		result.Warnings = append(result.Warnings,
			"no auth mode set — defaulting to 'none'. API is open to anyone who can reach the bind address")
		c.Cluster.Auth.Mode = "none"
	case "none":
		result.Warnings = append(result.Warnings,
			"auth mode is 'none' — API is open to anyone who can reach the bind address")
	case "oidc":
		if c.Cluster.Auth.Issuer == "" {
			return fmt.Errorf("auth mode is 'oidc' but 'issuer' is not set")
		}
		if c.Cluster.Auth.ClientID == "" {
			return fmt.Errorf("auth mode is 'oidc' but 'client_id' is not set")
		}
	case "psk":
		if c.Cluster.Auth.PSK == "" && os.Getenv("PHONON_PSK") == "" {
			return fmt.Errorf("auth mode is 'psk' but 'psk' is not set in config or PHONON_PSK env var")
		}
	default:
		return fmt.Errorf("unknown auth mode %q (must be 'none', 'psk', or 'oidc')", c.Cluster.Auth.Mode)
	}

	// Warn about non-loopback bind without auth.
	bind := c.Cluster.Bind
	if bind == "" {
		bind = ":8080"
	}
	if c.Cluster.Auth.Mode == "none" || c.Cluster.Auth.Mode == "" {
		host, _, _ := net.SplitHostPort(bind)
		if host == "" || host == "0.0.0.0" || host == "::" {
			result.Warnings = append(result.Warnings,
				"binding on all interfaces ("+bind+") with no auth — anyone on the LAN can reach the API. "+
					"Set auth.mode to 'psk' or 'oidc' for secured access, or bind '127.0.0.1' for local-only",
			)
		}
	}

	return nil
}

func validateGroup(gi int, g *GroupConfig, result *ValidationResult) error {
	if g.Name == "" {
		return fmt.Errorf("group at index %d has no name", gi)
	}
	if len(g.Phones) == 0 {
		return fmt.Errorf("group %q has no phones", g.Name)
	}

	switch g.Mode {
	case ModePool:
		if g.Runtime != RuntimeLitert {
			return fmt.Errorf("group %q: mode=pool requires runtime=litert, got %q", g.Name, g.Runtime)
		}
	case ModeShard:
		if g.Runtime != RuntimePrima {
			return fmt.Errorf("group %q: mode=shard requires runtime=prima, got %q", g.Name, g.Runtime)
		}
		result.Warnings = append(result.Warnings,
			fmt.Sprintf("group %q: mode=shard is experimental — upstream runtime not yet shipped", g.Name))
	default:
		return fmt.Errorf("group %q: unknown mode %q (must be 'pool' or 'shard')", g.Name, g.Mode)
	}

	switch g.Runtime {
	case RuntimeLitert, RuntimePrima:
	default:
		return fmt.Errorf("group %q: unknown runtime %q (must be 'litert' or 'prima')", g.Name, g.Runtime)
	}

	// Backend defaults to "auto" (sidecar picks NPU → GPU → CPU).
	if g.Backend == "" {
		g.Backend = BackendAuto
	}
	switch g.Backend {
	case BackendAuto, BackendNPU, BackendGPU, BackendCPU:
	default:
		return fmt.Errorf("group %q: unknown backend %q (must be 'auto', 'npu', 'gpu', or 'cpu')", g.Name, g.Backend)
	}
	if g.Backend != BackendAuto && g.Backend != BackendCPU && g.Runtime == RuntimePrima {
		result.Warnings = append(result.Warnings,
			fmt.Sprintf("group %q: backend %q has no effect with runtime=prima (CPU-only)", g.Name, g.Backend))
	}

	if g.Model == "" {
		return fmt.Errorf("group %q: model must be specified", g.Name)
	}
	if !KnownModels[g.Model] && g.DownloadURL == "" {
		result.Warnings = append(result.Warnings,
			fmt.Sprintf("group %q: unknown model %q — set 'download_url' to explicitly enable", g.Name, g.Model))
	}

	return nil
}
