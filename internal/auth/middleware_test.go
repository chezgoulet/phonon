package auth

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	gojwt "github.com/golang-jwt/jwt/v5"
)

const testSecureStatus = "secure"

func TestStatusSecure(t *testing.T) {
	m := New(Config{
		Mode:   "oidc",
		Issuer: "https://auth.example.com",
	})
	m.started = true

	s := m.Status()
	if s.Mode != testSecureStatus {
		t.Errorf("expected secure, got %s", s.Mode)
	}
	if s.Issuer != "https://auth.example.com" {
		t.Errorf("expected issuer, got %s", s.Issuer)
	}
}

func TestStatusInsecure(t *testing.T) {
	m := New(Config{Mode: "none"})
	m.started = true

	s := m.Status()
	if s.Mode != "insecure" {
		t.Errorf("expected insecure, got %s", s.Mode)
	}
}

func TestStatusDefault(t *testing.T) {
	m := New(Config{})
	m.started = true

	s := m.Status()
	if s.Mode != "insecure" {
		t.Errorf("expected insecure, got %s", s.Mode)
	}
}

func TestStatusPSK(t *testing.T) {
	m := New(Config{Mode: "psk", PSK: "supersecret"})
	m.started = true

	s := m.Status()
	if s.Mode != "psk" {
		t.Errorf("expected psk, got %s", s.Mode)
	}
}

func TestHandlerInsecurePassThrough(t *testing.T) {
	m := New(Config{Mode: "none"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
}

func TestHandlerMissingToken(t *testing.T) {
	m := New(Config{
		Mode: "oidc",
	})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", w.Code)
	}
}

func TestHandlerInvalidToken(t *testing.T) {
	m := New(Config{Mode: "oidc"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer not.a.token")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", w.Code)
	}
}

func TestHandlerPSK(t *testing.T) {
	m := New(Config{Mode: "psk", PSK: "testkey"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))

	// Valid PSK via Authorization header
	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer testkey")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for valid PSK, got %d", w.Code)
	}

	// Valid PSK via X-Phonon-Token
	req2 := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req2.Header.Set("X-Phonon-Token", "testkey")
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w, req2)
	if w2.Code != http.StatusOK {
		t.Errorf("expected 200 for valid X-Phonon-Token, got %d", w2.Code)
	}

	// Invalid PSK
	req3 := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req3.Header.Set("Authorization", "Bearer wrongkey")
	w3 := httptest.NewRecorder()
	handler.ServeHTTP(w3, req3)
	if w3.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for wrong PSK, got %d", w3.Code)
	}
}

func TestExtractBearerToken(t *testing.T) {
	tests := []struct {
		name    string
		header  string
		want    string
		wantErr bool
	}{
		{"valid", "Bearer mytoken", "mytoken", false},
		{"lowercase", "bearer mytoken", "mytoken", false},
		{"missing header", "", "", true},
		{"wrong format", "Basic creds", "", true},
		{"empty", "Bearer", "", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
			if tt.header != "" {
				req.Header.Set("Authorization", tt.header)
			}
			got, err := extractBearerToken(req)
			if (err != nil) != tt.wantErr {
				t.Errorf("extractBearerToken() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("extractBearerToken() = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestStatusHandlerSecure(t *testing.T) {
	m := New(Config{Mode: "oidc", Issuer: "https://auth.example.com"})
	m.started = true

	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/status", http.NoBody)
	w := httptest.NewRecorder()
	m.StatusHandler()(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var s Status
	json.NewDecoder(w.Body).Decode(&s)
	if s.Mode != testSecureStatus {
		t.Errorf("expected secure, got %s", s.Mode)
	}
}

func TestStatusHandlerInsecure(t *testing.T) {
	m := New(Config{Mode: "none"})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/status", http.NoBody)
	w := httptest.NewRecorder()
	m.StatusHandler()(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var s Status
	json.NewDecoder(w.Body).Decode(&s)
	if s.Mode != "insecure" {
		t.Errorf("expected insecure, got %s", s.Mode)
	}
}

func TestStartStop(t *testing.T) {
	m := New(Config{Mode: "none"})
	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	if !m.started {
		t.Error("expected started")
	}

	m.Stop()
	m.Stop() // second stop should be no-op
	if m.started {
		t.Error("expected stopped")
	}
}

func TestDoubleStart(t *testing.T) {
	m := New(Config{Mode: "none"})
	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer m.Stop()

	if err := m.Start(); err == nil {
		t.Error("expected error on double start")
	}
}

func TestClaimsFromContext(t *testing.T) {
	ctx := context.WithValue(context.Background(), claimsKey, `{"sub":"test"}`)
	got := ClaimsFromContext(ctx)
	if got != `{"sub":"test"}` {
		t.Errorf("expected claims, got %q", got)
	}

	// Test with empty context
	if ClaimsFromContext(nil) != "" {
		t.Error("expected empty string from nil context")
	}
}

func TestConfigurationModes(t *testing.T) {
	if ModeOIDC != "oidc" {
		t.Errorf("expected oidc, got %s", ModeOIDC)
	}
	if ModeNone != "none" {
		t.Errorf("expected none, got %s", ModeNone)
	}
}

// TestVerifyIDToken_RejectsHS256Forgery ensures that an attacker who knows
// the RS256 public key cannot forge a token using the HS256 algorithm with
// that public key as the HMAC secret (#243).
func TestVerifyIDToken_RejectsHS256Forgery(t *testing.T) {
	// Generate an RSA key pair for the legitimate server
	rsaKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}

	// The public key is public — an attacker knows it
	rsaPub := &rsaKey.PublicKey

	// Attacker forges a token using HS256 with the public key as the HMAC secret
	// This is the classic JWT algorithm confusion attack
	forgedToken := gojwt.NewWithClaims(gojwt.SigningMethodHS256, gojwt.MapClaims{
		"sub": "forged",
		"iss": "attacker",
		"exp": time.Now().Add(24 * time.Hour).Unix(),
	})

	// Sign with the RSA public key bytes as HMAC secret
	pubDER, err := x509.MarshalPKIXPublicKey(rsaPub)
	if err != nil {
		t.Fatalf("MarshalPKIXPublicKey: %v", err)
	}
	tokenStr, err := forgedToken.SignedString(pubDER)
	if err != nil {
		t.Fatalf("SignedString: %v", err)
	}

	// Server tries to verify the token using the RSA public key
	keyFunc := func(token *gojwt.Token) (interface{}, error) {
		return rsaPub, nil
	}

	var claims gojwt.MapClaims
	err = verifyIDToken(tokenStr, keyFunc, &claims)
	if err == nil {
		t.Fatal("HS256 forgery accepted — algorithm confusion vulnerability still open")
	}
	t.Logf("HS256 forgery correctly rejected: %v", err)
}

// ─── PSK edge case tests ─────────────────────────────────────────

func TestHandlerPSK_doubleSpace(t *testing.T) {
	// Authorization: "Bearer  double-space-key" — extractPSK should
	// TrimSpace the result to recover the key.
	m := New(Config{Mode: "psk", PSK: "double-space-key"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer  double-space-key")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for Bearer with double space, got %d", w.Code)
	}
}

func TestHandlerPSK_trailingWhitespace(t *testing.T) {
	m := New(Config{Mode: "psk", PSK: "trailing-key"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer trailing-key \t")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for Bearer with trailing whitespace, got %d", w.Code)
	}
}

func TestHandlerPSK_lowercaseBearer(t *testing.T) {
	m := New(Config{Mode: "psk", PSK: "lowercase-key"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "bearer lowercase-key")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for lowercase 'bearer', got %d", w.Code)
	}
}

func TestHandlerPSK_emptyPSK(t *testing.T) {
	// An empty PSK means the key was not configured. All requests should
	// be rejected with 401.
	m := New(Config{Mode: "psk", PSK: ""})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer any-key")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for empty PSK, got %d", w.Code)
	}

	// Also verify via X-Phonon-Token header
	req2 := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req2.Header.Set("X-Phonon-Token", "any-key")
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w2, req2)
	if w2.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for empty PSK with X-Phonon-Token, got %d", w2.Code)
	}
}

func TestHandlerPSK_xPhononTokenWithWhitespace(t *testing.T) {
	m := New(Config{Mode: "psk", PSK: "token-with-ws"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	// X-Phonon-Token with leading/trailing whitespace
	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("X-Phonon-Token", "  token-with-ws ")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for X-Phonon-Token with whitespace, got %d", w.Code)
	}
}

func TestHandlerPSK_rejectsTabAfterBearer(t *testing.T) {
	// RFC 7235 requires a single space between auth-scheme and token-68.
	// Tab is not valid, so this is a deliberate rejection.
	m := New(Config{Mode: "psk", PSK: "tab-key"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer\ttab-key")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 for Bearer with tab separator, got %d (not RFC 7235 compliant)", w.Code)
	}
}

// TestHandlerPSK_StripsInboundClaimsOn401 verifies #329: the PSK reject
// path strips any upstream-injected X-Auth-Claims locally before writing
// the 401, keeping the belt-and-suspenders defense uniform across every
// 401 branch of the auth middleware.
func TestHandlerPSK_StripsInboundClaimsOn401(t *testing.T) {
	m := New(Config{Mode: "psk", PSK: "real-key"})
	m.started = true

	reached := false
	handler := m.Handler(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		reached = true
	}))

	req := httptest.NewRequest(http.MethodGet, "/", http.NoBody)
	req.Header.Set("Authorization", "Bearer wrong-key")
	req.Header.Set("X-Auth-Claims", `{"sub":"injected-by-proxy"}`)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized || reached {
		t.Fatalf("expected 401 without reaching downstream, got %d reached=%v", w.Code, reached)
	}
	if got := req.Header.Get("X-Auth-Claims"); got != "" {
		t.Errorf("PSK-reject 401 left X-Auth-Claims=%q", got)
	}
}
