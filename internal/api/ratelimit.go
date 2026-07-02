package api

import (
	"log/slog"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"

	"golang.org/x/time/rate"
)

// RateLimiter applies a global token-bucket limit to the coordinator's
// protected API routes, with a two-tier priority scheme:
//
//   - Normal-priority requests (inference: /api/v1/chat/completions,
//     /v1/chat/completions, the OpenAI model list) draw only from the
//     primary bucket and are shed first when it empties.
//   - High-priority admin requests (pairing, preflight, model management
//     GETs, cluster endpoints) draw from the primary bucket too, but when
//     it is exhausted they fall back to a dedicated reserve bucket. Admin
//     operations are therefore only rejected when the global limit is
//     genuinely exhausted, never merely because inference traffic
//     saturated the primary bucket.
//
// Sidecar routes (/api/v1/sidecar/) are mounted separately in main and
// never pass through this middleware.
type RateLimiter struct {
	primary *rate.Limiter
	reserve *rate.Limiter
	rps     float64
	log     *slog.Logger
}

// NewRateLimiter creates a limiter refilling tokensPerSecond with the given
// burst. The admin reserve refills at the same rate with a burst of
// max(burst/4, 1).
func NewRateLimiter(tokensPerSecond float64, burst int) *RateLimiter {
	if tokensPerSecond <= 0 {
		tokensPerSecond = 10
	}
	if burst <= 0 {
		burst = 20
	}
	reserveBurst := burst / 4
	if reserveBurst < 1 {
		reserveBurst = 1
	}
	return &RateLimiter{
		primary: rate.NewLimiter(rate.Limit(tokensPerSecond), burst),
		reserve: rate.NewLimiter(rate.Limit(tokensPerSecond), reserveBurst),
		rps:     tokensPerSecond,
		log:     slog.With("component", "ratelimit"),
	}
}

// Middleware wraps next with the rate limit. Place it outside the auth
// middleware so throttled requests are rejected before token validation.
func (rl *RateLimiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if rl.primary.Allow() {
			next.ServeHTTP(w, r)
			return
		}

		// Primary bucket exhausted: admin traffic may use the reserve.
		if isHighPriority(r) && rl.reserve.Allow() {
			next.ServeHTTP(w, r)
			return
		}

		retryAfter := int(math.Ceil(1.0 / rl.rps))
		if retryAfter < 1 {
			retryAfter = 1
		}
		w.Header().Set("Retry-After", strconv.Itoa(retryAfter))

		rl.log.Warn("request rate-limited",
			"client_ip", clientIP(r),
			"method", r.Method,
			"path", r.URL.Path,
			"priority", priorityLabel(r),
		)

		writeJSON(w, http.StatusTooManyRequests, map[string]any{
			"error": map[string]string{
				"message": "rate limit exceeded, retry later",
				"type":    "rate_limit_error",
			},
		})
	})
}

// isHighPriority classifies admin operations that must survive load
// shedding: pairing, preflight, model management (GET), and cluster
// endpoints. Inference and the OpenAI model list are normal priority.
func isHighPriority(r *http.Request) bool {
	p := r.URL.Path
	switch {
	case strings.HasPrefix(p, "/api/v1/pair/"),
		strings.HasPrefix(p, "/api/v1/preflight/"),
		strings.HasPrefix(p, "/api/v1/cluster/"):
		return true
	}
	// Model management reads (e.g. GET /api/v1/models/{model}/download,
	// and the /v1/-prefixed alias). The bare model list ("/api/v1/models",
	// "/v1/models") is OpenAI client traffic — normal priority.
	if r.Method == http.MethodGet &&
		(strings.HasPrefix(p, "/api/v1/models/") || strings.HasPrefix(p, "/v1/models/")) {
		return true
	}
	return false
}

func priorityLabel(r *http.Request) string {
	if isHighPriority(r) {
		return "high"
	}
	return "normal"
}

// clientIP extracts the requester's IP for rate-limit logging.
func clientIP(r *http.Request) string {
	// Behind a trusted reverse proxy the first X-Forwarded-For entry is
	// the original client. (Untrusted clients can spoof this header, but
	// it is used for logging only — never for authorization.)
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i > 0 {
			return strings.TrimSpace(xff[:i])
		}
		return strings.TrimSpace(xff)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
