package api

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// TestModelDownloadRejectsPlantedSymlink pins the #304 fix: the serve path
// opens through the pinned models-dir fd with O_NOFOLLOW, so a symlink
// planted at a cached model's base name can never redirect the response to
// content outside the cache root. The old os.Open(path) serve path followed
// such a link (TOCTOU) and served the target's bytes.
func TestModelDownloadRejectsPlantedSymlink(t *testing.T) {
	cache, dir := uploadTestCache(t)
	payload := []byte("GGUF cached model bytes")
	if _, err := cache.Put("tiny-llm", bytes.NewReader(payload), "", 0); err != nil {
		t.Fatal(err)
	}

	dl := NewModelDownloadHandler(cache, dir)
	mux := http.NewServeMux()
	dl.RegisterRoutes(mux)

	get := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "/v1/models/tiny-llm/download", http.NoBody)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		return w
	}

	// Baseline: with the cache intact the handler serves the cached bytes.
	if w := get(); w.Code != http.StatusOK || !bytes.Equal(w.Body.Bytes(), payload) {
		t.Fatalf("intact cache: expected 200 with cached bytes, got %d: %q", w.Code, w.Body.String())
	}

	// Attacker swaps the directory entry at the model's base name for a
	// symlink pointing OUTSIDE the cache root.
	const secret = "OUTSIDE-SECRET-NOT-IN-CACHE"
	outside := filepath.Join(t.TempDir(), "secret.gguf")
	if err := os.WriteFile(outside, []byte(secret), 0o644); err != nil {
		t.Fatal(err)
	}
	modelPath := filepath.Join(dir, "models", "tiny-llm")
	if err := os.Remove(modelPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, modelPath); err != nil {
		t.Skipf("cannot create symlinks here: %v", err)
	}

	// The planted link must be refused (ELOOP via O_NOFOLLOW), never read:
	// the secret must not appear anywhere in the response.
	w := get()
	if bytes.Contains(w.Body.Bytes(), []byte(secret)) {
		t.Fatalf("served symlink target outside cache root (%d): %q", w.Code, w.Body.String())
	}
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 refusing planted symlink, got %d: %q", w.Code, w.Body.String())
	}
}
