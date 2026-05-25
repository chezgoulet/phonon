package config

import (
	"testing"
	"time"
)

func TestLoadExampleConfig(t *testing.T) {
	yaml := `
cluster:
  name: "homelab-inference"
  auth:
    mode: none
  networking:
    prefer: ethernet

groups:
  - name: fast-general
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [pixel-7a-01, pixel-7a-02]
  - name: reasoning
    mode: shard
    model: gemma-4-27b-q4
    runtime: prima
    phones: [pixel-9-01, pixel-9-02]
    standby: [pixel-8-01]
`
	cfg, vr, err := Load([]byte(yaml))
	if err != nil {
		t.Fatalf("Load() returned error: %v", err)
	}
	if cfg.Cluster.Name != "homelab-inference" {
		t.Errorf("expected cluster name 'homelab-inference', got %q", cfg.Cluster.Name)
	}
	if len(cfg.Groups) != 2 {
		t.Fatalf("expected 2 groups, got %d", len(cfg.Groups))
	}
	if cfg.Groups[0].Name != "fast-general" {
		t.Errorf("expected first group name 'fast-general', got %q", cfg.Groups[0].Name)
	}
	if cfg.Groups[1].Name != "reasoning" {
		t.Errorf("expected second group name 'reasoning', got %q", cfg.Groups[1].Name)
	}
	if len(vr.Warnings) != 0 {
		t.Errorf("expected no warnings, got %v", vr.Warnings)
	}
}

func TestLoadFile(t *testing.T) {
	_, _, err := LoadFile("/nonexistent/phonon.yaml")
	if err == nil {
		t.Fatal("expected error for nonexistent file, got nil")
	}
}

func TestValidate_EmptyGroups(t *testing.T) {
	yaml := `cluster:
  name: test
groups: []`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for empty groups, got nil")
	}
}

func TestValidate_NoGroups(t *testing.T) {
	yaml := `cluster:
  name: test`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for no groups, got nil")
	}
}

func TestValidate_GroupNoName(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for unnamed group, got nil")
	}
}

func TestValidate_GroupNoPhones(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: empty
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: []`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for empty phone list, got nil")
	}
}

func TestValidate_ModeRuntimeMismatchPool(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: bad
    mode: pool
    model: gemma-4-E2B-it
    runtime: prima
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for pool+prima mismatch, got nil")
	}
}

func TestValidate_ModeRuntimeMismatchShard(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: bad
    mode: shard
    model: gemma-4-27b-q4
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for shard+litert mismatch, got nil")
	}
}

func TestValidate_UnknownMode(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: bad
    mode: unknown
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for unknown mode, got nil")
	}
}

func TestValidate_UnknownRuntime(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: bad
    mode: pool
    model: gemma-4-E2B-it
    runtime: unknown
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for unknown runtime, got nil")
	}
}

func TestValidate_NoModel(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: bad
    mode: pool
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for missing model, got nil")
	}
}

func TestValidate_UnknownModelWarning(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: custom
    mode: pool
    model: my-custom-model-v1
    runtime: litert
    phones: [phone-01]`
	_, vr, err := Load([]byte(yaml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(vr.Warnings) != 1 {
		t.Fatalf("expected 1 warning, got %d: %v", len(vr.Warnings), vr.Warnings)
	}
	if vr.Warnings[0] != `group "custom": unknown model "my-custom-model-v1" — set 'download_url' to explicitly enable` {
		t.Errorf("unexpected warning text: %q", vr.Warnings[0])
	}
}

func TestValidate_UnknownModelWithDownloadURL(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: custom
    mode: pool
    model: my-custom-model-v1
    runtime: litert
    download_url: "http://example.com/model.bin"
    phones: [phone-01]`
	_, vr, err := Load([]byte(yaml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(vr.Warnings) != 0 {
		t.Errorf("expected no warnings with download_url, got %v", vr.Warnings)
	}
}

func TestValidate_DuplicatePhoneSameGroup(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01, phone-01]`
	_, _, err := Load([]byte(yaml))
	if err != nil {
		t.Errorf("duplicate phone in same group should be allowed, got error: %v", err)
	}
}

func TestValidate_PhoneInMultipleActiveGroups(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]
  - name: g2
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for duplicate phone in multiple groups, got nil")
	}
}

func TestValidate_StandbyNotActiveElsewhere(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]
  - name: g2
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-02]
    standby: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for standby active elsewhere, got nil")
	}
}

func TestValidate_StandbyInOwnGroupAllowed(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01, phone-02]
    standby: [phone-03]`
	_, _, err := Load([]byte(yaml))
	if err != nil {
		t.Fatalf("standby in own group should be allowed, got error: %v", err)
	}
}

func TestValidate_OIDCRequiresIssuer(t *testing.T) {
	yaml := `cluster:
  name: test
  auth:
    mode: oidc
    client_id: my-client
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for OIDC without issuer, got nil")
	}
}

func TestValidate_OIDCRequiresClientID(t *testing.T) {
	yaml := `cluster:
  name: test
  auth:
    mode: oidc
    issuer: https://example.com
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err == nil {
		t.Fatal("expected error for OIDC without client_id, got nil")
	}
}

func TestValidate_OIDCValid(t *testing.T) {
	yaml := `cluster:
  name: test
  auth:
    mode: oidc
    issuer: https://example.com
    client_id: my-client
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	_, _, err := Load([]byte(yaml))
	if err != nil {
		t.Fatalf("valid OIDC config should be allowed, got error: %v", err)
	}
}

func TestRegisterModels(t *testing.T) {
	RegisterModels("test-model-v2")
	if !KnownModels["test-model-v2"] {
		t.Error("expected test-model-v2 to be registered")
	}
}

func TestKnownModelsSnapshot(t *testing.T) {
	// Verify a few baseline models exist
	expected := []string{"gemma-4-E2B-it", "gemma-4-27b-q4", "llama-3.2-3b-q4", "phi-3.5-mini-q4"}
	for _, m := range expected {
		if !KnownModels[m] {
			t.Errorf("expected known model %q to be in KnownModels", m)
		}
	}
}

func TestValidate_DefaultNetworkConfig(t *testing.T) {
	yaml := `cluster:
  name: test
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`
	cfg, _, err := Load([]byte(yaml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Cluster.Networking.Prefer != "" {
		t.Errorf("expected empty networking prefer, got %q", cfg.Cluster.Networking.Prefer)
	}
}

func TestValidationResult_Warnings(t *testing.T) {
	vr := &ValidationResult{Warnings: []string{"warn1", "warn2"}}
	if len(vr.Warnings) != 2 {
		t.Errorf("expected 2 warnings, got %d", len(vr.Warnings))
	}
}

func TestHealthDefaults(t *testing.T) {
    tests := []struct{
        name string
        yaml string
        expected func(t *testing.T, h HealthConfig)
    }{
        {
            name: "defaults",
            yaml: `cluster:
  name: test
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`,
            expected: func(t *testing.T, h HealthConfig) {
                if h.Overheat.Threshold != 45 {
                    t.Errorf("expected default overheat 45, got %f", h.Overheat.Threshold)
                }
                if h.Overheat.ReentryThreshold != 40 {
                    t.Errorf("expected default overheat reentry 40, got %f", h.Overheat.ReentryThreshold)
                }
                if h.Battery.LowThreshold != 15 {
                    t.Errorf("expected default battery low 15, got %f", h.Battery.LowThreshold)
                }
                if h.Battery.ReentryThreshold != 30 {
                    t.Errorf("expected default battery reentry 30, got %f", h.Battery.ReentryThreshold)
                }
                if h.Battery.CapacityThreshold != 80 {
                    t.Errorf("expected default capacity 80, got %f", h.Battery.CapacityThreshold)
                }
                if h.OfflineTimeout != "60s" {
                    t.Errorf("expected default offline timeout 60s, got %q", h.OfflineTimeout)
                }
            },
        },
        {
            name: "custom",
            yaml: `cluster:
  name: test
  health:
    overheat:
      threshold: 50
      reentry_threshold: 45
    battery:
      low_threshold: 10
      reentry_threshold: 25
      capacity_threshold: 75
    offline_timeout: "120s"
groups:
  - name: g1
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [phone-01]`,
            expected: func(t *testing.T, h HealthConfig) {
                if h.Overheat.Threshold != 50 {
                    t.Errorf("expected overheat 50, got %f", h.Overheat.Threshold)
                }
                if h.Overheat.ReentryThreshold != 45 {
                    t.Errorf("expected overheat reentry 45, got %f", h.Overheat.ReentryThreshold)
                }
                if h.Battery.LowThreshold != 10 {
                    t.Errorf("expected battery low 10, got %f", h.Battery.LowThreshold)
                }
                if h.Battery.ReentryThreshold != 25 {
                    t.Errorf("expected battery reentry 25, got %f", h.Battery.ReentryThreshold)
                }
                if h.Battery.CapacityThreshold != 75 {
                    t.Errorf("expected capacity 75, got %f", h.Battery.CapacityThreshold)
                }
                if h.OfflineTimeout != "120s" {
                    t.Errorf("expected offline timeout 120s, got %q", h.OfflineTimeout)
                }
            },
        },
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            cfg, _, err := Load([]byte(tt.yaml))
            if err != nil {
                t.Fatalf("unexpected error: %v", err)
            }
            tt.expected(t, cfg.Cluster.Health)
        })
    }
}

func TestOfflineTimeoutDuration(t *testing.T) {
	h := &HealthConfig{OfflineTimeout: "60s"}
	if d := h.OfflineTimeoutDuration(); d != 60*time.Second {
		t.Errorf("expected 60s, got %v", d)
	}

	h = &HealthConfig{OfflineTimeout: "30s"}
	if d := h.OfflineTimeoutDuration(); d != 30*time.Second {
		t.Errorf("expected 30s, got %v", d)
	}

	// Invalid falls back to 60s
	h = &HealthConfig{OfflineTimeout: "invalid"}
	if d := h.OfflineTimeoutDuration(); d != 60*time.Second {
		t.Errorf("expected 60s fallback, got %v", d)
	}

	// Empty falls back to 60s
	h = &HealthConfig{}
	if d := h.OfflineTimeoutDuration(); d != 60*time.Second {
		t.Errorf("expected 60s fallback for empty, got %v", d)
	}
}
