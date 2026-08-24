package auth

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	gojwt "github.com/golang-jwt/jwt/v5"
)

// ─── Claims parsing ──────────────────────────────────────────────

func TestParseClaims(t *testing.T) {
	raw := json.RawMessage(`{
		"sub":"user-42",
		"iss":"https://auth.example.com",
		"aud":["phonon"],
		"exp":1900000000,
		"email":"user@example.com",
		"roles":["admin","operator"],
		"scope":"openid email profile"
	}`)
	c, err := parseClaims(raw)
	if err != nil {
		t.Fatalf("parseClaims: %v", err)
	}
	if c.Subject != "user-42" {
		t.Errorf("Subject = %q, want user-42", c.Subject)
	}
	if c.Issuer != "https://auth.example.com" {
		t.Errorf("Issuer = %q", c.Issuer)
	}
	if len(c.Audience) != 1 || c.Audience[0] != "phonon" {
		t.Errorf("Audience = %v", c.Audience)
	}
	if c.Expiry != 1900000000 {
		t.Errorf("Expiry = %d", c.Expiry)
	}
	if c.Email != "user@example.com" {
		t.Errorf("Email = %q", c.Email)
	}
	if !c.HasRole("admin") || !c.HasRole("operator") {
		t.Errorf("roles not parsed: %v", c.Roles)
	}
	if !c.HasScope("profile") {
		t.Errorf("scopes not parsed from scope claim: %v", c.Scopes)
	}
	if string(c.Raw()) != string(raw) {
		t.Error("Raw() must retain the exact validated claim JSON")
	}
}

func TestParseClaims_TooLarge(t *testing.T) {
	big := `{"sub":"` + strings.Repeat("a", maxRawClaimsBytes) + `"}`
	if _, err := parseClaims(json.RawMessage(big)); err == nil {
		t.Fatal("expected rejection of oversized claim set")
	}
}

func TestParseClaims_Minimal(t *testing.T) {
	c, err := parseClaims(json.RawMessage(`{"sub":"s1"}`))
	if err != nil {
		t.Fatalf("parseClaims: %v", err)
	}
	if c.Subject != "s1" || c.HasRole("admin") || c.HasScope("openid") {
		t.Errorf("unexpected claims: %+v", c)
	}
	if c.Raw() == nil {
		t.Error("raw must be retained even for minimal tokens")
	}
}

// ─── Accessor semantics (fail-closed) ────────────────────────────

func TestClaimsFrom_FailsClosed(t *testing.T) {
	if _, err := ClaimsFrom(nil); err != ErrNoClaims {
		t.Errorf("nil context: expected ErrNoClaims, got %v", err)
	}
	if _, err := ClaimsFrom(context.Background()); err != ErrNoClaims {
		t.Errorf("empty context: expected ErrNoClaims, got %v", err)
	}
	if s, err := SubjectFrom(context.Background()); err != ErrNoClaims || s != "" {
		t.Errorf("SubjectFrom empty context: got (%q, %v)", s, err)
	}
}

func TestClaimsFromContext_BackCompatString(t *testing.T) {
	ctx := context.WithValue(context.Background(), claimsKey, `{"sub":"legacy"}`)
	if got := ClaimsFromContext(ctx); got != `{"sub":"legacy"}` {
		t.Errorf("legacy string accessor broken: %q", got)
	}
	c, err := ClaimsFrom(ctx)
	if err != nil || c.Subject != "legacy" {
		t.Errorf("ClaimsFrom on legacy context: (%v, %v)", c, err)
	}
	if ClaimsFromContext(nil) != "" {
		t.Error("nil context should yield empty string")
	}
}

func TestNilClaims_HasRoleHasScope(t *testing.T) {
	var c *Claims
	if c.HasRole("admin") || c.HasScope("openid") {
		t.Error("nil Claims must deny every role/scope")
	}
}

// ─── End-to-end: forged headers cannot reach handlers ────────────

// fakeOIDCIssuer stands up a minimal OIDC provider (discovery + JWKS) and
// returns its URL plus an RS256 signer for minting test ID tokens.
func fakeOIDCIssuer(t *testing.T) (issuerURL string, sign func(claims gojwt.MapClaims) string) {
	t.Helper()

	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}
	jwks := map[string]any{
		"keys": []map[string]string{{
			"kty": "RSA",
			"use": "sig",
			"alg": "RS256",
			"kid": "test-key",
			"n":   base64.RawURLEncoding.EncodeToString(key.PublicKey.N.Bytes()),
			"e":   base64.RawURLEncoding.EncodeToString([]byte{1, 0, 1}), // 65537
		}},
	}
	jwksBody, _ := json.Marshal(jwks)

	mux := http.NewServeMux()
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	// The issuer embedded in discovery must equal the external URL exactly.
	cfgBody, _ := json.Marshal(map[string]string{
		"issuer":   srv.URL,
		"jwks_uri": srv.URL + "/keys",
	})
	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(cfgBody)
	})
	mux.HandleFunc("/keys", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jwksBody)
	})

	return srv.URL, func(claims gojwt.MapClaims) string {
		tok := gojwt.NewWithClaims(gojwt.SigningMethodRS256, claims)
		tok.Header["kid"] = "test-key"
		signed, err := tok.SignedString(key)
		if err != nil {
			t.Fatalf("SignedString: %v", err)
		}
		return signed
	}
}

// TestOIDCMiddleware_ClaimsTrustedContextOnly is the regression test for the
// X-Auth-Claims trust problem (#168): a client sends a forged
// X-Auth-Claims header, and the handler must see
//  1. no such header downstream (stripped, never re-injected), and
//  2. parsed claims derived exclusively from the *validated* ID token.
func TestOIDCMiddleware_ClaimsTrustedContextOnly(t *testing.T) {
	issuer, sign := fakeOIDCIssuer(t)

	m := New(Config{
		Mode:     ModeOIDC,
		Issuer:   issuer,
		ClientID: "phonon",
	})
	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer m.Stop()

	token := sign(gojwt.MapClaims{
		"iss":   issuer,
		"aud":   "phonon",
		"sub":   "alice",
		"exp":   time.Now().Add(time.Hour).Unix(),
		"roles": []string{"admin"},
		"scope": "openid inference",
	})

	var (
		downstreamHeader string
		gotClaims        *Claims
		claimsErr        error
		gotSubject       string
		subjectErr       error
	)
	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		downstreamHeader = r.Header.Get("X-Auth-Claims")
		gotClaims, claimsErr = ClaimsFrom(r.Context())
		gotSubject, subjectErr = SubjectFrom(r.Context())
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", http.NoBody)
	req.Header.Set("Authorization", "Bearer "+token)
	// The forgery attempt: attacker-supplied identity header.
	req.Header.Set("X-Auth-Claims", `{"sub":"root","roles":["superadmin"]}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("valid token rejected: %d %s", w.Code, w.Body.String())
	}
	if downstreamHeader != "" {
		t.Fatalf("forged X-Auth-Claims reached the handler: %q", downstreamHeader)
	}
	if claimsErr != nil {
		t.Fatalf("ClaimsFrom failed inside authenticated request: %v", claimsErr)
	}
	if gotClaims.Subject != "alice" {
		t.Errorf("Subject = %q, want alice (from validated token only)", gotClaims.Subject)
	}
	if !gotClaims.HasRole("admin") || gotClaims.HasRole("superadmin") {
		t.Errorf("roles = %v; forged superadmin role must not appear", gotClaims.Roles)
	}
	if !gotClaims.HasScope("inference") {
		t.Errorf("scopes = %v, want [openid inference]", gotClaims.Scopes)
	}
	if subjectErr != nil || gotSubject != "alice" {
		t.Errorf("SubjectFrom = (%q, %v)", gotSubject, subjectErr)
	}
}

// TestOIDCMiddleware_RejectsGarbageClaimShape ensures a token whose claim
// set is not valid JSON-shaped as expected is rejected rather than passed
// through with zero-value identity.
func TestOIDCMiddleware_RejectsUnparseableToken(t *testing.T) {
	issuer, _ := fakeOIDCIssuer(t)
	m := New(Config{Mode: ModeOIDC, Issuer: issuer, ClientID: "phonon"})
	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer m.Stop()

	// Not signed by the provider key → verification fails before claims
	// parsing ever runs; the request must get 401 and the handler must
	// never observe claims.
	handlerRan := false
	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlerRan = true
		if _, err := ClaimsFrom(r.Context()); err == nil {
			t.Error("claims present despite invalid token")
		}
		w.WriteHeader(http.StatusOK)
	}))

	forged := gojwt.NewWithClaims(gojwt.SigningMethodRS256, gojwt.MapClaims{
		"iss": issuer, "aud": "phonon", "sub": "mallory",
		"exp": time.Now().Add(time.Hour).Unix(),
	})
	otherKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	badToken, err := forged.SignedString(otherKey)
	if err != nil {
		t.Fatalf("SignedString: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer "+badToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if handlerRan {
		t.Error("handler ran for an unverified token")
	}
	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", w.Code)
	}
}

// TestPSKMode_NoClaimsInContext pins the PSK-mode contract: there is no OIDC
// identity, so ClaimsFrom must fail closed even on an otherwise-valid request.
func TestPSKMode_NoClaimsInContext(t *testing.T) {
	m := New(Config{Mode: ModePSK, PSK: "key"})
	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, err := ClaimsFrom(r.Context()); err != ErrNoClaims {
			t.Errorf("PSK mode must yield ErrNoClaims, got %v", err)
		}
		if r.Header.Get("X-Auth-Claims") != "" {
			t.Error("X-Auth-Claims must be stripped in PSK mode too")
		}
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer key")
	req.Header.Set("X-Auth-Claims", `{"sub":"forged"}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("valid PSK rejected: %d", w.Code)
	}
}
