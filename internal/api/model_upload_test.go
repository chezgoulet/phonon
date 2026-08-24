package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
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

func TestModelUploadNameFieldAtLimitAccepted(t *testing.T) {
	cache, _ := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	payload := []byte("bytes")
	longName := strings.Repeat("a", 4096)
	body, ctype := buildUpload(t, longName, "", payload)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	req.Header.Set(ChecksumHeader, sha(payload))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201 for %d-byte name, got %d: %s", len(longName), w.Code, w.Body.String())
	}
	var meta struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(w.Body).Decode(&meta); err != nil {
		t.Fatal(err)
	}
	if meta.Name != longName {
		t.Errorf("response name not intact: got %d bytes, want %d", len(meta.Name), len(longName))
	}
	if !cache.Has(longName) {
		t.Error("cache should register the uploaded model under its full name")
	}
}

func TestModelUploadNameFieldOverLimitRejected(t *testing.T) {
	cache, dir := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	payload := []byte("bytes")
	tooLong := strings.Repeat("a", 4097)
	body, ctype := buildUpload(t, tooLong, "", payload)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	req.Header.Set(ChecksumHeader, sha(payload))
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for %d-byte name, got %d: %s", len(tooLong), w.Code, w.Body.String())
	}
	var resp struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(resp.Error.Message, "name") || !strings.Contains(resp.Error.Message, "4096 byte limit") {
		t.Errorf("error should name the field and the limit: %q", resp.Error.Message)
	}
	entries, err := os.ReadDir(filepath.Join(dir, "models"))
	if err != nil {
		t.Fatal(err)
	}
	// Init creates models/.names for original-name sidecars (model #314);
	// anything else under models means the rejected upload touched disk.
	for _, e := range entries {
		if e.Name() != ".names" {
			t.Errorf("rejected upload must not touch disk, found %q", e.Name())
		}
	}
}

func TestModelUploadChecksumFieldOverLimitRejected(t *testing.T) {
	cache, _ := uploadTestCache(t)
	h := NewModelUploadHandler(cache)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	bigChecksum := strings.Repeat("f", 4097)
	body, ctype := buildUpload(t, "m", bigChecksum, []byte("bytes"))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", body)
	req.Header.Set("Content-Type", ctype)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for oversized checksum field, got %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(resp.Error.Message, "checksum") || !strings.Contains(resp.Error.Message, "4096 byte limit") {
		t.Errorf("error should name the field and the limit: %q", resp.Error.Message)
	}
}

// countingReader tracks how many body bytes the multipart machinery has
// actually pulled through.
type countingReader struct {
	r io.Reader
	n int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n += int64(n)
	return n, err
}

// TestReadSmallFieldCapsDiscardOnOverflow ensures an overflowing field is
// not drained unboundedly while the upload semaphore is held: at most ~1MiB
// of a 5MiB junk field may be consumed after the limit is detected.
func TestReadSmallFieldCapsDiscardOnOverflow(t *testing.T) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, err := mw.CreateFormField("name")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := fw.Write(bytes.Repeat([]byte("a"), 5<<20)); err != nil {
		t.Fatal(err)
	}
	mw.Close()

	cr := &countingReader{r: bytes.NewReader(buf.Bytes())}
	mr := multipart.NewReader(cr, mw.Boundary())
	part, err := mr.NextPart()
	if err != nil {
		t.Fatal(err)
	}

	_, err = readSmallField(part)
	if !errors.Is(err, errFieldTooLarge) {
		t.Fatalf("expected errFieldTooLarge, got %v", err)
	}
	// Budget: 4097-byte read + 1MiB capped discard + header/boundary slack.
	const budget = maxFormFieldLen + 1 + maxFieldDiscardBytes + 8192
	if cr.n > budget {
		t.Errorf("consumed %d bytes of the body; want ≤ %d (uncapped Close would drain %d)",
			cr.n, budget, buf.Len())
	}
}

// TestModelUploadTruncatedFieldNotReportedAsTooLarge ensures raw I/O errors
// (aborted/truncated bodies) surface as malformed multipart bodies rather
// than the misleading "exceeds 4096 byte limit" overflow message.
func TestModelUploadTruncatedFieldNotReportedAsTooLarge(t *testing.T) {
	for _, field := range []string{"name", "checksum"} {
		t.Run(field, func(t *testing.T) {
			cache, _ := uploadTestCache(t)
			h := NewModelUploadHandler(cache)
			mux := http.NewServeMux()
			h.RegisterRoutes(mux)

			var buf bytes.Buffer
			mw := multipart.NewWriter(&buf)
			fw, err := mw.CreateFormField(field)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := fw.Write([]byte(strings.Repeat("n", 512))); err != nil {
				t.Fatal(err)
			}
			mw.Close()

			// Cut the body mid-field so the field read fails without
			// ever seeing the boundary.
			truncated := buf.Bytes()[:buf.Len()/2]
			req := httptest.NewRequest(http.MethodPost, "/api/v1/models/upload", bytes.NewReader(truncated))
			req.Header.Set("Content-Type", mw.FormDataContentType())
			req.Header.Set(ChecksumHeader, sha([]byte("bytes")))
			w := httptest.NewRecorder()
			mux.ServeHTTP(w, req)

			if w.Code != http.StatusBadRequest {
				t.Fatalf("expected 400 for truncated body, got %d: %s", w.Code, w.Body.String())
			}
			var resp struct {
				Error struct {
					Message string `json:"message"`
				} `json:"error"`
			}
			if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
				t.Fatal(err)
			}
			if strings.Contains(resp.Error.Message, "byte limit") {
				t.Errorf("I/O failure misreported as overflow: %q", resp.Error.Message)
			}
			if !strings.Contains(resp.Error.Message, "malformed multipart body") {
				t.Errorf("truncated body should map to malformed-body error: %q", resp.Error.Message)
			}
		})
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
