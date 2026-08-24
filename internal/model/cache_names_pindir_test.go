package model

// Regression tests for the .names hardening cluster:
//
//   - #313: persistOriginalName must write through an fd-pinned .names dir
//     (openat + O_NOFOLLOW). A planted symlink in place of .names — or of
//     its parent models dir — must be refused, never followed.
//   - #314: Init creates .names and sweeps orphaned ".tmp-*" sidecar temps
//     left by a crash between create and rename.
//   - #315: sidecars are deliberately written 0644 (pre-#306 behavior).

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// walkSnapshot lists every path under root (relative), sorted, so a test can
// assert the tree is byte-for-byte unchanged after an attack attempt.
func walkSnapshot(t *testing.T, root string) []string {
	t.Helper()
	var got []string
	err := filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return err
		}
		got = append(got, rel)
		return nil
	})
	if err != nil {
		t.Fatalf("walk %s: %v", root, err)
	}
	return got
}

// TestPersistOriginalName_RefusesPlantedSymlink mirrors the existing
// models-dir symlink-refusal tests (#313): a symlink planted in place of
// .names (or of the models dir above it) must fail the pin with an ELOOP/
// ENOTDIR-class error instead of being followed out of the cache root. No
// byte may reach the evil tree, and the planted link itself must survive.
func TestPersistOriginalName_RefusesPlantedSymlink(t *testing.T) {
	t.Run(".names leaf symlinked", func(t *testing.T) {
		refusePlantedNamesSymlink(t, true)
	})
	t.Run("models dir symlinked", func(t *testing.T) {
		refusePlantedNamesSymlink(t, false)
	})
}

func refusePlantedNamesSymlink(t *testing.T, swapLeaf bool) {
	base := t.TempDir()
	root := filepath.Join(base, "cache")

	// Evil tree outside the cache root, shaped exactly like the real one,
	// holding a victim sidecar the attack would truncate or replace.
	evilRoot := filepath.Join(base, root+"-evil")
	evilModels := filepath.Join(evilRoot, cacheModelsDir)
	evilNames := filepath.Join(evilModels, cacheNamesLeaf)
	if err := os.MkdirAll(evilNames, 0o755); err != nil {
		t.Fatal(err)
	}
	victim := filepath.Join(evilNames, "escape")
	const sentinel = "VICTIM-SIDECAR-CONTENT"
	if err := os.WriteFile(victim, []byte(sentinel), 0o644); err != nil {
		t.Fatal(err)
	}

	c := NewCache(root, nil)
	c.log = testLogger(t)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	link := filepath.Join(root, cacheModelsDir)
	if swapLeaf {
		link = filepath.Join(link, cacheNamesLeaf)
	}
	// RemoveAll: the models-dir variant must remove a non-empty dir (.names
	// now lives inside it); on a symlink RemoveAll unlinks just the link.
	if err := os.RemoveAll(link); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(evilRoot, link); err != nil {
		t.Skipf("symlinks unavailable on this platform: %v", err)
	}

	before := walkSnapshot(t, evilRoot)
	c.persistOriginalName(filepath.Join(root, cacheModelsDir, "m.gguf"), "org/attack-model")
	after := walkSnapshot(t, evilRoot)

	if strings.Join(before, "|") != strings.Join(after, "|") {
		t.Errorf("evil tree changed through a planted symlink:\nbefore: %v\nafter:  %v", before, after)
	}
	if raw, err := os.ReadFile(victim); err != nil || string(raw) != sentinel {
		t.Errorf("victim damaged (%v): %q", err, raw)
	}
	if fi, err := os.Lstat(link); err != nil || fi.Mode()&os.ModeSymlink == 0 {
		t.Errorf("planted symlink was replaced or removed: %v", err)
	}

	// Positive control: with the symlink removed the very same call must
	// succeed, proving the refusal came from the planted link and not from
	// a broken write path.
	if err := os.RemoveAll(link); err != nil {
		t.Fatal(err)
	}
	if !swapLeaf {
		// The models-dir variant removed the real models dir wholesale;
		// restore it so the control exercises the normal recreate+re-pin path.
		if err := os.MkdirAll(filepath.Join(root, cacheModelsDir), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	c.persistOriginalName(filepath.Join(root, cacheModelsDir, "m.gguf"), "org/attack-model")
	raw, err := os.ReadFile(filepath.Join(root, cacheNamesDir, "m.gguf"))
	if err != nil {
		t.Fatalf("control write failed: %v", err)
	}
	if got := strings.TrimSpace(string(raw)); got != "org/attack-model" {
		t.Errorf("control sidecar content: got %q, want %q", got, "org/attack-model")
	}
}

// TestInit_CreatesNamesDir pins the #314 requirement that Init creates the
// .names sidecar directory (it previously appeared lazily on first write).
func TestInit_CreatesNamesDir(t *testing.T) {
	dir := t.TempDir()
	c := NewCache(dir, nil)
	c.log = testLogger(t)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	fi, err := os.Stat(filepath.Join(dir, cacheNamesDir))
	if err != nil {
		t.Fatalf("Init did not create %s: %v", cacheNamesDir, err)
	}
	if !fi.IsDir() {
		t.Errorf("%s is not a directory", cacheNamesDir)
	}
}

// TestInit_SweepsOrphanedNamesTemps pins the #314 sweep: orphaned ".tmp-*"
// temps from a crash between CreateTemp and Rename are removed during Init,
// while legitimate sidecars are left untouched.
func TestInit_SweepsOrphanedNamesTemps(t *testing.T) {
	dir := t.TempDir()
	namesDir := filepath.Join(dir, cacheNamesDir)
	if err := os.MkdirAll(namesDir, 0o755); err != nil {
		t.Fatal(err)
	}
	orphan := filepath.Join(namesDir, ".tmp-"+strings.Repeat("a", 16))
	if err := os.WriteFile(orphan, []byte("half-written"), 0o644); err != nil {
		t.Fatal(err)
	}
	kept := filepath.Join(namesDir, sanitizeName("org/model"))
	if err := os.WriteFile(kept, []byte("org/model"), 0o644); err != nil {
		t.Fatal(err)
	}
	// A model LEGITIMATELY named with the ".tmp-" prefix but NOT the 16-hex
	// temp shape must survive the sweep (#323): sanitizeName leaves dots
	// untouched, so this is a real sidecar, not an orphan. ".tmp-model" is
	// clearly not the .tmp-<16hex> temp pattern.
	legalDotTmp := filepath.Join(namesDir, ".tmp-model")
	if err := os.WriteFile(legalDotTmp, []byte("org/legal-dot-tmp"), 0o644); err != nil {
		t.Fatal(err)
	}

	c := NewCache(dir, nil)
	c.log = testLogger(t)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	if _, err := os.Lstat(orphan); !os.IsNotExist(err) {
		t.Errorf("orphaned .names temp survived Init sweep: %v", err)
	}
	if raw, err := os.ReadFile(kept); err != nil || string(raw) != "org/model" {
		t.Errorf("legitimate sidecar damaged by sweep (%v): %q", err, raw)
	}
	if raw, err := os.ReadFile(legalDotTmp); err != nil || string(raw) != "org/legal-dot-tmp" {
		t.Errorf("legal .tmp-prefixed sidecar wrongly swept (#323) (%v): %q", err, raw)
	}
}

// TestPersistOriginalName_SidecarMode0644 pins #315: the sidecar is
// deliberately world-readable again (pre-#306 behavior), regardless of
// umask, and holds the original model name.
func TestPersistOriginalName_SidecarMode0644(t *testing.T) {
	dir := t.TempDir()
	c := NewCache(dir, nil)
	c.log = testLogger(t)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	c.persistOriginalName(filepath.Join(dir, cacheModelsDir, "model.gguf"), "org/original-model")

	sidecar := filepath.Join(dir, cacheNamesDir, "model.gguf")
	fi, err := os.Stat(sidecar)
	if err != nil {
		t.Fatalf("sidecar missing: %v", err)
	}
	if got := fi.Mode().Perm(); got != 0o644 {
		t.Errorf("sidecar mode: got %04o, want 0644 (#315 deliberate restore)", got)
	}
	raw, err := os.ReadFile(sidecar)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.TrimSpace(string(raw)); got != "org/original-model" {
		t.Errorf("sidecar content: got %q, want %q", got, "org/original-model")
	}
}
