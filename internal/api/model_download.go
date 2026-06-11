package api

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strings"

	"github.com/chezgoulet/phonon/internal/model"
)

// ModelDownloadHandler serves cached model files for sidecar download.
//
// The sidecar receives a model_push command with the coordinator URL, then
// fetches GET /v1/models/{model}/download to retrieve the GGUF file.
// Supports Range requests for download resume.
type ModelDownloadHandler struct {
	cache     *model.Cache
	cacheRoot string // path to the cache root (e.g. "./cache")
	log       *slog.Logger
}

// NewModelDownloadHandler creates a handler that serves files from the model cache.
func NewModelDownloadHandler(cache *model.Cache, cacheRoot string) *ModelDownloadHandler {
	return &ModelDownloadHandler{
		cache:     cache,
		cacheRoot: cacheRoot,
		log:       slog.With("component", "model-download"),
	}
}

// RegisterRoutes adds the model download endpoint to the given mux.
func (h *ModelDownloadHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/models/{model}/download", h.handleDownload)
}

func (h *ModelDownloadHandler) handleDownload(w http.ResponseWriter, r *http.Request) {
	modelName := r.PathValue("model")
	if modelName == "" {
		http.Error(w, `{"error":"model name required"}`, http.StatusBadRequest)
		return
	}

	// Resolve local path from cache
	path, err := h.cache.ModelPath(modelName)
	if err != nil {
		h.log.Warn("model not cached", "model", modelName)
		http.Error(w, fmt.Sprintf(`{"error":"model not cached: %s"}`, modelName), http.StatusNotFound)
		return
	}

	f, err := os.Open(path)
	if err != nil {
		h.log.Error("failed to open model file", "model", modelName, "error", err)
		http.Error(w, `{"error":"failed to open model file"}`, http.StatusInternalServerError)
		return
	}
	defer f.Close()

	fi, err := f.Stat()
	if err != nil {
		http.Error(w, `{"error":"failed to stat model file"}`, http.StatusInternalServerError)
		return
	}

	// Compute SHA-256 hash for the X-Checksum-Sha256 header
	hasher := sha256.New()
	if _, err := io.Copy(hasher, f); err != nil {
		http.Error(w, `{"error":"failed to hash model file"}`, http.StatusInternalServerError)
		return
	}
	checksum := hex.EncodeToString(hasher.Sum(nil))

	// Seek back to start for ServeContent
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		http.Error(w, `{"error":"failed to seek model file"}`, http.StatusInternalServerError)
		return
	}

	// Set response headers
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename=%q`, safeModelName(modelName)))
	w.Header().Set("Content-Length", fmt.Sprintf("%d", fi.Size()))
	w.Header().Set("X-Checksum-Sha256", checksum)
	w.Header().Set("Accept-Ranges", "bytes")

	// Serve the file with Range support
	http.ServeContent(w, r, safeModelName(modelName), fi.ModTime(), f)
}

// safeModelName prevents path traversal in model name by replacing
// any path separators with underscores.
func safeModelName(name string) string {
	return strings.NewReplacer("/", "_", "\\", "_", "..", "_").Replace(name)
}
