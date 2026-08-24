package model

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// victimSentinel mirrors the reported reproduction: a 29-byte victim file
// that O_CREATE|O_TRUNC zeroed through the planted symlink before the
// post-open containment check could refuse the streamed body.
const victimSentinel = "VICTIM-FILE-29-BYTES-EXACTLY!" // len == 29

// containmentTestServer serves body on every request.
func containmentTestServer(t *testing.T, body []byte) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// assertFileUnchanged fails t if path's content differs from want.
func assertFileUnchanged(t *testing.T, path string, want []byte) {
	t.Helper()
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("victim file was modified: got %d bytes %q, want %d bytes", len(got), got, len(want))
	}
}

// TestDownload_PlantedDestSymlinkTruncatesNothing is the regression test for
// the blocking finding: a final-component symlink planted at the download
// destination must NOT be followed. Before the fix, openFileForDownload's
// O_CREATE|O_TRUNC truncated the outside target before the post-open
// EvalSymlinks check refused the streamed body (victim 29 → 0 bytes).
func TestDownload_PlantedDestSymlinkTruncatesNothing(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "cache")
	victim := filepath.Join(base, "victim-on-host.bin")
	sentinel := []byte(victimSentinel)
	if err := os.WriteFile(victim, sentinel, 0o644); err != nil {
		t.Fatal(err)
	}

	cache := NewCache(root, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	dest := filepath.Join(root, cacheModelsDir, "innocent-model.gguf")
	if err := os.Symlink(victim, dest); err != nil {
		t.Skipf("symlinks unavailable on this platform: %v", err)
	}

	srv := containmentTestServer(t, []byte("attacker-controlled-body"))
	err := cache.downloadOnce(context.Background(), srv.URL, dest, "")
	if err == nil {
		t.Fatal("downloadOnce succeeded through a planted symlink — containment bypass")
	}
	if !strings.Contains(err.Error(), "symlink") && !strings.Contains(err.Error(), "outside cache root") {
		t.Errorf("expected containment-class error, got: %v", err)
	}
	assertFileUnchanged(t, victim, sentinel)
}

// TestDownload_DirComponentSymlinkRejected plants a DIRECTORY-COMPONENT
// symlink (the models dir replaced by a sibling-prefixed <root>-evil/models)
// and asserts the download writes nothing outside the cache root — not even
// truncating an existing victim inside the evil tree.
func TestDownload_DirComponentSymlinkRejected(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "cache")
	evilModels := filepath.Join(base, root+"-evil", cacheModelsDir)
	if err := os.MkdirAll(evilModels, 0o755); err != nil {
		t.Fatal(err)
	}
	victim := filepath.Join(evilModels, "preexisting-victim.bin")
	sentinel := []byte(victimSentinel)
	if err := os.WriteFile(victim, sentinel, 0o644); err != nil {
		t.Fatal(err)
	}

	cache := NewCache(root, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	// Replace the models dir with a symlink into the sibling-prefixed tree.
	modelsDir := filepath.Join(root, cacheModelsDir)
	if err := os.Remove(modelsDir); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(evilModels, modelsDir); err != nil {
		t.Skipf("symlinks unavailable on this platform: %v", err)
	}

	srv := containmentTestServer(t, []byte("attacker-controlled-body"))
	dest := filepath.Join(modelsDir, "escape.gguf")
	err := cache.downloadOnce(context.Background(), srv.URL, dest, "")
	if err == nil {
		t.Fatal("downloadOnce succeeded through a symlinked models dir — containment bypass")
	}
	assertFileUnchanged(t, victim, sentinel)
	entries, rerr := os.ReadDir(evilModels)
	if rerr != nil {
		t.Fatal(rerr)
	}
	for _, e := range entries {
		if e.Name() != "preexisting-victim.bin" {
			t.Errorf("file written outside cache root via directory symlink: %s", e.Name())
		}
	}
}

// TestPut_RejectsDirectoryComponentSymlinkEscape is the regression test for
// the real remaining bypass found in adversarial review: a DIRECTORY-COMPONENT
// symlink. The pre-existing file-level tmp-symlink test covered a case that
// was never exploitable; here the cache's .tmp or models dir itself is
// replaced by a symlink into a sibling-prefixed <root>-evil tree.
func TestPut_RejectsDirectoryComponentSymlinkEscape(t *testing.T) {
	payload := []byte("uploaded model bytes")
	sum := sha256hex(payload)

	t.Run("tmp dir symlinked", func(t *testing.T) {
		base := t.TempDir()
		root := filepath.Join(base, "cache")
		evilTmp := filepath.Join(base, root+"-evil", cacheTmpDir)
		if err := os.MkdirAll(evilTmp, 0o755); err != nil {
			t.Fatal(err)
		}
		cache := NewCache(root, nil)
		if err := cache.Init(); err != nil {
			t.Fatalf("Init: %v", err)
		}
		tmpDir := filepath.Join(root, cacheTmpDir)
		if err := os.Remove(tmpDir); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(evilTmp, tmpDir); err != nil {
			t.Skipf("symlinks unavailable on this platform: %v", err)
		}

		_, err := cache.Put("escape-model.gguf", bytes.NewReader(payload), sum, 0)
		if err == nil {
			t.Fatal("Put succeeded through a symlinked .tmp dir — containment bypass")
		}
		assertEvilTreeEmpty(t, evilTmp)
	})

	t.Run("models dir symlinked", func(t *testing.T) {
		base := t.TempDir()
		root := filepath.Join(base, "cache")
		evilModels := filepath.Join(base, root+"-evil", cacheModelsDir)
		if err := os.MkdirAll(evilModels, 0o755); err != nil {
			t.Fatal(err)
		}
		cache := NewCache(root, nil)
		if err := cache.Init(); err != nil {
			t.Fatalf("Init: %v", err)
		}
		modelsDir := filepath.Join(root, cacheModelsDir)
		if err := os.Remove(modelsDir); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(evilModels, modelsDir); err != nil {
			t.Skipf("symlinks unavailable on this platform: %v", err)
		}

		_, err := cache.Put("escape-model.gguf", bytes.NewReader(payload), sum, 0)
		if err == nil {
			t.Fatal("Put succeeded through a symlinked models dir — containment bypass")
		}
		assertEvilTreeEmpty(t, evilModels)
	})
}

// assertEvilTreeEmpty walks dir and fails t if any regular file exists in it.
func assertEvilTreeEmpty(t *testing.T, dir string) {
	t.Helper()
	var found []string
	err := filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() {
			found = append(found, path)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walk %s: %v", dir, err)
	}
	if len(found) > 0 {
		t.Errorf("files were written outside the cache root: %v", found)
	}
}

// TestGet_RejectsTraversalNames pins entry-point symmetry with Put(): names
// containing ".." are rejected before reaching path construction (#246).
func TestGet_RejectsTraversalNames(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	for _, name := range []string{"../escape.gguf", "a/../b.gguf", ".."} {
		if _, err := cache.Get(context.Background(), name, "", ""); err == nil {
			t.Errorf("Get accepted dangerous model name %q", name)
		} else if !strings.Contains(err.Error(), "rejected") {
			t.Errorf("expected traversal rejection for %q, got: %v", name, err)
		}
	}
}
