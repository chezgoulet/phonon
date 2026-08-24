package model

import (
	"bytes"
	"context"
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
	if got := sanitizeName("meta-llama/Llama-3.2-1B:Q4_K_M"); got != "meta-llama_Llama-3.2-1B_Q4_K_M" {
		t.Errorf("unexpected: %q", got)
	}
}

// TestSanitizeNameOverlongNamesStayDistinct guards the 64-bit hash suffix on
// overlong names: two different names sharing a long common prefix must not
// collide, or Put's rename would let one model overwrite another's file.
func TestSanitizeNameOverlongNamesStayDistinct(t *testing.T) {
	common := strings.Repeat("Llama-3-405B-block-", 12) // 228-byte shared prefix
	a := common + strings.Repeat("a", 24) + "-alpha"
	b := common + strings.Repeat("b", 24) + "-bravo"
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
