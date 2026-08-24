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
	rootDir string
	client  *http.Client
	log     *slog.Logger
	mu      sync.RWMutex
	entries map[string]*CacheEntry // model name → entry
	backoff []time.Duration        // retry backoff schedule (override for tests)
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

// bareName enforces that names handed to pinned-dir operations are single
// path components. sanitizeName guarantees this for model names ("/" and
// ":" replaced); the check makes the invariant load-bearing at every
// pinned-fd call site instead of trusting callers.
func bareName(name string) error {
	if name == "" || name == "." || name == ".." ||
		strings.ContainsRune(name, '/') || strings.ContainsRune(name, filepath.Separator) {
		return fmt.Errorf("refusing name %q: pinned-dir operations take bare filenames only", name)
	}
	return nil
}

// pinCacheSubdir pins one of the known constant subdirs below a pinned
// root: openat(rootFd, name, O_RDONLY|O_DIRECTORY|O_NOFOLLOW). If anyone
// replaced the subdir with a symlink this FAILS (ELOOP/ENOTDIR), which is
// the correct containment behavior. A subdir removed after Init is
// recreated and re-pinned once.
func (c *Cache) pinCacheSubdir(root *pinnedDir, name string) (pinnedDir, error) {
	d, err := root.openSub(name)
	if err == nil {
		return d, nil
	}
	if mkErr := os.MkdirAll(filepath.Join(c.rootDir, name), 0o755); mkErr != nil {
		// Surface the original pin failure — recreating is best-effort.
		return pinnedDir{}, err
	}
	// Retry once; if the entry is now a symlink the retry fails again.
	return root.openSub(name)
}

// pinDestination splits dest into (dir, base) and pins dir:
//   - cache-managed subdirs (<root>/models, <root>/.tmp) are pinned through
//     a freshly pinned cache-root fd — race-free by construction;
//   - foreign destinations fall back to path-based pinning with the
//     residual race documented in pinForeignDir (production never uses
//     them; direct downloadOnce callers in tools/tests may).
//
// The returned pinnedDir must be released with closePinned.
func (c *Cache) pinDestination(dest string) (pinnedDir, string, error) {
	base := filepath.Base(dest)
	dir := filepath.Dir(dest)

	sub := ""
	switch dir {
	case filepath.Join(c.rootDir, cacheModelsDir):
		sub = cacheModelsDir
	case filepath.Join(c.rootDir, cacheTmpDir):
		sub = cacheTmpDir
	}
	if sub != "" {
		root, err := pinRootDir(c.rootDir)
		if err != nil {
			return pinnedDir{}, "", err
		}
		defer closePinned(&root) // subdirs stay valid after the root fd closes
		d, err := c.pinCacheSubdir(&root, sub)
		return d, base, err
	}
	d, err := pinForeignDir(dir)
	return d, base, err
}

// Get returns the local path for the given model. If not cached, it downloads
// from the upstream URL. The SHA is optionally verified after download.
func (c *Cache) Get(ctx context.Context, modelName, upstreamURL, expectedSHA string) (string, error) {
	// Entry-point symmetry with Put(): reject traversal sequences before
	// they can reach path construction.
	if strings.Contains(modelName, "..") {
		return "", fmt.Errorf("model name %q rejected: path traversal sequences are not allowed", modelName)
	}

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

	base := sanitizeName(modelName)
	tmpBase := base + ".downloading"

	// Pinned-directory discipline — race-free by construction:
	//
	//  1. Resolve the cache root ONCE per operation and pin it
	//     (O_RDONLY|O_DIRECTORY|O_NOFOLLOW after EvalSymlinks of the root).
	//  2. Pin the constant subdirs (.tmp, models) via openat(rootFd, ...,
	//     O_NOFOLLOW|O_DIRECTORY): a swapped-in symlink FAILS here.
	//  3. Do every file operation through those fds using BARE FILENAMES
	//     ONLY (sanitizeName never leaves "/"). Single-component resolution
	//     against held inodes cannot traverse a swapped ancestor because no
	//     ancestor is ever resolved from a path string — so neither the
	//     write-open nor the atomic promote can escape the real cache dirs,
	//     no matter how fast an attacker toggles entries under the root.
	rootPin, err := pinRootDir(c.rootDir)
	if err != nil {
		return "", fmt.Errorf("download %s: %w", modelName, err)
	}
	defer closePinned(&rootPin)

	tmpPin, err := c.pinCacheSubdir(&rootPin, cacheTmpDir)
	if err != nil {
		return "", fmt.Errorf("download %s: %w", modelName, err)
	}
	defer closePinned(&tmpPin)

	destPath := filepath.Join(c.rootDir, cacheModelsDir, base)

	// Defense-in-depth ONLY (round-1 checks kept where cheap): on unix the
	// pinned-fd discipline below carries correctness by construction, so
	// this runs once up-front as a cheap pre-flight (diagnosing a swapped
	// models dir before spending the download); it remains load-bearing on
	// non-unix fallbacks where renameAt resolves paths (see
	// cache_pindir_other.go).
	if err := c.verifyDestinationForWrite(destPath); err != nil {
		return "", fmt.Errorf("rename target: %w", err)
	}

	// Remove any stale tmp file left by an interrupted prior attempt
	// (unlinkat through the pinned tmp dir — contained by construction).
	_ = tmpPin.remove(tmpBase)

	if err := c.download(ctx, upstreamURL, tmpPin, tmpBase, expectedSHA); err != nil {
		return "", fmt.Errorf("download %s: %w", modelName, err)
	}

	// Atomic promote INSIDE pinned dirs: renameat(tmpFd, tmpBase,
	// modelsFd, base). os.Rename would resolve two attacker-swappable path
	// strings; renameat resolves two bare filenames against held inodes.
	//
	// The models dir is re-pinned per attempt: if it was removed and
	// recreated mid-download (benign churn or an attacker toggle), the
	// first pin may reference an unlinked inode (ENOENT). Every re-pin
	// revalidates via openat(rootFd, ..., O_NOFOLLOW|O_DIRECTORY) — a
	// swapped-in symlink still refuses (ELOOP) — so the retry cannot
	// weaken containment.
	promote := func() error {
		mp, err := c.pinCacheSubdir(&rootPin, cacheModelsDir)
		if err != nil {
			return err
		}
		defer closePinned(&mp)
		return renameAt(tmpPin, tmpBase, mp, base)
	}

	err = promote()
	if err != nil {
		err = promote()
	}
	if err != nil {
		tmpPin.remove(tmpBase)
		return "", fmt.Errorf("rename %s: %w", modelName, err)
	}

	size, err := func() (int64, error) {
		sp, err := c.pinCacheSubdir(&rootPin, cacheModelsDir)
		if err != nil {
			return 0, err
		}
		defer closePinned(&sp)
		return sp.statSize(base)
	}()
	if err != nil {
		return "", err
	}

	entry = &CacheEntry{
		Name:      modelName,
		Path:      destPath,
		SHA256:    expectedSHA,
		SizeBytes: size,
		CachedAt:  time.Now(),
	}

	c.mu.Lock()
	c.entries[modelName] = entry
	c.mu.Unlock()

	c.log.Info("model cached", "model", modelName, "size", size)
	return destPath, nil
}

// download fetches a file from url into destDir under destBase (a bare
// filename), with retry and optional SHA-256 verification. Uses exponential
// backoff (3 attempts). destDir must already be pinned; the tmp subdir is
// created by Init (re-pinned on demand via pinCacheSubdir).
func (c *Cache) download(ctx context.Context, url string, destDir pinnedDir, destBase, expectedSHA string) error {
	if url == "" {
		return fmt.Errorf("no download URL for model")
	}

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

		lastErr = c.downloadOnceAt(ctx, url, destDir, destBase, expectedSHA)
		if lastErr == nil {
			return nil
		}
		c.log.Warn("download attempt failed", "url", url, "attempt", attempt+1, "error", lastErr)
	}

	return fmt.Errorf("download failed after %d attempts: %w", maxAttempts+1, lastErr)
}

// downloadOnce performs a single download attempt into a caller-provided
// destination PATH. Kept for direct callers (tools/tests); it pins the
// destination directory via pinDestination — cache-managed subdirs are
// pinned through the cache-root fd, foreign dirs take the documented
// legacy fallback — and delegates to downloadOnceAt.
func (c *Cache) downloadOnce(ctx context.Context, url, dest, expectedSHA string) error {
	destDir, base, err := c.pinDestination(dest)
	if err != nil {
		return err
	}
	defer closePinned(&destDir)
	return c.downloadOnceAt(ctx, url, destDir, base, expectedSHA)
}

// downloadOnceAt performs a single download attempt with HTTP Range resume
// support, writing destBase inside the PINNED dir destDir. Every file
// operation (size probe, write-open, resume-hash read, cleanup unlink,
// complete-file hash) resolves one bare filename against the held
// directory inode — there is no path resolution left to race.
func (c *Cache) downloadOnceAt(ctx context.Context, url string, destDir pinnedDir, destBase, expectedSHA string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, http.NoBody)
	if err != nil {
		return err
	}

	// Check for partial download to resume from (probed through the pinned
	// dir; O_NOFOLLOW makes a planted symlink report as "nothing resumable"
	// and the later write-open refuse it outright).
	var existingSize int64
	if size, statErr := destDir.statSize(destBase); statErr == nil && size > 0 {
		existingSize = size
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
		// File is already complete — verify checksum and return. The hash
		// reads through the pinned dir too.
		c.log.Debug("file already complete, verifying", "url", url, "size", existingSize)
		if expectedSHA != "" {
			got, err := hashFileAt(destDir, destBase)
			if err != nil {
				return fmt.Errorf("hash complete file: %w", err)
			}
			if !strings.EqualFold(got, expectedSHA) {
				destDir.remove(destBase)
				return fmt.Errorf("SHA-256 mismatch: expected %s, got %s", expectedSHA, got)
			}
		}
		return nil
	default:
		return fmt.Errorf("HTTP %d from %s", resp.StatusCode, url)
	}

	// Open file through the pinned dir: append if resuming, truncate if
	// starting fresh. O_NOFOLLOW makes a planted final-component symlink
	// fail with ELOOP before any truncate/append side effect.
	var flag int
	if resumeOffset > 0 {
		flag = os.O_CREATE | os.O_WRONLY | os.O_APPEND
	} else {
		flag = os.O_CREATE | os.O_WRONLY | os.O_TRUNC
	}

	f, err := destDir.openFile(destBase, flag, 0o644)
	if err != nil {
		return c.diagnoseOpenFailure(destDir, destBase, err)
	}

	// Hash verification: if resuming, hash the existing content too (read
	// through the same pinned dir).
	hasher := sha256.New()
	if resumeOffset > 0 {
		existing, err := destDir.openRead(destBase)
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
		// Cleanup unlinks INSIDE the pinned dir — it can never touch an
		// outside victim even if directory entries were swapped mid-flight.
		destDir.remove(destBase)
		return fmt.Errorf("write body: %w", err)
	}

	if err := f.Close(); err != nil {
		destDir.remove(destBase)
		return err
	}

	// Verify checksum
	if expectedSHA != "" {
		got := hex.EncodeToString(hasher.Sum(nil))
		if !strings.EqualFold(got, expectedSHA) {
			destDir.remove(destBase)
			return fmt.Errorf("SHA-256 mismatch: expected %s, got %s (downloaded %d bytes, resumed %d)", expectedSHA, got, resp.ContentLength, resumeOffset)
		}
	}

	return nil
}

// diagnoseOpenFailure upgrades a failed pinned-dir open to a
// containment-class error when the final component is a planted symlink.
// Diagnosis only: containment itself comes from openat + O_NOFOLLOW, which
// failed the open before any side effect.
func (c *Cache) diagnoseOpenFailure(d pinnedDir, name string, err error) error {
	full := filepath.Join(d.disp, name)
	if fi, lerr := os.Lstat(full); lerr == nil && fi.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("refusing to open %q: destination is a symlink resolving outside cache root (symlink attack?)", full)
	}
	return fmt.Errorf("open %s: %w", full, err)
}

// hashFileAt hashes destBase within the pinned dir (read-only openat).
func hashFileAt(dir pinnedDir, name string) (string, error) {
	f, err := dir.openRead(name)
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

	// Unlink through the pinned models dir when possible so removal cannot
	// be redirected by swapped directory entries either.
	d, base, err := c.pinDestination(entry.Path)
	if err != nil {
		return err
	}
	defer closePinned(&d)
	return d.remove(base)
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

// ErrChecksumMismatch is returned by Put when the uploaded bytes do not
// match the expected SHA-256.
var ErrChecksumMismatch = fmt.Errorf("SHA-256 checksum mismatch")

// ErrTooLarge is returned by Put when the upload exceeds maxBytes.
var ErrTooLarge = fmt.Errorf("model file exceeds size limit")

// containsPath reports whether resolved path p is root itself or lies
// strictly inside root. Unlike a bare strings.HasPrefix check, it cannot be
// bypassed by a sibling directory whose name shares the root's prefix
// (e.g. root=/var/cache passing for /var/cache-evil).
func containsPath(root, p string) bool {
	root = filepath.Clean(root)
	p = filepath.Clean(p)
	if p == root {
		return true
	}
	return strings.HasPrefix(p, root+string(filepath.Separator))
}

// verifyDestinationForWrite runs the round-1 containment checks:
//   - the final path component must not be a symlink;
//   - every directory component must resolve inside the cache root.
//
// ROLE CHANGE since the pinned-directory-fd redesign: on unix, correctness
// no longer depends on these checks — Get/Put pin the cache root once per
// operation, open the constant subdirs via openat(rootFd, ..., O_NOFOLLOW)
// (a swapped-in subdir symlink fails there), and do all file work through
// those dirfds with bare filenames, so nothing is resolved from attacker-
// swappable path strings at all. This function is kept as cheap
// defense-in-depth before the promote rename, and remains load-bearing
// only on non-unix platforms whose syscall package lacks openat/renameat
// (see cache_pindir_other.go for the documented residual race there).
func (c *Cache) verifyDestinationForWrite(path string) error {
	if fi, err := os.Lstat(path); err == nil && fi.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("refusing to write %q: destination is a symlink resolving outside cache root (symlink attack?)", path)
	}
	realRoot, err := filepath.EvalSymlinks(c.rootDir)
	if err != nil {
		return fmt.Errorf("resolve cache root symlinks: %w", err)
	}
	realParent, err := filepath.EvalSymlinks(filepath.Dir(path))
	if err != nil {
		return fmt.Errorf("resolve parent of %s: %w", path, err)
	}
	if !containsPath(realRoot, realParent) {
		return fmt.Errorf("directory %q resolves outside cache root %q (symlink attack?)", realParent, realRoot)
	}
	return nil
}

// Put streams a model file from r into the cache under name, hashing while
// writing. If expectedSHA is non-empty, the computed SHA-256 must match
// (case-insensitively) or the upload is discarded with ErrChecksumMismatch.
// If maxBytes > 0, uploads exceeding it are discarded with ErrTooLarge.
// The file is written into the pinned tmp dir and atomically renamed
// (renameat between pinned dirfds) into the pinned models dir on success —
// mirroring download() — so concurrent readers and the reconciler never
// observe a partial file.
func (c *Cache) Put(name string, r io.Reader, expectedSHA string, maxBytes int64) (*CacheEntry, error) {
	if name == "" {
		return nil, fmt.Errorf("model name required")
	}
	if strings.Contains(name, "..") {
		return nil, fmt.Errorf("model name %q rejected: path traversal sequences are not allowed", name)
	}

	base := sanitizeName(name)
	tmpBase := base + ".uploading"
	destPath := filepath.Join(c.rootDir, cacheModelsDir, base)

	// Same pinned-directory discipline as Get(): root resolved once,
	// constant subdirs pinned via openat(rootFd, ..., O_NOFOLLOW|O_DIRECTORY)
	// (a swapped-in symlink FAILS here), every file op by bare filename.
	rootPin, err := pinRootDir(c.rootDir)
	if err != nil {
		return nil, fmt.Errorf("create upload tmp file: %w", err)
	}
	defer closePinned(&rootPin)

	tmpPin, err := c.pinCacheSubdir(&rootPin, cacheTmpDir)
	if err != nil {
		return nil, fmt.Errorf("create upload tmp file: %w", err)
	}
	defer closePinned(&tmpPin)

	// O_EXCL|O_NOFOLLOW through the pinned tmp dir: a planted final-
	// component symlink fails the open with zero side effects.
	f, err := tmpPin.openFile(tmpBase, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, fmt.Errorf("create upload tmp file: %w", c.diagnoseOpenFailure(tmpPin, tmpBase, err))
	}
	cleanup := func() {
		f.Close()
		tmpPin.remove(tmpBase)
	}

	hasher := sha256.New()
	src := io.Reader(r)
	if maxBytes > 0 {
		// Read one extra byte so exceeding the limit is detectable.
		src = io.LimitReader(r, maxBytes+1)
	}
	written, err := io.Copy(io.MultiWriter(f, hasher), src)
	if err != nil {
		cleanup()
		return nil, fmt.Errorf("write upload: %w", err)
	}
	if maxBytes > 0 && written > maxBytes {
		cleanup()
		return nil, fmt.Errorf("%w: got more than %d bytes", ErrTooLarge, maxBytes)
	}
	if err := f.Close(); err != nil {
		tmpPin.remove(tmpBase)
		return nil, fmt.Errorf("close upload tmp file: %w", err)
	}

	got := hex.EncodeToString(hasher.Sum(nil))
	if expectedSHA != "" && !strings.EqualFold(got, expectedSHA) {
		tmpPin.remove(tmpBase)
		return nil, fmt.Errorf("%w: expected %s, got %s", ErrChecksumMismatch, expectedSHA, got)
	}

	// Defense-in-depth (round-1 checks kept where cheap): correctness on
	// unix is carried by renameAt between pinned dirfds below; this stays
	// load-bearing only for non-unix fallbacks.
	if err := c.verifyDestinationForWrite(destPath); err != nil {
		tmpPin.remove(tmpBase)
		return nil, fmt.Errorf("rename target: %w", err)
	}

	// Atomic promote INSIDE pinned dirs; models dir re-pinned per attempt
	// so benign churn (dir removed/recreated mid-upload) costs one retry,
	// while every re-pin still refuses a swapped-in symlink (ELOOP).
	promote := func() error {
		mp, err := c.pinCacheSubdir(&rootPin, cacheModelsDir)
		if err != nil {
			return err
		}
		defer closePinned(&mp)
		return renameAt(tmpPin, tmpBase, mp, base)
	}
	err = promote()
	if err != nil {
		err = promote()
	}
	if err != nil {
		tmpPin.remove(tmpBase)
		return nil, fmt.Errorf("rename upload into cache: %w", err)
	}

	entry := &CacheEntry{
		Name:      name,
		Path:      destPath,
		SHA256:    got,
		SizeBytes: written,
		CachedAt:  time.Now(),
	}
	c.mu.Lock()
	c.entries[name] = entry
	c.mu.Unlock()

	c.log.Info("model uploaded to cache", "model", name, "size", written)
	result := *entry
	return &result, nil
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
