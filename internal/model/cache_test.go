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

func TestDistributeHandler_NoModelInPath(t *testing.T) {
	cache := NewCache(t.TempDir(), nil)
	handler := DistributeHandler(cache)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models/", nil)
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

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models/nonexistent.gguf", nil)
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

	req := httptest.NewRequest(http.MethodGet, "/api/v1/models/test-model.gguf", nil)
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

	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/test-model.gguf", nil)
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
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
