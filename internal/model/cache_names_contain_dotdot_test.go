package model

import (
	"bytes"
	"context"
	"io"
	"os"
	"testing"
)

// TestCacheDotDotInNameUploadAndServe is the #310 regression test: a model
// name that merely CONTAINS ".." (e.g. "my..model") is a legal single path
// component — sanitizeName leaves it intact and it must round-trip through
// Put AND serve via OpenModel/Get. Only the exact components "." and ".."
// (and the empty name) are genuine traversal/special values.
func TestCacheDotDotInNameUploadAndServe(t *testing.T) {
	dir := t.TempDir()
	c := NewCache(dir, nil)
	if err := c.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	const name = "my..model"
	payload := []byte("payload-for-my..model")

	// 1. Upload must SUCCEED (it already did before the fix; keep it pinned).
	if _, err := c.Put(name, bytes.NewReader(payload), "", 0); err != nil {
		t.Fatalf("Put(%q): %v", name, err)
	}

	// 2. Serve via OpenModel must SUCCEED and return the file's content.
	//    Before #310 this 500'd with "path traversal sequences are not
	//    allowed" even though upload accepted the same name.
	f, fi, err := c.OpenModel(name)
	if err != nil {
		t.Fatalf("OpenModel(%q): %v", name, err)
	}
	defer f.Close()
	if fi == nil {
		t.Fatal("OpenModel returned nil FileInfo")
	}
	got, err := io.ReadAll(f)
	if err != nil {
		t.Fatalf("read served model: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Errorf("served content = %q, want %q", got, payload)
	}
	if gotSize := fi.Size(); gotSize != int64(len(payload)) {
		t.Errorf("FileInfo.Size() = %d, want %d", gotSize, len(payload))
	}

	// The Get download entry point had the identical over-broad guard; pin
	// that it no longer over-rejects a cached name containing "..".
	path, err := c.Get(context.Background(), name, "", "")
	if err != nil {
		t.Errorf("Get(%q): %v", name, err)
	} else if _, err := os.Stat(path); err != nil {
		t.Errorf("Get(%q) path %q not stat-able: %v", name, path, err)
	}

	// 3. The genuinely dangerous exact-component names stay rejected.
	for _, bad := range []string{"..", ".", ""} {
		f, fi, err := c.OpenModel(bad)
		if f != nil {
			f.Close()
		}
		if err == nil {
			t.Errorf("OpenModel(%q): expected rejection, got success (fi=%v)", bad, fi)
		}
		if _, err := c.Get(context.Background(), bad, "", ""); err == nil {
			t.Errorf("Get(%q): expected rejection, got success", bad)
		}
	}
}
