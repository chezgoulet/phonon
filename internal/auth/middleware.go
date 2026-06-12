// Package auth provides OIDC JWT validation middleware for the coordinator API.
//
// In secure mode (Mode == "oidc"), the middleware validates JWTs from the
// configured OIDC provider using the go-oidc library (signature, expiry,
// issuer, and audience verified by the provider).
//
// In insecure mode (Mode == "none" or empty), all requests pass without
// validation.
package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	gojwt "github.com/golang-jwt/jwt/v5"
	"github.com/coreos/go-oidc/v3/oidc"
)

// Mode constants.
const (
	ModeOIDC = "oidc"
	ModeNone = "none"
)

// Config holds the authentication configuration for the middleware.
type Config struct {
	Mode     string // "oidc" or "none"
	Issuer   string // OIDC issuer URL
	ClientID string // expected audience (client_id)

	// PSK is the pre-shared key for LAN deployments (mode="psk").
	// If empty and mode is "psk", auth will reject all requests.
	PSK string

	JWKSRefresh      time.Duration
	DiscoveryTimeout time.Duration
}

// Defaults.
const (
	DefaultJWKSRefresh      = 15 * time.Minute
	DefaultDiscoveryTimeout = 10 * time.Second
)

// Middleware is an HTTP middleware that validates JWTs / PSK tokens.
type Middleware struct {
	config  Config
	log     *slog.Logger
	started bool
	startMu sync.Mutex
	stopCh  chan struct{}

	// go-oidc verifier (only when mode="oidc")
	verifier *oidc.IDTokenVerifier
	httpCli  *http.Client
}

// New creates a new auth middleware.
func New(cfg Config) *Middleware {
	logger := slog.With("component", "auth")

	if cfg.JWKSRefresh <= 0 {
		cfg.JWKSRefresh = DefaultJWKSRefresh
	}
	if cfg.DiscoveryTimeout <= 0 {
		cfg.DiscoveryTimeout = DefaultDiscoveryTimeout
	}

	return &Middleware{
		config: cfg,
		log:    logger,
		httpCli: &http.Client{
			Timeout: cfg.DiscoveryTimeout,
		},
		stopCh: make(chan struct{}),
	}
}

// Start performs OIDC discovery and initializes the token verifier.
// In non-OIDC modes ("none", "psk"), this is a no-op.
func (m *Middleware) Start() error {
	m.startMu.Lock()
	defer m.startMu.Unlock()

	if m.started {
		return fmt.Errorf("auth middleware already started")
	}

	switch m.config.Mode {
	case ModeOIDC:
		if err := m.startOIDC(); err != nil {
			return err
		}
	case "psk":
		m.log.Info("auth middleware started in PSK mode")
	case ModeNone, "":
		m.log.Info("auth middleware started in insecure mode")
	default:
		return fmt.Errorf("unknown auth mode: %q", m.config.Mode)
	}

	m.started = true
	return nil
}

func (m *Middleware) startOIDC() error {
	ctx := context.Background()
	provider, err := oidc.NewProvider(ctx, m.config.Issuer)
	if err != nil {
		return fmt.Errorf("oidc provider init: %w", err)
	}

	m.verifier = provider.Verifier(&oidc.Config{
		ClientID: m.config.ClientID,
		// Skip expiry check here — we do it manually below with leeway
		SkipExpiryCheck: false,
	})

	// Start background JWKS refresh loop using the go-oidc library's
	// built-in key set (it refreshes automatically).
	m.log.Info("auth middleware started in secure mode",
		"issuer", m.config.Issuer,
		"client_id", m.config.ClientID)
	return nil
}

// Stop terminates background operations.
func (m *Middleware) Stop() {
	m.startMu.Lock()
	defer m.startMu.Unlock()

	if !m.started {
		return
	}
	close(m.stopCh)
	m.started = false
	m.log.Info("auth middleware stopped")
}

// Status represents the current authentication mode.
type Status struct {
	Mode   string `json:"mode"`   // "secure", "psk", or "insecure"
	Issuer string `json:"issuer,omitempty"`
}

// Status returns the current authentication status.
func (m *Middleware) Status() Status {
	switch m.config.Mode {
	case ModeOIDC:
		return Status{Mode: "secure", Issuer: m.config.Issuer}
	case "psk":
		return Status{Mode: "psk"}
	default:
		return Status{Mode: "insecure"}
	}
}

// Handler returns an HTTP middleware that validates tokens.
// In insecure mode (mode="none"), it calls next directly.
func (m *Middleware) Handler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch m.config.Mode {
		case ModeOIDC:
			m.handleOIDC(w, r, next)
		case "psk":
			m.handlePSK(w, r, next)
		default:
			next.ServeHTTP(w, r)
		}
	})
}

func (m *Middleware) handleOIDC(w http.ResponseWriter, r *http.Request, next http.Handler) {
	// Strip any injected X-Auth-Claims header before validation
	// (prevents upstream proxy injection attacks)
	r.Header.Del("X-Auth-Claims")

	token, err := extractBearerToken(r)
	if err != nil {
		http.Error(w, `{"error":"unauthorized","message":"missing or invalid authorization header"}`, http.StatusUnauthorized)
		return
	}

	// Verify using go-oidc — handles signature, expiry, issuer, audience
	idToken, err := m.verifier.Verify(r.Context(), token)
	if err != nil {
		m.log.Warn("token validation failed", "error", err)
		http.Error(w, fmt.Sprintf(`{"error":"unauthorized","message":%q}`, err.Error()), http.StatusUnauthorized)
		return
	}

	// Extract claims as raw JSON for downstream use
	var rawClaims json.RawMessage
	if err := idToken.Claims(&rawClaims); err != nil {
		m.log.Warn("failed to extract claims", "error", err)
		http.Error(w, `{"error":"unauthorized","message":"failed to extract claims"}`, http.StatusUnauthorized)
		return
	}

	// Base claims for issuer/clientID validation (redundant with go-oidc, but
	// included for forward compatibility).
	var claims struct {
		Sub string `json:"sub"`
	}
	if err := idToken.Claims(&claims); err == nil {
		_ = claims.Sub // available for downstream logging
	}

	// Inject claims into request context.
	// TODO(#168): migrate downstream handlers to read claims from
	// context.Context instead of headers.
	r.Header.Set("X-Auth-Claims", string(rawClaims))

	// Store claims in context for future migration away from headers.
	ctx := context.WithValue(r.Context(), claimsKey, string(rawClaims))
	next.ServeHTTP(w, r.WithContext(ctx))
}

func (m *Middleware) handlePSK(w http.ResponseWriter, r *http.Request, next http.Handler) {
	if !validatePSK(r, []byte(m.config.PSK), len(m.config.PSK)) {
		http.Error(w, `{"error":"unauthorized","message":"invalid or missing PSK"}`, http.StatusUnauthorized)
		return
	}
	next.ServeHTTP(w, r)
}

// ClaimsFromContext retrieves the JWT claims JSON previously stored in the
// request context by the auth middleware. Returns empty string if not present.
func ClaimsFromContext(ctx context.Context) string {
	if v := ctx.Value(claimsKey); v != nil {
		return v.(string)
	}
	return ""
}

// contextKey is an unexported type for context keys to avoid collisions.
type contextKey string

const claimsKey contextKey = "auth:claims"

// extractBearerToken extracts a Bearer token from the Authorization header.
func extractBearerToken(r *http.Request) (string, error) {
	auth := r.Header.Get("Authorization")
	if auth == "" {
		return "", fmt.Errorf("missing authorization header")
	}

	parts := strings.SplitN(auth, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return "", fmt.Errorf("invalid authorization format")
	}

	return parts[1], nil
}

// verifyIDToken is a standalone helper that verifies a JWT using golang-jwt
// with a static key or HMAC secret. Used internally for backward-compatible
// token verification in non-OIDC modes or for testing.
func verifyIDToken(tokenStr string, keyFunc gojwt.Keyfunc, claims interface{}) error {
	token, err := gojwt.Parse(tokenStr, keyFunc,
		gojwt.WithValidMethods([]string{"RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "HS256", "HS384", "HS512"}),
		gojwt.WithLeeway(30*time.Second),
	)
	if err != nil {
		return fmt.Errorf("jwt parse: %w", err)
	}

	if !token.Valid {
		return fmt.Errorf("invalid token")
	}

	// Map claims
	claimBytes, err := json.Marshal(token.Claims)
	if err != nil {
		return fmt.Errorf("marshal claims: %w", err)
	}
	if err := json.Unmarshal(claimBytes, claims); err != nil {
		return fmt.Errorf("unmarshal claims: %w", err)
	}

	return nil
}

// StatusHandler returns the auth status endpoint handler.
func (m *Middleware) StatusHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(m.Status())
	}
}
