package model

import (
	"fmt"
	"log/slog"
	"net/http"
	"strings"
)

// modelPrefix is the URL path prefix for model downloads.
const modelPrefix = "/api/v1/models/"

// DistributeHandler returns an HTTP handler that serves cached model files
// with Range request support.
func DistributeHandler(cache *Cache) http.Handler {
	log := slog.With("component", "model-distribute")

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Extract model name from path: /api/v1/models/{name}
		if !strings.HasPrefix(r.URL.Path, modelPrefix) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		modelName := strings.TrimPrefix(r.URL.Path, modelPrefix)
		if modelName == "" {
			http.Error(w, "model name required", http.StatusBadRequest)
			return
		}

		// Only GET and HEAD are supported
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		path, err := cache.ModelPath(modelName)
		if err != nil {
			log.Warn("model not in cache", "model", modelName, "remote", r.RemoteAddr)
			http.Error(w, fmt.Sprintf("model %q not cached", modelName), http.StatusNotFound)
			return
		}

		log.Debug("serving model", "model", modelName, "remote", r.RemoteAddr, "path", path)
		http.ServeFile(w, r, path)
	})
}

// ModelURL builds the coordinator URL for a model download.
// baseURL is the coordinator's external URL, e.g. "http://10.0.0.1:9876".
func URL(baseURL, modelName string) string {
	return strings.TrimRight(baseURL, "/") + modelPrefix + modelName
}
