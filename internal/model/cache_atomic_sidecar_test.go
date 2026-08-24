package model

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestCachePutSidecarWrittenBeforeRename pins the sidecar-before-rename
// ordering invariant (#306): persistOriginalName must complete BEFORE the
// promote rename, so a crash can never leave a stale sidecar binding new
// content to the previous owner's name. Restart tests pass under either
// ordering, so the injectable hook is the only seam that can catch a
// reordering regression. It also pins the atomic-write fix by asserting no
// .tmp-* residue remains in the .names dir after Put.
func TestCachePutSidecarWrittenBeforeRename(t *testing.T) {
	dir := t.TempDir()
	c := NewCache(dir, nil)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	const name = "org/ordering-model"
	base := sanitizeName(name)
	if base == name {
		t.Fatalf("test requires a sanitized name distinct from the original; got %q", base)
	}
	sidecar := filepath.Join(dir, cacheNamesDir, base)

	hookFired := false
	c.SetTestHookAfterSidecar(func() {
		hookFired = true

		// (a) Sidecar must already EXIST and hold the original name at
		// the moment between persistOriginalName and the promote rename.
		raw, err := os.ReadFile(sidecar)
		if err != nil {
			t.Errorf("sidecar missing at hook time (ordering violated?): %v", err)
			return
		}
		if got := strings.TrimSpace(string(raw)); got != name {
			t.Errorf("sidecar content at hook time: got %q, want %q", got, name)
		}

		// Ordering proof: the content must NOT be promoted yet.
		if _, err := os.Stat(filepath.Join(dir, cacheModelsDir, base)); !os.IsNotExist(err) {
			t.Error("model file already present before the promote rename ran")
		}
	})

	content := []byte("payload-owned-by-ordering-model")
	if _, err := c.Put(name, bytes.NewReader(content), "", 0); err != nil {
		t.Fatalf("Put(%q): %v", name, err)
	}

	if !hookFired {
		t.Fatal("test hook did not fire between persistOriginalName and promote")
	}

	raw, err := os.ReadFile(sidecar)
	if err != nil {
		t.Fatalf("sidecar missing after Put: %v", err)
	}
	if got := strings.TrimSpace(string(raw)); got != name {
		t.Errorf("sidecar content after Put: got %q, want %q", got, name)
	}

	// Truncation-fix residue check: the atomic tmp+rename write must leave
	// no .tmp-* files behind in the .names directory.
	residue, err := filepath.Glob(filepath.Join(dir, cacheNamesDir, ".tmp-*"))
	if err != nil {
		t.Fatal(err)
	}
	if len(residue) != 0 {
		t.Errorf("stale temp files left in .names dir: %v", residue)
	}
}
