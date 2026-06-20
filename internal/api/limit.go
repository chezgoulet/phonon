package api

import (
	"fmt"
	"net/http"
)

const (
	// DefaultBodyLimit is the default maximum request body size (1 MB).
	DefaultBodyLimit int64 = 1 << 20

	// SidecarBodyLimit is the maximum request body for sidecar endpoints.
	// Model push payloads (metadata + model binary URL) are well under 10 KB,
	// but this allows for future fields like inline config blobs.
	SidecarBodyLimit int64 = 10 << 20
)

// BodyLimit returns an HTTP middleware that caps the request body size.
// If a request's Content-Length exceeds the limit, or the body exceeds it
// during read, a 413 (Request Entity Too Large) response is returned.
//
// This wraps http.MaxBytesReader at the middleware layer so oversized
// requests are rejected before reaching the handler. Individual handlers
// may still set their own stricter limits via http.MaxBytesReader.
func BodyLimit(limit int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Reject before the body is read if Content-Length exceeds limit.
			if r.ContentLength > limit {
				http.Error(w, fmt.Sprintf("request body too large: %d bytes (max %d)",
					r.ContentLength, limit), http.StatusRequestEntityTooLarge)
				return
			}

			// Wrap the body with a MaxBytesReader so the handler can't read past the limit.
			r.Body = http.MaxBytesReader(w, r.Body, limit)

			next.ServeHTTP(w, r)
		})
	}
}
