package api

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"strings"
	"time"

	"github.com/chezgoulet/phonon/internal/model"
)

// DefaultModelUploadMaxBytes caps uploads at 8 GiB unless configured.
const DefaultModelUploadMaxBytes int64 = 8 << 30

// ChecksumHeader carries the client's expected SHA-256 for an upload.
// Matches the header the download endpoint emits.
const ChecksumHeader = "X-Checksum-Sha256"

// ModelUploadHandler accepts GGUF model uploads over multipart/form-data
// and stores them in the same cache directory the model reconciler and
// download endpoint use.
//
// Request format — POST /api/v1/models/upload, multipart/form-data:
//
//	name     (form field, optional) model name; defaults to the file's name
//	checksum (form field) expected SHA-256 hex — or the X-Checksum-Sha256
//	         header; one of the two is required
//	file     (file field) the model binary
//
// Responses: 201 with model metadata; 400 for malformed requests or a
// checksum mismatch; 409 when another upload is in flight; 413 when the
// file exceeds the configured limit.
type ModelUploadHandler struct {
	cache    *model.Cache
	maxBytes int64
	log      *slog.Logger

	// registerModel is invoked after a successful upload so the model
	// appears in the OpenAI model list. Nil = skip.
	registerModel func(name string)

	// sem serializes uploads: exactly one may run at a time so the
	// reconciler and concurrent uploads never fight over disk bandwidth
	// or tmp files.
	sem chan struct{}
}

// ModelUploadOption configures a ModelUploadHandler.
type ModelUploadOption func(*ModelUploadHandler)

// WithUploadMaxBytes overrides the maximum accepted file size (default 8 GiB).
func WithUploadMaxBytes(n int64) ModelUploadOption {
	return func(h *ModelUploadHandler) {
		if n > 0 {
			h.maxBytes = n
		}
	}
}

// WithModelRegistration wires a callback run after each successful upload
// (e.g. OpenAIHandler.AddModel) so the model shows up in the model list.
func WithModelRegistration(fn func(name string)) ModelUploadOption {
	return func(h *ModelUploadHandler) {
		h.registerModel = fn
	}
}

// NewModelUploadHandler creates the upload handler.
func NewModelUploadHandler(cache *model.Cache, opts ...ModelUploadOption) *ModelUploadHandler {
	h := &ModelUploadHandler{
		cache:    cache,
		maxBytes: DefaultModelUploadMaxBytes,
		log:      slog.With("component", "model-upload"),
		sem:      make(chan struct{}, 1),
	}
	for _, opt := range opts {
		opt(h)
	}
	return h
}

// RegisterRoutes adds the upload endpoint to the given mux. NOTE: in main
// this mux is mounted outside the default 1 MB body limit — model files
// are gigabytes.
func (h *ModelUploadHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/models/upload", h.handleUpload)
	mux.HandleFunc("POST /v1/models/upload", h.handleUpload)
}

func (h *ModelUploadHandler) handleUpload(w http.ResponseWriter, r *http.Request) {
	// Single-upload throttle: try to claim the semaphore without blocking.
	select {
	case h.sem <- struct{}{}:
		defer func() { <-h.sem }()
	default:
		w.Header().Set("Retry-After", "30")
		writeJSON(w, http.StatusConflict, map[string]any{
			"error": map[string]string{
				"message": "another model upload is in progress, retry later",
				"type":    "conflict_error",
			},
		})
		return
	}

	mr, err := r.MultipartReader()
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": map[string]string{
				"message": "expected multipart/form-data: " + err.Error(),
				"type":    "invalid_request_error",
			},
		})
		return
	}

	// The checksum may arrive as a header or as a form field preceding
	// the file part.
	name := ""
	checksum := strings.TrimSpace(r.Header.Get(ChecksumHeader))

	var entry *model.CacheEntry
	for {
		part, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{
				"error": map[string]string{
					"message": "malformed multipart body: " + err.Error(),
					"type":    "invalid_request_error",
				},
			})
			return
		}

		switch {
		case part.FileName() == "" && part.FormName() == "name":
			val, ferr := readSmallField(part)
			if ferr != nil {
				writeSmallFieldError(w, "name", ferr)
				return
			}
			name = val
		case part.FileName() == "" && part.FormName() == "checksum":
			val, ferr := readSmallField(part)
			if ferr != nil {
				writeSmallFieldError(w, "checksum", ferr)
				return
			}
			checksum = strings.TrimSpace(val)
		case part.FileName() != "":
			// Fields must precede the file part (standard for form
			// encoders); validate what we have, then stream the binary
			// straight into the cache without buffering.
			if name == "" {
				name = part.FileName()
			}
			if checksum == "" {
				part.Close()
				writeJSON(w, http.StatusBadRequest, map[string]any{
					"error": map[string]string{
						"message": fmt.Sprintf("expected SHA-256 checksum required (form field %q before the file, or %s header)", "checksum", ChecksumHeader),
						"type":    "invalid_request_error",
					},
				})
				return
			}
			entry, err = h.cache.Put(name, part, checksum, h.maxBytes)
			part.Close()
			if err != nil {
				h.writePutError(w, name, err)
				return
			}
		default:
			// Unknown form field — ignore.
			_, _ = io.Copy(io.Discard, io.LimitReader(part, 1<<20))
			part.Close()
		}
	}

	if entry == nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": map[string]string{
				"message": "no file part in upload",
				"type":    "invalid_request_error",
			},
		})
		return
	}

	if h.registerModel != nil {
		h.registerModel(entry.Name)
	}
	h.log.Info("model upload complete", "model", entry.Name, "size", entry.SizeBytes)

	writeJSON(w, http.StatusCreated, map[string]any{
		"name":       entry.Name,
		"size_bytes": entry.SizeBytes,
		"sha256":     entry.SHA256,
		"cached_at":  entry.CachedAt.UTC().Format(time.RFC3339),
	})
}

// writePutError maps Cache.Put failures to HTTP statuses.
func (h *ModelUploadHandler) writePutError(w http.ResponseWriter, name string, err error) {
	h.log.Warn("model upload failed", "model", name, "error", err)
	switch {
	case errors.Is(err, model.ErrTooLarge):
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]any{
			"error": map[string]string{
				"message": fmt.Sprintf("model file exceeds the %d byte limit", h.maxBytes),
				"type":    "invalid_request_error",
			},
		})
	case errors.Is(err, model.ErrChecksumMismatch):
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": map[string]string{
				"message": err.Error(),
				"type":    "invalid_request_error",
			},
		})
	default:
		writeJSON(w, http.StatusInternalServerError, map[string]any{
			"error": map[string]string{
				"message": "failed to store model: " + err.Error(),
				"type":    "server_error",
			},
		})
	}
}

// maxFormFieldLen caps small form field values (name, checksum) at 4 KiB.
const maxFormFieldLen = 4096

// maxFieldDiscardBytes caps how much of a rejected overflowing field is
// drained before giving up — the same cap applied to unknown fields — since
// uploads are serialized by a semaphore that a multi-gigabyte junk field
// must not pin down while being drained.
const maxFieldDiscardBytes = 1 << 20

// errFieldTooLarge marks a genuine overflow: the field value itself exceeded
// maxFormFieldLen. Any other error from readSmallField is a transport or
// framing failure (malformed body, aborted request) and must not be
// reported as "exceeds limit".
var errFieldTooLarge = errors.New("form field exceeds limit")

// readSmallField reads a form field value of at most maxFormFieldLen bytes.
// Values over the limit yield errFieldTooLarge; other errors are raw I/O or
// multipart-framing failures. On overflow only maxFieldDiscardBytes of the
// remainder are discarded and the part is deliberately left unclosed:
// Part.Close would otherwise drain the unbounded remainder while the caller
// holds the single-upload semaphore. The request is rejected immediately
// afterwards, so the connection is simply abandoned.
func readSmallField(p *multipart.Part) (string, error) {
	b, err := io.ReadAll(io.LimitReader(p, maxFormFieldLen+1))
	if err != nil {
		p.Close()
		return "", err
	}
	if len(b) > maxFormFieldLen {
		_, _ = io.Copy(io.Discard, io.LimitReader(p, maxFieldDiscardBytes))
		return "", errFieldTooLarge
	}
	p.Close()
	return string(b), nil
}

// writeSmallFieldError maps readSmallField failures: genuine overflows get
// the dedicated too-large message, everything else falls into the existing
// malformed-body 400 path alongside NextPart errors.
func writeSmallFieldError(w http.ResponseWriter, field string, err error) {
	if errors.Is(err, errFieldTooLarge) {
		writeFieldTooLarge(w, field)
		return
	}
	writeJSON(w, http.StatusBadRequest, map[string]any{
		"error": map[string]string{
			"message": "malformed multipart body: " + err.Error(),
			"type":    "invalid_request_error",
		},
	})
}

// writeFieldTooLarge rejects requests whose form fields exceed the limit.
func writeFieldTooLarge(w http.ResponseWriter, field string) {
	writeJSON(w, http.StatusBadRequest, map[string]any{
		"error": map[string]string{
			"message": fmt.Sprintf("form field %s exceeds %d byte limit", field, maxFormFieldLen),
			"type":    "invalid_request_error",
		},
	})
}
