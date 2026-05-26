package model

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

// TestCacheDownloadResume verifies that partial downloads resume using HTTP Range requests.
func TestCacheDownloadResume(t *testing.T) {
	modelData := []byte("this is a multi-gigabyte model file that we want to resume")

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rangeHeader := r.Header.Get("Range")
		if rangeHeader == "" {
			// First request — return full file
			w.WriteHeader(http.StatusOK)
			w.Write(modelData)
			return
		}

		// Parse "bytes=N-" format
		var start int
		if _, err := fmt.Sscanf(rangeHeader, "bytes=%d-", &start); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		if start >= len(modelData) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}

		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, len(modelData)-1, len(modelData)))
		w.WriteHeader(http.StatusPartialContent)
		w.Write(modelData[start:])
	}))
	defer server.Close()

	dir := t.TempDir()
	dest := dir + "/model.bin"

	// Create a partial file (first 20 bytes)
	partial := modelData[:20]
	if err := os.WriteFile(dest, partial, 0o644); err != nil {
		t.Fatalf("write partial: %v", err)
	}

	cache := NewCache(dir, nil)
	cache.log = testLogger(t)

	// Call downloadOnce — should detect partial file and resume
	err := cache.downloadOnce(context.Background(), server.URL, dest, "")
	if err != nil {
		t.Fatalf("downloadOnce with resume: %v", err)
	}

	// Verify complete file
	result, err := os.ReadFile(dest)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(result, modelData) {
		t.Errorf("expected %q, got %q", modelData, result)
	}

}

// TestCacheDownloadResumeHTTPError verifies that failure during resume cleans up properly.
func TestCacheDownloadResumeHTTPError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("full file"))
	}))
	defer server.Close()

	dir := t.TempDir()
	dest := dir + "/model.bin"
	os.WriteFile(dest, []byte("partial-"), 0o644)

	cache := NewCache(dir, nil)
	cache.log = testLogger(t)

	// No Range support — server returns 200, should truncate and re-download
	err := cache.downloadOnce(context.Background(), server.URL, dest, "")
	if err != nil {
		t.Fatalf("downloadOnce: %v", err)
	}

	result, err := os.ReadFile(dest)
	if err != nil {
		t.Fatal(err)
	}
	if string(result) != "full file" {
		t.Errorf("expected 'full file', got %q", result)
	}
}

// TestCacheDownloadResumeCompleteFile verifies that a fully-downloaded file (416 Range Not Satisfiable)
// is treated as success — the file is already complete.
func TestCacheDownloadResumeServerRangeNotSatisfiable(t *testing.T) {
	modelData := []byte("complete file")
	dir := t.TempDir()
	dest := dir + "/model.bin"

	// Write the complete file
	if err := os.WriteFile(dest, modelData, 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Return 416 if range starts at or past the end
		rangeHeader := r.Header.Get("Range")
		if strings.HasPrefix(rangeHeader, "bytes=") {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.Write(modelData)
	}))
	defer server.Close()

	cache := NewCache(dir, nil)
	cache.log = testLogger(t)

	// 416 means file is already complete — should be treated as success
	err := cache.downloadOnce(context.Background(), server.URL, dest, "")
	if err != nil {
		t.Fatalf("416 should be treated as success: %v", err)
	}

	// Verify file content unchanged
	result, err := os.ReadFile(dest)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(result, modelData) {
		t.Errorf("expected %q, got %q", modelData, result)
	}
}

// testLogger creates a slog.Logger for tests.
func testLogger(t *testing.T) *slog.Logger {
	t.Helper()
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError}))
}
