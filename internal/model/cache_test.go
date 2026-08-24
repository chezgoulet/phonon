package model

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

var testFilePerm os.FileMode = 0o644

func TestNewCache(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	if cache == nil {
		t.Fatal("expected non-nil cache")
	}
	if cache.rootDir != dir {
		t.Errorf("expected rootDir %q, got %q", dir, cache.rootDir)
	}
}

func TestCacheInit(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)

	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	// Directories should exist
	for _, d := range []string{"models", ".tmp"} {
		path := filepath.Join(dir, d)
		if _, err := os.Stat(path); os.IsNotExist(err) {
			t.Errorf("expected %s directory to exist", d)
		}
	}
}

func TestCacheScanExisting(t *testing.T) {
	dir := t.TempDir()
	modelsDir := filepath.Join(dir, "models")
	os.MkdirAll(modelsDir, 0o755)

	// Create a fake cached model
	modelPath := filepath.Join(modelsDir, "test-model.bin")
	os.WriteFile(modelPath, []byte("model-data"), testFilePerm)

	cache := NewCache(dir, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	if !cache.Has("test-model.bin") {
		t.Error("expected model to be found after scan")
	}
}

func TestCacheHas(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	if cache.Has("nonexistent") {
		t.Error("expected false for missing model")
	}

	// Manually add an entry
	cache.mu.Lock()
	cache.entries["my-model"] = &CacheEntry{Name: "my-model", Path: "/fake/path"}
	cache.mu.Unlock()

	if !cache.Has("my-model") {
		t.Error("expected true after adding entry")
	}
}

func TestCacheList(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	if entries := cache.List(); len(entries) != 0 {
		t.Errorf("expected 0 entries, got %d", len(entries))
	}

	cache.mu.Lock()
	cache.entries["a"] = &CacheEntry{Name: "a"}
	cache.entries["b"] = &CacheEntry{Name: "b"}
	cache.mu.Unlock()

	if entries := cache.List(); len(entries) != 2 {
		t.Errorf("expected 2 entries, got %d", len(entries))
	}
}

func TestCacheRemove(t *testing.T) {
	dir := t.TempDir()
	modelsDir := filepath.Join(dir, "models")
	os.MkdirAll(modelsDir, 0o755)

	modelPath := filepath.Join(modelsDir, "test-model.bin")
	os.WriteFile(modelPath, []byte("data"), testFilePerm)

	cache := NewCache(dir, nil)
	cache.Init()

	if err := cache.Remove("test-model.bin"); err != nil {
		t.Fatalf("Remove: %v", err)
	}
	if cache.Has("test-model.bin") {
		t.Error("model should be removed")
	}
	if _, err := os.Stat(modelPath); !os.IsNotExist(err) {
		t.Error("file should be deleted")
	}
}

func TestCacheRemoveNotCached(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	if err := cache.Remove("nonexistent"); !errors.Is(err, ErrNotCached) {
		t.Errorf("expected ErrNotCached, got %v", err)
	}
}

func TestCacheModelPath(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	_, err := cache.ModelPath("missing")
	if !errors.Is(err, ErrNotCached) {
		t.Errorf("expected ErrNotCached, got %v", err)
	}

	modelsDir := filepath.Join(dir, "models")
	os.MkdirAll(modelsDir, 0o755)
	modelPath := filepath.Join(modelsDir, "mymodel.bin")
	os.WriteFile(modelPath, []byte("data"), testFilePerm)
	cache.Init()

	got, err := cache.ModelPath("mymodel.bin")
	if err != nil {
		t.Fatalf("ModelPath: %v", err)
	}
	if got != modelPath {
		t.Errorf("expected %q, got %q", modelPath, got)
	}
}

func TestResolveHuggingFaceURL(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{
			input:    "meta-llama/Llama-3.2-1B:Q4_K_M",
			expected: "https://huggingface.co/meta-llama/Llama-3.2-1B-GGUF/resolve/main/Llama-3.2-1B-Q4_K_M.gguf",
		},
		{
			input:    "mistralai/Mistral-7B",
			expected: "https://huggingface.co/mistralai/Mistral-7B-GGUF/resolve/main/Mistral-7B-Q4_K_M.gguf",
		},
		{
			input:    "org/repo:Q2_K",
			expected: "https://huggingface.co/org/repo-GGUF/resolve/main/repo-Q2_K.gguf",
		},
	}

	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			got := ResolveHuggingFaceURL(tt.input)
			if got != tt.expected {
				t.Errorf("expected %q, got %q", tt.expected, got)
			}
		})
	}
}

func TestSanitizeName(t *testing.T) {
	name := "meta-llama/Llama-3.2-1B:Q4_K_M"
	got := sanitizeName(name)
	// Folding rewrote both separators, so the result must carry a 64-bit
	// hash suffix of the original name to stay distinct from its fold twin.
	sum := sha256.Sum256([]byte(name))
	want := "meta-llama_Llama-3.2-1B_Q4_K_M-" + hex.EncodeToString(sum[:8])
	if got != want {
		t.Errorf("unexpected: got %q, want %q", got, want)
	}
}

// TestSanitizeNameFoldCollidedNamesStayDistinct pins the short-name fold
// collision fix: "/" and ":" both fold to "_", so without a distinguishing
// suffix Put("a/b") and Put("a_b") targeted ONE stored file and the second
// upload silently overwrote the first, cross-serving one model as another.
func TestCachePutFoldCollidedNamesStayDistinct(t *testing.T) {
	dir := t.TempDir()
	c := NewCache(dir, nil)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	contentX := []byte("payload-owned-by-a-slash-b")
	contentY := []byte("payload-owned-by-a-underscore-b")

	if _, err := c.Put("a/b", bytes.NewReader(contentX), "", 0); err != nil {
		t.Fatalf(`Put "a/b": %v`, err)
	}
	if _, err := c.Put("a_b", bytes.NewReader(contentY), "", 0); err != nil {
		t.Fatalf(`Put "a_b": %v`, err)
	}

	pathX, err := c.ModelPath("a/b")
	if err != nil {
		t.Fatalf(`ModelPath "a/b": %v`, err)
	}
	pathY, err := c.ModelPath("a_b")
	if err != nil {
		t.Fatalf(`ModelPath "a_b": %v`, err)
	}
	if pathX == pathY {
		t.Fatalf(`fold collision: "a/b" and "a_b" share one stored file %s`, pathX)
	}

	gotX, err := os.ReadFile(pathX)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(gotX, contentX) {
		t.Errorf(`Get("a/b") returns wrong content: got %q, want %q (overwritten by "a_b"?)`, gotX, contentX)
	}
	gotY, err := os.ReadFile(pathY)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(gotY, contentY) {
		t.Errorf(`Get("a_b") returns wrong content: got %q, want %q`, gotY, contentY)
	}

	// The mapping must survive a restart via the persisted sidecars.
	c2 := NewCache(dir, nil)
	if err := c2.Init(); err != nil {
		t.Fatalf("re-Init: %v", err)
	}
	for name, want := range map[string][]byte{"a/b": contentX, "a_b": contentY} {
		p, err := c2.Get(context.Background(), name, "", "")
		if err != nil {
			t.Fatalf(`Get(%q) after restart: %v`, name, err)
		}
		got, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf(`Get(%q) after restart: got %q, want %q`, name, got, want)
		}
	}
}

// TestSanitizeNameManySeparatorsStaysWithinLimit feeds a pathological
// separator-heavy name through fold+hash and requires the result to stay
// within the filesystem-safe length cap.
func TestSanitizeNameManySeparatorsStaysWithinLimit(t *testing.T) {
	name := strings.Repeat("org/repo:quant/", 60) // 450 bytes, 120 separators
	got := sanitizeName(name)
	if len(got) > maxSanitizedNameLen {
		t.Errorf("sanitized name is %d bytes, exceeds %d", len(got), maxSanitizedNameLen)
	}
	if !utf8.ValidString(got) {
		t.Errorf("sanitized name is not valid UTF-8: %q", got)
	}
	sum := sha256.Sum256([]byte(name))
	wantSuffix := "-" + hex.EncodeToString(sum[:8])
	if !strings.HasSuffix(got, wantSuffix) {
		t.Errorf("sanitized name %q lacks hash suffix %q", got, wantSuffix)
	}
}

// TestSanitizeNameOverlongNamesStayDistinct guards the 64-bit hash suffix on
// overlong names: two different names sharing a long common prefix must not
// collide, or Put's rename would let one model overwrite another's file.
//
// The names share 247 identical leading bytes — longer than any kept prefix
// (223 bytes under the 64-bit suffix; 231 even under a 32-bit suffix) — so
// the visible part of both sanitized names is IDENTICAL and only the hash
// suffix tells them apart. The tails were brute-forced so the raw SHA-256
// digests agree in their FIRST 4 BYTES while differing in bytes 5–8: the
// distinctness assertion below therefore fails outright against the old
// 32-bit truncation (sum[:4]) that permitted crafted collisions.
func TestSanitizeNameOverlongNamesStayDistinct(t *testing.T) {
	common := strings.Repeat("Llama-3-405B-block-", 13) // 247-byte shared prefix
	a := common + "-coll-00013579!"
	b := common + "-coll-00036791!"
	if len(a) <= maxSanitizedNameLen || len(b) <= maxSanitizedNameLen {
		t.Fatalf("test names must exceed %d bytes: got %d and %d", maxSanitizedNameLen, len(a), len(b))
	}
	sa := sanitizeName(a)
	sb := sanitizeName(b)
	if sa == sb {
		t.Fatalf("distinct overlong names collided: %q", sa)
	}
	for name, s := range map[string]string{"a": sa, "b": sb} {
		if len(s) > maxSanitizedNameLen {
			t.Errorf("sanitized %s is %d bytes, exceeds %d", name, len(s), maxSanitizedNameLen)
		}
		suffix := s[len(s)-17:]
		if suffix[0] != '-' || strings.Trim(suffix[1:], "0123456789abcdef") != "" {
			t.Errorf("sanitized %s has malformed 64-bit hash suffix %q: %q", name, suffix, s)
		}
	}
	// The shared 247-byte prefix spans the whole kept region: everything
	// before the hash suffix must be identical between the two.
	if sa[:len(sa)-17] != sb[:len(sb)-17] {
		t.Errorf("expected only the hash suffix to differ:\n%q\n%q", sa, sb)
	}
}

// TestSanitizeNameTruncatesOnRuneBoundary ensures the byte-level truncation
// of overlong names never splits a multi-byte UTF-8 rune.
func TestSanitizeNameTruncatesOnRuneBoundary(t *testing.T) {
	tests := []struct {
		name     string
		model    string
		wantHead string // runes that must survive intact before the hash suffix
	}{
		{
			// 260 bytes of 2-byte runes: raw cut at byte 223 would split
			// rune 111; expect it walked back to a whole-rune boundary.
			name:     "two-byte runes",
			model:    strings.Repeat("é", 130),
			wantHead: strings.Repeat("é", 111),
		},
		{
			// 270 bytes of 3-byte runes: raw cut at byte 223 would split
			// rune 74; expect truncation back to 74 whole runes.
			name:     "three-byte runes",
			model:    strings.Repeat("日", 90),
			wantHead: strings.Repeat("日", 74),
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if len(tt.model) <= maxSanitizedNameLen {
				t.Fatalf("test name must exceed %d bytes", maxSanitizedNameLen)
			}
			got := sanitizeName(tt.model)
			if !utf8.ValidString(got) {
				t.Fatalf("sanitized name splits a rune: %q", got)
			}
			if len(got) > maxSanitizedNameLen {
				t.Errorf("len=%d exceeds %d", len(got), maxSanitizedNameLen)
			}
			if !strings.HasPrefix(got, tt.wantHead) || got[len(tt.wantHead)] != '-' {
				t.Errorf("unexpected truncation point: %q", got)
			}
		})
	}
}

func TestDistributeHandler_NoModelInPath(t *testing.T) {
	cache := NewCache(t.TempDir(), nil)
	handler := DistributeHandler(cache)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models/", http.NoBody)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func TestDistributeHandler_ModelNotCached(t *testing.T) {
	cache := NewCache(t.TempDir(), nil)
	cache.Init()
	handler := DistributeHandler(cache)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models/nonexistent.gguf", http.NoBody)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected 404, got %d", w.Code)
	}
}

func TestDistributeHandler_ServeFile(t *testing.T) {
	dir := t.TempDir()
	modelsDir := filepath.Join(dir, "models")
	os.MkdirAll(modelsDir, 0o755)

	content := []byte("fake-model-data-for-testing")
	modelPath := filepath.Join(modelsDir, "test-model.gguf")
	os.WriteFile(modelPath, content, testFilePerm)

	cache := NewCache(dir, nil)
	cache.Init()
	handler := DistributeHandler(cache)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models/test-model.gguf", http.NoBody)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
	if w.Body.String() != string(content) {
		t.Errorf("body mismatch")
	}
}

func TestDistributeHandler_MethodNotAllowed(t *testing.T) {
	dir := t.TempDir()
	modelsDir := filepath.Join(dir, "models")
	os.MkdirAll(modelsDir, 0o755)
	os.WriteFile(filepath.Join(modelsDir, "test-model.gguf"), []byte("data"), testFilePerm)

	cache := NewCache(dir, nil)
	cache.Init()
	handler := DistributeHandler(cache)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/test-model.gguf", http.NoBody)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", w.Code)
	}
}

func TestURL(t *testing.T) {
	got := URL("http://10.0.0.1:9876", "llama3.2:1b")
	expected := "http://10.0.0.1:9876/api/v1/models/llama3.2:1b"
	if got != expected {
		t.Errorf("expected %q, got %q", expected, got)
	}

	// Trailing slash should be trimmed
	got = URL("http://10.0.0.1:9876/", "model")
	expected = "http://10.0.0.1:9876/api/v1/models/model"
	if got != expected {
		t.Errorf("expected %q, got %q", expected, got)
	}
}

func TestCacheDownload(t *testing.T) {
	// Start a test server that serves model data
	modelData := []byte("test-model-content-12345")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write(modelData)
	}))
	defer server.Close()

	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	path, err := cache.Get(context.Background(), "test-model", server.URL, "")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}

	if !cache.Has("test-model") {
		t.Error("expected model to be cached")
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data, modelData) {
		t.Errorf("expected %q, got %q", modelData, data)
	}
}

func TestCacheDownloadChecksum(t *testing.T) {
	modelData := []byte("verify-me-98765")

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write(modelData)
	}))
	defer server.Close()

	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	// Wrong checksum should fail
	cache.SetBackoff([]time.Duration{1 * time.Millisecond, 1 * time.Millisecond, 1 * time.Millisecond})
	_, err := cache.Get(context.Background(), "test-model", server.URL, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	if err == nil {
		t.Error("expected error for wrong checksum")
	}
}

func TestCacheDownloadHTTPError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()
	cache.SetBackoff([]time.Duration{1 * time.Millisecond, 1 * time.Millisecond, 1 * time.Millisecond})

	_, err := cache.Get(context.Background(), "test-model", server.URL, "")
	if err == nil {
		t.Error("expected error for HTTP 404")
	}
}

func TestCacheDownloadNoURL(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	cache.Init()

	_, err := cache.Get(context.Background(), "test-model", "", "")
	if err == nil {
		t.Error("expected error for empty URL")
	}
}

func TestCacheGetFromCache(t *testing.T) {
	dir := t.TempDir()
	modelsDir := filepath.Join(dir, "models")
	os.MkdirAll(modelsDir, 0o755)
	os.WriteFile(filepath.Join(modelsDir, "cached-model.bin"), []byte("data"), testFilePerm)

	cache := NewCache(dir, nil)
	cache.Init()

	// Get without URL — should return cached path
	path, err := cache.Get(context.Background(), "cached-model.bin", "", "")
	if err != nil {
		t.Fatalf("Get cached: %v", err)
	}
	if !strings.HasSuffix(path, "cached-model.bin") {
		t.Errorf("unexpected path: %q", path)
	}
}

// TestCachePutSurvivesRestartWithLongName verifies the name→file mapping of
// Put survives a restart: a fresh cache scanning the same directory must
// resolve an overlong model name (stored under a sanitized filename) back
// to its file.
func TestCachePutSurvivesRestartWithLongName(t *testing.T) {
	dir := t.TempDir()
	payload := []byte("restart-round-trip-payload")

	c1 := NewCache(dir, nil)
	if err := c1.Init(); err != nil {
		t.Fatalf("first Init: %v", err)
	}
	longName := strings.Repeat("model-", 45) + "end.gguf" // 278 bytes
	if len(longName) <= maxSanitizedNameLen {
		t.Fatalf("test name must exceed %d bytes", maxSanitizedNameLen)
	}
	if _, err := c1.Put(longName, bytes.NewReader(payload), "", 0); err != nil {
		t.Fatalf("Put: %v", err)
	}

	c2 := NewCache(dir, nil)
	if err := c2.Init(); err != nil {
		t.Fatalf("Init after restart: %v", err)
	}
	if !c2.Has(longName) {
		t.Fatal("model lost after restart: Has() is false for the original name")
	}
	path, err := c2.Get(context.Background(), longName, "", "")
	if err != nil {
		t.Fatalf("Get after restart: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Errorf("payload mismatch after restart: got %q", got)
	}
}

// TestCachePutSurvivesRestartWithSanitizedName covers the other mapping-loss
// case: names whose separators are rewritten ("/"→"_") also differ from
// their on-disk filename.
func TestCachePutSurvivesRestartWithSanitizedName(t *testing.T) {
	dir := t.TempDir()
	payload := []byte("org-repo-model")

	c1 := NewCache(dir, nil)
	c1.Init()
	if _, err := c1.Put("org/repo:model.gguf", bytes.NewReader(payload), "", 0); err != nil {
		t.Fatalf("Put: %v", err)
	}

	c2 := NewCache(dir, nil)
	if err := c2.Init(); err != nil {
		t.Fatalf("Init after restart: %v", err)
	}
	path, err := c2.Get(context.Background(), "org/repo:model.gguf", "", "")
	if err != nil {
		t.Fatalf("Get after restart: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Errorf("payload mismatch after restart: got %q", got)
	}
}
