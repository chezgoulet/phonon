package api

import (
	"context"
	"encoding/hex"
	"net/http"

	"github.com/google/uuid"
)

// TraceIDHeader is the response header carrying the request's trace ID.
const TraceIDHeader = "X-Phonon-Trace-Id"

type traceCtxKey struct{}

// NewTraceID returns a fresh UUIDv4 as a 32-character lowercase hex string.
func NewTraceID() string {
	u := uuid.New()
	return hex.EncodeToString(u[:])
}

// ContextWithTraceID returns a child context carrying the trace ID.
func ContextWithTraceID(ctx context.Context, traceID string) context.Context {
	return context.WithValue(ctx, traceCtxKey{}, traceID)
}

// TraceIDFromContext returns the request's trace ID, or "" when the request
// did not pass through TraceMiddleware (e.g. in unit tests).
func TraceIDFromContext(ctx context.Context) string {
	id, _ := ctx.Value(traceCtxKey{}).(string)
	return id
}

// TraceMiddleware assigns every request a fresh trace ID, exposes it to
// handlers via the request context, and echoes it to the client in the
// X-Phonon-Trace-Id response header. It sits on the main mux, outside auth,
// so every request — including rejected ones — is traceable.
//
// The ID is always generated server-side (an inbound X-Phonon-Trace-Id is
// ignored) so clients cannot inject arbitrary strings into the event log.
func TraceMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		traceID := NewTraceID()
		w.Header().Set(TraceIDHeader, traceID)
		next.ServeHTTP(w, r.WithContext(ContextWithTraceID(r.Context(), traceID)))
	})
}
