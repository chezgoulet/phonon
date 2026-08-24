package model

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// sha256hex hashes b and returns the lowercase hex digest.
func sha256hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// TestContainsPath_Semantics covers the path-containment helper directly.
func TestContainsPath_Semantics(t *testing.T) {
	cases := []struct {
		root, p string
		want    bool
	}{
		{"/cache", "/cache/models/m.gguf", true},
		{"/cache", "/cache/models/sub/m.gguf", true}, // deep child
		{"/cache/", "/cache/models/m.gguf", true},    // root with trailing slash
		{"/cache", "/cachefile", false},              // prefix-sibling must not match
		{"/cache", "/cache-tmp/x", false},
		{"/cache", "/other/m.gguf", false},
		{"/cache", "/cache", true}, // the root itself is "within"
		// Unclean paths must be normalized before comparison, so a caller
		// that skips EvalSymlinks cannot smuggle ".." components through.
		{"/cache", "/cache/../cache-evil/x", false},
		{"/cache", "/cache/./models/../models/m.gguf", true},
		{"/cache/", "/cache//models/m.gguf", true},
		{"/cache", "/cache..", false},
	}
	for _, tc := range cases {
		if got := containsPath(tc.root, tc.p); got != tc.want {
			t.Errorf("containsPath(%q, %q) = %v, want %v", tc.root, tc.p, got, tc.want)
		}
	}
}

// TestPut_RejectsTmpSymlinkEscape is the regression test for the
// HasPrefix symlink bypass: a pre-placed symlink at the tmp destination
// pointing outside the cache root must cause Put to fail (not follow the
// link and write through it).
func TestPut_RejectsTmpSymlinkEscape(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	secret := filepath.Join(dir, "..", "escape-target.bin")
	payload := []byte("uploaded model bytes")
	name := "innocent-model.gguf"

	if err := os.Symlink(secret, filepath.Join(dir, cacheTmpDir, name+".uploading")); err != nil {
		t.Skipf("symlinks unavailable on this platform: %v", err)
	}

	_, err := cache.Put(name, bytes.NewReader(payload), sha256hex(payload), 0)
	if err == nil {
		t.Fatal("Put succeeded through a symlinked tmp destination — containment bypass")
	}
	if !strings.Contains(err.Error(), "outside cache root") {
		t.Errorf("expected containment error, got: %v", err)
	}
	// The escape target must not exist — the write never went through.
	if _, statErr := os.Lstat(secret); statErr == nil {
		t.Error("file was written outside the cache root via symlink")
	}
}

// TestPut_NameSanitizationStillApplies pins that sanitizeName cannot be
// defeated by a dangerous name: exact special components stay rejected, and
// separator-laden names are folded to ONE contained component — nothing ever
// lands outside the cache root.
func TestPut_NameSanitizationStillApplies(t *testing.T) {
	dir := t.TempDir()
	cache := NewCache(dir, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	payload := []byte("data")
	for _, name := range []string{"..", ".", ""} {
		if _, err := cache.Put(name, bytes.NewReader(payload), "", 0); err == nil {
			t.Errorf("Put accepted dangerous model name %q", name)
		}
	}

	// "../../etc/passwd" folds to the single component ".._.._etc_passwd":
	// legal since #310 (it merely contains ".."), but it must land INSIDE
	// the models dir and nowhere else.
	const sneaky = "../../etc/passwd"
	if _, err := cache.Put(sneaky, bytes.NewReader(payload), "", 0); err != nil {
		t.Fatalf("Put(%q): %v", sneaky, err)
	}
	stored := filepath.Join(dir, cacheModelsDir, sanitizeName(sneaky))
	if _, err := os.Stat(stored); err != nil {
		t.Errorf("folded name not stored inside models dir: %v", err)
	}
	var escaped []string
	err := filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() && !strings.HasPrefix(path, filepath.Join(dir, cacheModelsDir)+string(filepath.Separator)) {
			escaped = append(escaped, path)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walk: %v", err)
	}
	if len(escaped) > 0 {
		t.Errorf("files written outside cache models dir: %v", escaped)
	}
}
