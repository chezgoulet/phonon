package auth

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	gojwt "github.com/golang-jwt/jwt/v5"
)

// startTestOIDC spins up an httptest OIDC provider (discovery + JWKS) backed
// by a fresh RSA key, and returns a started Middleware verifying against it.
func startTestOIDC(t *testing.T) (*Middleware, *rsa.PrivateKey, string) {
	t.Helper()

	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}
	pub := &key.PublicKey
	jwks, err := json.Marshal(map[string]any{
		"keys": []map[string]any{{
			"kty": "RSA",
			"alg": "RS256",
			"use": "sig",
			"kid": "test-key",
			"n":   base64.RawURLEncoding.EncodeToString(pub.N.Bytes()),
			"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(pub.E)).Bytes()),
		}},
	})
	if err != nil {
		t.Fatalf("marshal jwks: %v", err)
	}

	var issuer string
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"issuer":   issuer,
			"jwks_uri": issuer + "/jwks",
		})
	})
	mux.HandleFunc("/jwks", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jwks)
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	issuer = srv.URL

	m := New(Config{
		Mode:     ModeOIDC,
		Issuer:   srv.URL,
		ClientID: "test-audience",
	})
	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(m.Stop)
	return m, key, issuer
}

func signIDToken(t *testing.T, key *rsa.PrivateKey, issuer string, extra gojwt.MapClaims) string {
	t.Helper()
	claims := gojwt.MapClaims{
		"iss": issuer,
		"aud": "test-audience",
		"exp": time.Now().Add(5 * time.Minute).Unix(),
		"iat": time.Now().Unix(),
	}
	for k, v := range extra {
		claims[k] = v
	}
	tok := gojwt.NewWithClaims(gojwt.SigningMethodRS256, claims)
	tok.Header["kid"] = "test-key"
	signed, err := tok.SignedString(key)
	if err != nil {
		t.Fatalf("SignedString: %v", err)
	}
	return signed
}

func doOIDCRequest(t *testing.T, m *Middleware, token string, downstream func(http.ResponseWriter, *http.Request)) (*httptest.ResponseRecorder, *bool) {
	t.Helper()
	reachedDownstream := false
	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reachedDownstream = true
		downstream(w, r)
	}))
	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-proxy"}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	return w, &reachedDownstream
}

// TestHandleOIDC_RejectsMissingSub verifies #302 finding 1: a validly-signed
// token lacking sub is rejected with 401 BEFORE any claims reach downstream
// headers or context.
func TestHandleOIDC_RejectsMissingSub(t *testing.T) {
	m, key, issuer := startTestOIDC(t)

	token := signIDToken(t, key, issuer, nil)
	w, reached := doOIDCRequest(t, m, token, func(_ http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Auth-Claims") != "" {
			t.Error("X-Auth-Claims injected despite missing sub")
		}
	})

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for token without sub, got %d", w.Code)
	}
	if *reached {
		t.Error("downstream handler invoked despite missing sub")
	}
	if got := w.Body.String(); got != `{"error":"unauthorized","message":"token has no subject"}`+"\n" {
		t.Errorf("unexpected body: %q", got)
	}
}

// TestHandleOIDC_RejectsEmptySub covers the explicit `"sub": ""` variant.
func TestHandleOIDC_RejectsEmptySub(t *testing.T) {
	m, key, issuer := startTestOIDC(t)

	token := signIDToken(t, key, issuer, gojwt.MapClaims{"sub": ""})
	w, reached := doOIDCRequest(t, m, token, func(http.ResponseWriter, *http.Request) {})

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for empty sub, got %d", w.Code)
	}
	if *reached {
		t.Error("downstream handler invoked despite empty sub")
	}
}

// TestHandleOIDC_StripsInboundClaimsOn401 verifies #312: the belt-and-
// suspenders local strip in handleOIDC removes any upstream-injected
// X-Auth-Claims before writing a 401, on BOTH rejection paths (missing
// token and empty sub), so the defense holds even without the outermost
// TraceMiddleware strip.
func TestHandleOIDC_StripsInboundClaimsOn401(t *testing.T) {
	m, key, issuer := startTestOIDC(t)

	reached := false
	handler := m.Handler(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		reached = true
	}))

	// Path 1: missing Authorization header entirely.
	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-proxy"}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized || reached {
		t.Fatalf("missing token: expected 401 without reaching downstream, got %d reached=%v", w.Code, reached)
	}
	if got := req.Header.Get("X-Auth-Claims"); got != "" {
		t.Errorf("missing-token 401 left X-Auth-Claims=%q", got)
	}

	// Path 2: validly-signed token with an empty sub.
	token := signIDToken(t, key, issuer, gojwt.MapClaims{"sub": ""})
	req2 := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req2.Header.Set("Authorization", "Bearer "+token)
	req2.Header.Set("X-Auth-Claims", `{"sub":"injected-by-proxy"}`)
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w2, req2)
	if w2.Code != http.StatusUnauthorized || reached {
		t.Fatalf("empty sub: expected 401 without reaching downstream, got %d reached=%v", w2.Code, reached)
	}
	if got := req2.Header.Get("X-Auth-Claims"); got != "" {
		t.Errorf("empty-sub 401 left X-Auth-Claims=%q", got)
	}
}

// TestHandleOIDC_AcceptsNonEmptySub is the positive control: a validly-signed
// token WITH a sub authenticates and injects verified raw claims downstream,
// overwriting any upstream-injected header value.
func TestHandleOIDC_AcceptsNonEmptySub(t *testing.T) {
	m, key, issuer := startTestOIDC(t)

	token := signIDToken(t, key, issuer, gojwt.MapClaims{"sub": "user-123"})
	var gotClaimsHeader string
	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotClaimsHeader = r.Header.Get("X-Auth-Claims")
		if ClaimsFromContext(r.Context()) == "" {
			t.Error("expected verified claims in request context")
		}
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-proxy"}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for token with sub, got %d", w.Code)
	}
	if gotClaimsHeader == `{"sub":"injected-by-proxy"}` {
		t.Error("injected X-Auth-Claims survived; expected overwrite by verified claims")
	}
	if gotClaimsHeader == "" || !json.Valid([]byte(gotClaimsHeader)) {
		t.Fatalf("expected valid verified claims in X-Auth-Claims, got %q", gotClaimsHeader)
	}
	if !strings.Contains(gotClaimsHeader, `"user-123"`) {
		t.Errorf("verified sub not present in injected claims: %q", gotClaimsHeader)
	}
}
