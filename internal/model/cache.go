package model

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Default cache subdirectories.
const (
	cacheModelsDir = "models"
	cacheTmpDir    = ".tmp"
)

// ErrNotCached is returned when a model is not in the local cache.
var ErrNotCached = fmt.Errorf("model not in cache")

var defaultBackoff = []time.Duration{1 * time.Second, 3 * time.Second, 10 * time.Second}

// Cache manages local model files downloaded from upstream sources.
type Cache struct {
	rootDir   string
	client    *http.Client
	log       *slog.Logger
	mu        sync.RWMutex
	entries   map[string]*CacheEntry // model name → entry
	backoff   []time.Duration         // retry backoff schedule (override for tests)
}

// SetBackoff overrides the retry backoff schedule. Used in tests.
func (c *Cache) SetBackoff(b []time.Duration) {
	c.backoff = b
}

// NewCache creates a model cache rooted at cacheDir.
// If client is nil, http.DefaultClient is used.
func NewCache(cacheDir string, client *http.Client) *Cache {
	if client == nil {
		client = http.DefaultClient
	}
	return &Cache{
		rootDir: cacheDir,
		client:  client,
		log:     slog.With("component", "model-cache"),
		entries: make(map[string]*CacheEntry),
	}
}

// Init ensures cache directories exist and scans existing files.
func (c *Cache) Init() error {
	for _, d := range []string{cacheModelsDir, cacheTmpDir} {
		if err := os.MkdirAll(filepath.Join(c.rootDir, d), 0o755); err != nil {
			return fmt.Errorf("create %s: %w", d, err)
		}
	}
	return c.scan()
}

// scan populates entries from files already on disk.
func (c *Cache) scan() error {
	modelsDir := filepath.Join(c.rootDir, cacheModelsDir)
	entries, err := os.ReadDir(modelsDir)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("scan cache dir: %w", err)
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		fi, err := e.Info()
		if err != nil {
			continue
		}
		name := e.Name()
		c.entries[name] = &CacheEntry{
			Name:      name,
			Path:      filepath.Join(modelsDir, name),
			SizeBytes: fi.Size(),
			CachedAt:  fi.ModTime(),
		}
	}
	return nil
}

// Get returns the local path for the given model. If not cached, it downloads
// from the upstream URL. The SHA is optionally verified after download.
func (c *Cache) Get(ctx context.Context, modelName, upstreamURL, expectedSHA string) (string, error) {
	// Check cache under read lock first
	c.mu.RLock()
	entry, ok := c.entries[modelName]
	c.mu.RUnlock()

	if ok {
		// Verify checksum if expected
		if expectedSHA != "" && entry.SHA256 != expectedSHA {
			c.log.Warn("checksum mismatch in cache, re-downloading", "model", modelName)
		} else {
			return entry.Path, nil
		}
	}

	// Download
	dest := filepath.Join(c.rootDir, cacheModelsDir, sanitizeName(modelName))
	tmpDest := filepath.Join(c.rootDir, cacheTmpDir, sanitizeName(modelName)+".downloading")

	if err := c.download(ctx, upstreamURL, tmpDest, expectedSHA); err != nil {
		return "", fmt.Errorf("download %s: %w", modelName, err)
	}

	// Atomically rename
	if err := os.Rename(tmpDest, dest); err != nil {
		return "", fmt.Errorf("rename %s: %w", modelName, err)
	}

	fi, err := os.Stat(dest)
	if err != nil {
		return "", err
	}

	entry = &CacheEntry{
		Name:      modelName,
		Path:      dest,
		SHA256:    expectedSHA,
		SizeBytes: fi.Size(),
		CachedAt:  time.Now(),
	}

	c.mu.Lock()
	c.entries[modelName] = entry
	c.mu.Unlock()

	c.log.Info("model cached", "model", modelName, "size", fi.Size())
	return dest, nil
}

// download fetches a file from url, writing to dest, with retry and optional
// SHA-256 verification. Uses exponential backoff (3 attempts).
func (c *Cache) download(ctx context.Context, url, dest, expectedSHA string) error {
	if url == "" {
		return fmt.Errorf("no download URL for model")
	}

	// Ensure tmp dir exists
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}

	// Remove any stale tmp file
	os.Remove(dest)

	backoff := c.backoff
	if len(backoff) == 0 {
		backoff = defaultBackoff
	}

	var lastErr error
	maxAttempts := len(backoff)

	for attempt := 0; attempt <= maxAttempts; attempt++ {
		if attempt > 0 {
			c.log.Info("retrying download", "url", url, "attempt", attempt)
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(backoff[attempt-1]):
			}
		}

		lastErr = c.downloadOnce(ctx, url, dest, expectedSHA)
		if lastErr == nil {
			return nil
		}
		c.log.Warn("download attempt failed", "url", url, "attempt", attempt+1, "error", lastErr)
	}

	return fmt.Errorf("download failed after %d attempts: %w", maxAttempts+1, lastErr)
}

// downloadOnce performs a single download attempt with HTTP Range resume support.
// If a partial file exists, it sends a Range header to resume from the existing size.
func (c *Cache) downloadOnce(ctx context.Context, url, dest, expectedSHA string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, http.NoBody)
	if err != nil {
		return err
	}

	// Check for partial download to resume from
	var existingSize int64
	if fi, statErr := os.Stat(dest); statErr == nil && fi.Size() > 0 {
		existingSize = fi.Size()
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", existingSize))
		c.log.Debug("resuming partial download", "url", url, "existing_bytes", existingSize)
	}

	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	// Determine how to open the file and whether to verify existing bytes
	var resumeOffset int64
	switch resp.StatusCode {
	case http.StatusOK:
		// Server doesn't support Range or returned full file — start fresh
		c.log.Debug("server returned full file, starting from scratch", "url", url)
	case http.StatusPartialContent:
		// Server supports Range — resume from existing size
		resumeOffset = existingSize
	case http.StatusRequestedRangeNotSatisfiable:
		// File is already complete — verify checksum and return
		c.log.Debug("file already complete, verifying", "url", url, "size", existingSize)
		if expectedSHA != "" {
			got, err := fileSHA256(dest)
			if err != nil {
				return fmt.Errorf("hash complete file: %w", err)
			}
			if !strings.EqualFold(got, expectedSHA) {
				os.Remove(dest)
				return fmt.Errorf("SHA-256 mismatch: expected %s, got %s", expectedSHA, got)
			}
		}
		return nil
	default:
		return fmt.Errorf("HTTP %d from %s", resp.StatusCode, url)
	}

	// Open file: append if resuming, truncate if starting fresh
	var flag int
	if resumeOffset > 0 {
		flag = os.O_CREATE | os.O_WRONLY | os.O_APPEND
	} else {
		flag = os.O_CREATE | os.O_WRONLY | os.O_TRUNC
	}

	f, err := os.OpenFile(dest, flag, 0o644)
	if err != nil {
		return err
	}

	// Hash verification: if resuming, hash the existing content too
	hasher := sha256.New()
	if resumeOffset > 0 {
		existing, err := os.Open(dest)
		if err != nil {
			f.Close()
			return fmt.Errorf("open existing for hash: %w", err)
		}
		if _, err := io.Copy(hasher, existing); err != nil {
			existing.Close()
			f.Close()
			return fmt.Errorf("hash existing: %w", err)
		}
		existing.Close()
	}

	writer := io.MultiWriter(f, hasher)

	_, err = io.Copy(writer, resp.Body)
	if err != nil {
		f.Close()
		os.Remove(dest)
		return fmt.Errorf("write body: %w", err)
	}

	if err := f.Close(); err != nil {
		os.Remove(dest)
		return err
	}

	// Verify checksum
	if expectedSHA != "" {
		got := hex.EncodeToString(hasher.Sum(nil))
		if !strings.EqualFold(got, expectedSHA) {
			os.Remove(dest)
			return fmt.Errorf("SHA-256 mismatch: expected %s, got %s (downloaded %d bytes, resumed %d)", expectedSHA, got, resp.ContentLength, resumeOffset)
		}
	}

	return nil
}

// Has returns true if the model is in the local cache.
func (c *Cache) Has(name string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	_, ok := c.entries[name]
	return ok
}

// List returns all cached model entries.
func (c *Cache) List() []CacheEntry {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make([]CacheEntry, 0, len(c.entries))
	for _, e := range c.entries {
		result = append(result, *e)
	}
	return result
}

// Remove deletes a model from the cache.
func (c *Cache) Remove(name string) error {
	c.mu.Lock()
	entry, ok := c.entries[name]
	if !ok {
		c.mu.Unlock()
		return ErrNotCached
	}
	delete(c.entries, name)
	c.mu.Unlock()

	if err := os.Remove(entry.Path); err != nil {
		return err
	}
	return nil
}

// ModelPath returns the local path for a cached model.
func (c *Cache) ModelPath(name string) (string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	entry, ok := c.entries[name]
	if !ok {
		return "", ErrNotCached
	}
	return entry.Path, nil
}

// ResolveHuggingFaceURL builds a HuggingFace download URL from a model identifier.
// Format: "org/repo:quant" or "org/repo"
// Example: "meta-llama/Llama-3.2-1B:Q4_K_M" → "https://huggingface.co/meta-llama/Llama-3.2-1B-GGUF/resolve/main/Llama-3.2-1B-Q4_K_M.gguf"
func ResolveHuggingFaceURL(modelID string) string {
	parts := strings.SplitN(modelID, ":", 2)
	orgRepo := parts[0]
	quant := "Q4_K_M"
	if len(parts) > 1 && parts[1] != "" {
		quant = parts[1]
	}

	// Extract the short name from the repo
	repoParts := strings.Split(orgRepo, "/")
	shortName := repoParts[len(repoParts)-1]

	filename := shortName + "-" + quant + ".gguf"
	return fmt.Sprintf("https://huggingface.co/%s-GGUF/resolve/main/%s", orgRepo, filename)
}

// sanitizeName replaces path separators in model names.
func sanitizeName(name string) string {
	return strings.NewReplacer("/", "_", ":", "_").Replace(name)
}

// fileSHA256 computes the hex SHA-256 hash of a file.
func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	hasher := sha256.New()
	if _, err := io.Copy(hasher, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}
