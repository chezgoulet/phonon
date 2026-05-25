// Package auth provides OIDC JWT validation middleware for the coordinator API.
//
// In secure mode (Mode == "oidc"), the middleware validates JWTs from the
// configured OIDC provider: signature, expiry, issuer, and audience. JWKS
// keys are fetched and cached with periodic refresh.
//
// In insecure mode (Mode == "none" or empty), all requests pass without
// validation.
package auth

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log/slog"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Mode constants.
const (
	ModeOIDC = "oidc"
	ModeNone = "none"
)

// Config holds the authentication configuration for the middleware.
type Config struct {
	Mode             string // "oidc" or "none"
	Issuer           string // OIDC issuer URL
	ClientID         string // expected audience (client_id)
	JWKSRefresh      time.Duration
	DiscoveryTimeout time.Duration
}

// Defaults.
const (
	DefaultJWKSRefresh      = 15 * time.Minute
	DefaultDiscoveryTimeout = 10 * time.Second
)

// Middleware is an HTTP middleware that validates JWTs.
type Middleware struct {
	config     Config
	log        *slog.Logger
	jwks       *JWKSSet
	jwksMu     sync.RWMutex
	jwksURL    string
	httpClient *http.Client
	stopCh     chan struct{}
	started    bool
	startMu    sync.Mutex
}

// JWK represents a single JSON Web Key.
type JWK struct {
	Kty string `json:"kty"` // key type (e.g., "RSA", "EC")
	Kid string `json:"kid"` // key ID
	Alg string `json:"alg"` // algorithm (e.g., "RS256", "ES256")

	// RSA fields
	N string `json:"n,omitempty"` // modulus (base64url)
	E string `json:"e,omitempty"` // exponent (base64url)

	// EC fields
	Crv string `json:"crv,omitempty"` // curve (e.g., "P-256")
	X   string `json:"x,omitempty"`   // x coordinate (base64url)
	Y   string `json:"y,omitempty"`   // y coordinate (base64url)

	// Use
	Use string `json:"use,omitempty"` // "sig" for signature
}

// JWKSSet is a set of JWK keys returned by the JWKS endpoint.
type JWKSSet struct {
	Keys []JWK `json:"keys"`
}

// openIDConfig represents the /.well-known/openid-configuration response.
type openIDConfig struct {
	Issuer  string `json:"issuer"`
	JWKSURI string `json:"jwks_uri"`
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
		httpClient: &http.Client{
			Timeout: cfg.DiscoveryTimeout,
		},
		stopCh: make(chan struct{}),
	}
}

// Start performs OIDC discovery and begins the JWKS refresh loop.
// In insecure mode, this is a no-op.
func (m *Middleware) Start() error {
	m.startMu.Lock()
	defer m.startMu.Unlock()

	if m.started {
		return fmt.Errorf("auth middleware already started")
	}

	if m.config.Mode != ModeOIDC {
		m.log.Info("auth middleware started in insecure mode")
		m.started = true
		return nil
	}

	// Perform OIDC discovery
	if err := m.discover(); err != nil {
		return fmt.Errorf("oidc discovery: %w", err)
	}

	// Initial JWKS fetch
	if err := m.refreshJWKS(); err != nil {
		return fmt.Errorf("initial jwks fetch: %w", err)
	}

	// Background JWKS refresh
	go m.refreshLoop()

	m.log.Info("auth middleware started in secure mode",
		"issuer", m.config.Issuer,
		"client_id", m.config.ClientID)
	m.started = true
	return nil
}

// Stop terminates the JWKS refresh loop.
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

func (m *Middleware) discover() error {
	wellKnown := strings.TrimRight(m.config.Issuer, "/") + "/.well-known/openid-configuration"

	req, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, wellKnown, http.NoBody)
	resp, err := m.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("fetch discovery document: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("discovery returned HTTP %d", resp.StatusCode)
	}

	var cfg openIDConfig
	if err := json.NewDecoder(resp.Body).Decode(&cfg); err != nil {
		return fmt.Errorf("decode discovery document: %w", err)
	}

	if cfg.JWKSURI == "" {
		return fmt.Errorf("discovery document missing jwks_uri")
	}

	m.jwksURL = cfg.JWKSURI
	m.log.Info("oidc discovery complete", "jwks_uri", cfg.JWKSURI)
	return nil
}

func (m *Middleware) refreshLoop() {
	ticker := time.NewTicker(m.config.JWKSRefresh)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			if err := m.refreshJWKS(); err != nil {
				m.log.Warn("jwks refresh failed", "error", err)
			}
		case <-m.stopCh:
			return
		}
	}
}

func (m *Middleware) refreshJWKS() error {
	if m.jwksURL == "" {
		return fmt.Errorf("no jwks_uri configured")
	}

	req, err := http.NewRequestWithContext(context.Background(), http.MethodGet, m.jwksURL, nil)
	if err != nil {
		return fmt.Errorf("create jwks request: %w", err)
	}
	resp, err := m.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("fetch jwks: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("jwks returned HTTP %d", resp.StatusCode)
	}

	var set JWKSSet
	if err := json.NewDecoder(resp.Body).Decode(&set); err != nil {
		return fmt.Errorf("decode jwks: %w", err)
	}

	m.jwksMu.Lock()
	m.jwks = &set
	m.jwksMu.Unlock()

	m.log.Info("jwks refreshed", "keys", len(set.Keys))
	return nil
}

// Status returns the current auth mode.
type Status struct {
	Mode   string `json:"mode"`   // "secure" or "insecure"
	Issuer string `json:"issuer,omitempty"`
}

// Status returns the current authentication status.
func (m *Middleware) Status() Status {
	if m.config.Mode == ModeOIDC {
		return Status{Mode: "secure", Issuer: m.config.Issuer}
	}
	return Status{Mode: "insecure"}
}

// Handler returns an HTTP middleware that validates JWTs.
// In insecure mode, it calls next directly.
func (m *Middleware) Handler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if m.config.Mode != ModeOIDC {
			next.ServeHTTP(w, r)
			return
		}

		token, err := extractBearerToken(r)
		if err != nil {
			http.Error(w, `{"error":"unauthorized","message":"missing or invalid authorization header"}`, http.StatusUnauthorized)
			return
		}

		claims, err := m.validateToken(token)
		if err != nil {
			m.log.Warn("token validation failed", "error", err)
			http.Error(w, fmt.Sprintf(`{"error":"unauthorized","message":%q}`, err.Error()), http.StatusUnauthorized)
			return
		}

		// Inject claims into request context for downstream handlers
		r.Header.Set("X-Auth-Claims", claims)

		next.ServeHTTP(w, r)
	})
}

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

// validateToken validates a JWT and returns the claims payload as a JSON string.
func (m *Middleware) validateToken(token string) (string, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return "", fmt.Errorf("invalid jwt format")
	}

	// Decode header
	headerJSON, err := base64URLDecode(parts[0])
	if err != nil {
		return "", fmt.Errorf("decode header: %w", err)
	}

	var header struct {
		Alg string `json:"alg"`
		Kid string `json:"kid,omitempty"`
		Typ string `json:"typ,omitempty"`
	}
	if err := json.Unmarshal(headerJSON, &header); err != nil {
		return "", fmt.Errorf("parse header: %w", err)
	}

	// Decode payload
	payloadJSON, err := base64URLDecode(parts[1])
	if err != nil {
		return "", fmt.Errorf("decode payload: %w", err)
	}

	var claims struct {
		Iss string   `json:"iss"`
		Sub string   `json:"sub"`
		Aud []string `json:"aud"`
		Exp int64    `json:"exp"`
		Iat int64    `json:"iat"`
	}
	if err := json.Unmarshal(payloadJSON, &claims); err != nil {
		// Try with single string audience
		var claimsSingle struct {
			Iss string `json:"iss"`
			Sub string `json:"sub"`
			Aud string `json:"aud"`
			Exp int64  `json:"exp"`
			Iat int64  `json:"iat"`
		}
		if err2 := json.Unmarshal(payloadJSON, &claimsSingle); err2 != nil {
			return "", fmt.Errorf("parse payload: %w", err)
		}
		claims.Iss = claimsSingle.Iss
		claims.Sub = claimsSingle.Sub
		claims.Exp = claimsSingle.Exp
		claims.Iat = claimsSingle.Iat
		if claimsSingle.Aud != "" {
			claims.Aud = []string{claimsSingle.Aud}
		}
	}

	// Verify expiry
	if claims.Exp > 0 {
		if time.Now().Unix() > claims.Exp {
			return "", fmt.Errorf("token expired")
		}
	}

	// Verify issuer (if configured)
	if m.config.Issuer != "" && claims.Iss != "" && claims.Iss != m.config.Issuer {
		return "", fmt.Errorf("invalid issuer: %s", claims.Iss)
	}

	// Verify audience (client_id)
	if m.config.ClientID != "" {
		validAud := false
		for _, aud := range claims.Aud {
			if aud == m.config.ClientID {
				validAud = true
				break
			}
		}
		if !validAud {
			return "", fmt.Errorf("invalid audience")
		}
	}

	// Verify signature
	if err := m.verifySignature(parts, &header); err != nil {
		return "", fmt.Errorf("signature verification: %w", err)
	}

	return string(payloadJSON), nil
}

// verifySignature verifies the JWT signature using a matching JWK key.
func (m *Middleware) verifySignature(parts []string, header *struct {
	Alg string `json:"alg"`
	Kid string `json:"kid,omitempty"`
	Typ string `json:"typ,omitempty"`
}) error {
	// Find the matching key
	m.jwksMu.RLock()
	jwks := m.jwks
	m.jwksMu.RUnlock()

	if jwks == nil || len(jwks.Keys) == 0 {
		return fmt.Errorf("no jwks keys available")
	}

	// Find key by kid
	var key *JWK
	for i := range jwks.Keys {
		if jwks.Keys[i].Kid == header.Kid {
			key = &jwks.Keys[i]
			break
		}
	}
	if key == nil && header.Kid != "" {
		// Specified kid not found — maybe key rotation, try refreshing
		return fmt.Errorf("key with kid %q not found", header.Kid)
	}
	if key == nil {
		// No kid in token, use first key that matches algorithm
		for i := range jwks.Keys {
			if jwks.Keys[i].Alg == header.Alg || jwks.Keys[i].Alg == "" {
				key = &jwks.Keys[i]
				break
			}
		}
	}
	if key == nil {
		return fmt.Errorf("no matching key found")
	}

	// Build the signed content: header.payload
	signedContent := parts[0] + "." + parts[1]

	// Decode signature
	sigBytes, err := base64URLDecode(parts[2])
	if err != nil {
		return fmt.Errorf("decode signature: %w", err)
	}

	switch header.Alg {
	case "RS256", "RS384", "RS512":
		return m.verifyRSA(key, signedContent, sigBytes, header.Alg)
	case "ES256", "ES384", "ES512":
		return m.verifyECDSA(key, signedContent, sigBytes, header.Alg)
	default:
		return fmt.Errorf("unsupported algorithm: %s", header.Alg)
	}
}

func (m *Middleware) verifyRSA(key *JWK, signedContent string, sigBytes []byte, alg string) error {
	if key.N == "" || key.E == "" {
		return fmt.Errorf("rsa key missing modulus or exponent")
	}

	nBytes, err := base64URLDecode(key.N)
	if err != nil {
		return fmt.Errorf("decode modulus: %w", err)
	}
	eBytes, err := base64URLDecode(key.E)
	if err != nil {
		return fmt.Errorf("decode exponent: %w", err)
	}

	n := new(big.Int).SetBytes(nBytes)
	e := decodeExponent(eBytes)

	pub := &rsa.PublicKey{N: n, E: e}

	// Choose hash function
	hash := hashForAlg(alg)
	h := hash.New()
	h.Write([]byte(signedContent))
	digest := h.Sum(nil)

	return rsa.VerifyPKCS1v15(pub, hash, digest, sigBytes)
}

func (m *Middleware) verifyECDSA(key *JWK, signedContent string, sigBytes []byte, alg string) error {
	if key.X == "" || key.Y == "" {
		return fmt.Errorf("ec key missing x or y")
	}

	xBytes, err := base64URLDecode(key.X)
	if err != nil {
		return fmt.Errorf("decode x: %w", err)
	}
	yBytes, err := base64URLDecode(key.Y)
	if err != nil {
		return fmt.Errorf("decode y: %w", err)
	}

	x := new(big.Int).SetBytes(xBytes)
	y := new(big.Int).SetBytes(yBytes)

	pub := &ecdsa.PublicKey{
		Curve: curveForAlg(alg),
		X:     x,
		Y:     y,
	}

	// Hash the content
	hash := hashForAlg(alg)
	h := hash.New()
	h.Write([]byte(signedContent))
	digest := h.Sum(nil)

	// ECDSA signatures are ASN.1 DER encoded (standard JWT libraries use raw R||S,
	// but OIDC providers typically use DER-encoded signatures in JWTs)
	// Try DER first, then raw
	if ecdsa.VerifyASN1(pub, digest, sigBytes) {
		return nil
	}

	// Try raw R||S format
	if len(sigBytes)%2 == 0 {
		r := new(big.Int).SetBytes(sigBytes[:len(sigBytes)/2])
		s := new(big.Int).SetBytes(sigBytes[len(sigBytes)/2:])
		if ecdsa.Verify(pub, digest, r, s) {
			return nil
		}
	}

	return fmt.Errorf("ecdsa signature verification failed")
}

// base64URLDecode decodes a base64url-encoded string (with padding handling).
func base64URLDecode(s string) ([]byte, error) {
	// Add padding if needed
	switch len(s) % 4 {
	case 2:
		s += "=="
	case 3:
		s += "="
	}
	return base64.URLEncoding.DecodeString(s)
}

func decodeExponent(b []byte) int {
	if len(b) == 0 {
		return 0
	}
	if len(b) < 8 {
		var e int
		for _, v := range b {
			e = e<<8 | int(v)
		}
		return e
	}
	return int(binary.BigEndian.Uint64(b[len(b)-8:]))
}

func hashForAlg(alg string) crypto.Hash {
	switch alg {
	case "RS256", "ES256":
		return crypto.SHA256
	case "RS384", "ES384":
		return crypto.SHA384
	case "RS512", "ES512":
		return crypto.SHA512
	default:
		return crypto.SHA256
	}
}

func curveForAlg(alg string) elliptic.Curve {
	switch alg {
	case "ES256":
		return elliptic.P256()
	case "ES384":
		return elliptic.P384()
	case "ES512":
		return elliptic.P521()
	default:
		return elliptic.P256()
	}
}

// StatusHandler returns the auth status endpoint handler.
func (m *Middleware) StatusHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(m.Status())
	}
}


