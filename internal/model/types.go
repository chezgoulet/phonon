package model

import (
	"time"
)

// UpstreamSource identifies where to download a model from.
type UpstreamSource string

const (
	SourceHuggingFace UpstreamSource = "huggingface"
	SourceGeneric     UpstreamSource = "generic"
)

// ModelMetadata describes a model to be cached and distributed.
type Metadata struct {
	Name        string         // Fully qualified name, e.g. "meta-llama/Llama-3.2-1B:Q4_K_M"
	Source      UpstreamSource // How to resolve the download URL
	UpstreamURL string         // Override download URL (from group.download_url)
	ExpectedSHA string         // SHA-256 checksum (from group.checksum)
	Quant       string         // Quantization string, e.g. "Q4_K_M"
	SizeBytes   int64          // File size, set after download
}

// CacheEntry is a model stored on disk.
type CacheEntry struct {
	Name      string
	Path      string
	SHA256    string
	SizeBytes int64
	CachedAt  time.Time
}

// ReconcilerAction describes what the reconciler should do for a phone.
type ReconcilerAction int

const (
	ActionNone  ReconcilerAction = iota // Desired == current, no action
	ActionPush                          // Model not on phone — push it
	ActionLoad                          // Model cached but not loaded
	ActionUnload                        // Model loaded but not desired
)

// ReconcilerStep is a single step the reconciler produces for a device.
type ReconcilerStep struct {
	DeviceID  string
	Action    ReconcilerAction
	ModelName string
	URL       string // Download URL for push
	SHA256    string // Checksum for push
	SizeBytes int64  // File size for push
	Backend   string // Requested accelerator for load: auto/npu/gpu/cpu
}
