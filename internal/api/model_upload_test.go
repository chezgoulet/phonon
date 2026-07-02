package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/chezgoulet/phonon/internal/model"
	"github.com/chezgoulet/phonon/internal/registry"
)

func uploadTestCache(t *testing.T) (*model.Cache, string) {
	t.Helper()
	dir := t.TempDir()
	c := model.NewCache(dir, nil)
	if err := c.Init(); err != nil {
		t.Fatal(err)
	}
	return c, dir
}

// buildUpload creates a multipart body with name/checksum fields + file.
func buildUpload(t *testing.T, name, checksum string, payload []byte) (*bytes.Buffer, string) {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	if name != "" {
		if err := mw.WriteField("name", name); err != nil {
			t.Fatal(err)
		}
	}
	if checksum != "" {
		if err := mw.WriteField("checksum", checksum); err != nil {
			t.Fatal(err)
		}
	}
	fw, err := mw.CreateFormFile("file", "uploaded.gguf")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := fw.Write(payload); err != nil {
		t.Fatal(err)
	}
	mw.Close()
	return &buf, mw.FormDataContentType()
}

func sha(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

func TestModelUploadHappyPath(t *testing.T) {
	cache, dir := uploadTestCache(t)
	var registered []string
	h := NewModelUploadHandler(cache, WithModelRegistration(func(n string) {
		registered = append(registered, n)
	}))
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	payload := []byte("GGUF fake model bytes")
	body, ctype := buildUpload(t, "tiny-llm", sha(payload), payload)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", w.Code, w.Body.String())
	}
	var meta struct {
		Name      string `json:"name"`
		SizeBytes int64  `json:"size_bytes"`
		SHA256    string `json:"sha256"`
	}
	if err := json.NewDecoder(w.Body).Decode(&meta); err != nil {
		t.Fatal(err)
	}
	if meta.Name != "tiny-llm" || meta.SizeBytes != int64(len(payload)) || meta.SHA256 != sha(payload) {
		t.Errorf("metadata = %+v", meta)
	}

	// File landed in the same cache layout the download handler serves.
	onDisk, err := os.ReadFile(filepath.Join(dir, "models", "tiny-llm"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(onDisk, payload) {
		t.Error("stored bytes differ from upload")
	}
	if !cache.Has("tiny-llm") {
		t.Error("cache should register the uploaded model")
	}
	if len(registered) != 1 || registered[0] != "tiny-llm" {
		t.Errorf("model list registration = %v", registered)
	}

	// The existing download handler can serve it immediately.
	dl := NewModelDownloadHandler(cache, dir)
	dmux := http.NewServeMux()
	dl.RegisterRoutes(dmux)
	dreq := httptest.NewRequest(http.MethodGet, "/v1/models/tiny-llm/download", http.NoBody)
	dw := httptest.NewRecorder()
	dmux.ServeHTTP(dw, dreq)
	if dw.Code != http.StatusOK || !bytes.Equal(dw.Body.Bytes(), payload) {
		t.Errorf("download after upload: code=%d", dw.Code)
	}
}

func TestModelUploadChecksumHeaderVariant(t *testing.T) {
	cache, _ := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	payload := []byte("bytes")
	body, ctype := buildUpload(t, "m", "", payload) // no checksum form field
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	req.Header.Set(ChecksumHeader, sha(payload))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201 with header checksum, got %d: %s", w.Code, w.Body.String())
	}
}

func TestModelUploadChecksumMismatch(t *testing.T) {
	cache, dir := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body, ctype := buildUpload(t, "bad", sha([]byte("different bytes")), []byte("actual bytes"))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 on mismatch, got %d", w.Code)
	}
	if cache.Has("bad") {
		t.Error("mismatched upload must not be registered")
	}
	if _, err := os.Stat(filepath.Join(dir, "models", "bad")); !os.IsNotExist(err) {
		t.Error("mismatched upload must be deleted from disk")
	}
	// tmp dir must not accumulate partial files
	entries, _ := os.ReadDir(filepath.Join(dir, ".tmp"))
	if len(entries) != 0 {
		t.Errorf("tmp dir should be clean, has %d entries", len(entries))
	}
}

func TestModelUploadMissingChecksum(t *testing.T) {
	cache, _ := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	body, ctype := buildUpload(t, "m", "", []byte("bytes"))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 without checksum, got %d", w.Code)
	}
}

func TestModelUploadSizeLimit(t *testing.T) {
	cache, _ := uploadTestCache(t)
	h := NewModelUploadHandler(cache, WithUploadMaxBytes(16))
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	payload := bytes.Repeat([]byte("x"), 64)
	body, ctype := buildUpload(t, "huge", sha(payload), payload)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413, got %d", w.Code)
	}
	if cache.Has("huge") {
		t.Error("oversized upload must not be registered")
	}
}

func TestModelUploadSingleConcurrentUpload(t *testing.T) {
	cache, _ := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	// Occupy the semaphore as if an upload were in flight.
	h.sem <- struct{}{}
	defer func() { <-h.sem }()

	payload := []byte("bytes")
	body, ctype := buildUpload(t, "m", sha(payload), payload)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusConflict {
		t.Fatalf("expected 409 while another upload runs, got %d", w.Code)
	}
	if w.Header().Get("Retry-After") == "" {
		t.Error("409 should carry Retry-After")
	}
}

func TestModelUploadAppearsInModelList(t *testing.T) {
	cache, _ := uploadTestCache(t)
	openai := NewOpenAIHandler(registry.New())
	h := NewModelUploadHandler(cache, WithModelRegistration(func(n string) {
		openai.AddModel(n, "upload")
	}))

	umux := http.NewServeMux()
	h.RegisterRoutes(umux)
	payload := []byte("model!")
	body, ctype := buildUpload(t, "uploaded-model", sha(payload), payload)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	umux.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("upload failed: %d", w.Code)
	}

	omux := http.NewServeMux()
	openai.RegisterRoutes(omux)
	lw := httptest.NewRecorder()
	omux.ServeHTTP(lw, httptest.NewRequest(http.MethodGet, "/v1/models", http.NoBody))
	var list ModelListResponse
	if err := json.NewDecoder(lw.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, m := range list.Data {
		if m.ID == "uploaded-model" && m.OwnedBy == "upload" {
			found = true
		}
	}
	if !found {
		t.Errorf("uploaded model missing from model list: %+v", list.Data)
	}
}

func TestCachePutConcurrentWithReads(t *testing.T) {
	cache, _ := uploadTestCache(t)
	payload := []byte("payload")

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			name := fmt.Sprintf("m-%d", i)
			if _, err := cache.Put(name, bytes.NewReader(payload), sha(payload), 0); err != nil {
				t.Errorf("Put %s: %v", name, err)
			}
			_ = cache.List()
			_, _ = cache.ModelPath(name)
		}(i)
	}
	wg.Wait()
	if got := len(cache.List()); got != 8 {
		t.Errorf("expected 8 cached models, got %d", got)
	}
}
