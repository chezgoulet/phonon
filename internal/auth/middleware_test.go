package auth

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func parseRSAPublicKey(pemBytes []byte) (*rsa.PublicKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, fmt.Errorf("no pem block")
	}
	key, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	rsaKey, ok := key.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("not an rsa public key")
	}
	return rsaKey, nil
}

func TestStatusSecure(t *testing.T) {
	m := New(Config{
		Mode:   "oidc",
		Issuer: "https://auth.example.com",
	})
	m.started = true

	s := m.Status()
	if s.Mode != "secure" {
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

func TestHandlerInsecurePassThrough(t *testing.T) {
	m := New(Config{Mode: "none"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
}

func TestHandlerMissingToken(t *testing.T) {
	jwksSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer jwksSrv.Close()

	m := New(Config{
		Mode:     "oidc",
		Issuer:   jwksSrv.URL,
		ClientID: "test-client",
	})
	m.jwksURL = jwksSrv.URL + "/jwks"
	m.jwks = &JWKSSet{Keys: []JWK{}}
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", w.Code)
	}
}

func TestHandlerInvalidToken(t *testing.T) {
	m := New(Config{Mode: "oidc"})
	m.started = true

	handler := m.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer not.a.token")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", w.Code)
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
			req := httptest.NewRequest(http.MethodGet, "/", nil)
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

func TestStatusHandler(t *testing.T) {
	m := New(Config{Mode: "oidc", Issuer: "https://auth.example.com"})
	m.started = true

	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/status", nil)
	w := httptest.NewRecorder()
	m.StatusHandler()(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}

	var s Status
	json.NewDecoder(w.Body).Decode(&s)
	if s.Mode != "secure" {
		t.Errorf("expected secure, got %s", s.Mode)
	}
}

// newTestOIDCServer creates an httptest.Server that serves OIDC discovery + JWKS.
// Returns the server and the URL (pre-resolved for closure use).
func newTestOIDCServer(discoveryPath, jwksPath string, keys []JWK) (*httptest.Server, string) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "openid-configuration") || r.URL.Path == discoveryPath {
			json.NewEncoder(w).Encode(openIDConfig{
				Issuer:  srv.URL,
				JWKSURI: srv.URL + jwksPath,
			})
			return
		}
		if strings.HasSuffix(r.URL.Path, "jwks") || r.URL.Path == jwksPath {
			json.NewEncoder(w).Encode(JWKSSet{Keys: keys})
			return
		}
	}))
	return srv, srv.URL
}

func TestStartStopSecure(t *testing.T) {
	srv, srvURL := newTestOIDCServer("/.well-known/openid-configuration", "/jwks", nil)
	defer srv.Close()

	m := New(Config{
		Mode:             "oidc",
		Issuer:           srvURL,
		ClientID:         "test",
		DiscoveryTimeout: 5 * time.Second,
	})

	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}

	s := m.Status()
	if s.Mode != "secure" {
		t.Errorf("expected secure, got %s", s.Mode)
	}

	m.Stop()
	m.Stop() // second stop no-op
}

func TestDoubleStart(t *testing.T) {
	srv, srvURL := newTestOIDCServer("/.well-known/openid-configuration", "/jwks", nil)
	defer srv.Close()

	m := New(Config{Mode: "oidc", Issuer: srvURL})
	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer m.Stop()

	if err := m.Start(); err == nil {
		t.Error("expected error on double start")
	}
}

func TestDiscoverFailure(t *testing.T) {
	m := New(Config{
		Mode:             "oidc",
		Issuer:           "http://nonexistent.example.com",
		DiscoveryTimeout: 1,
	})

	if err := m.Start(); err == nil {
		t.Error("expected error for unreachable issuer")
	}
}

func TestJWKSRefresh(t *testing.T) {
	var jwksCallCount int
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "jwks") {
			jwksCallCount++
			json.NewEncoder(w).Encode(JWKSSet{Keys: []JWK{{
				Kty: "RSA",
				Kid: "test-key",
				Alg: "RS256",
				N:   base64.RawURLEncoding.EncodeToString([]byte("modulusbyteshere")),
				E:   base64.RawURLEncoding.EncodeToString([]byte("AQAB")),
			}}})
			return
		}
	}))
	defer srv.Close()

	m := New(Config{
		Mode:   "oidc",
		Issuer: srv.URL + "/",
	})

	m.jwksURL = srv.URL + "/jwks"
	if err := m.refreshJWKS(); err != nil {
		t.Fatalf("refreshJWKS: %v", err)
	}

	if jwksCallCount != 1 {
		t.Errorf("expected 1 jwks call, got %d", jwksCallCount)
	}

	if m.jwks == nil || len(m.jwks.Keys) != 1 {
		t.Error("expected jwks to have 1 key")
	}
}

func TestBase64URLDecode(t *testing.T) {
	tests := []struct {
		input string
		want  string
	}{
		{"dGVzdA", "test"},
		{"dGVzdA==", "test"},
	}
	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			got, err := base64URLDecode(tt.input)
			if err != nil {
				t.Fatalf("base64URLDecode: %v", err)
			}
			if string(got) != tt.want {
				t.Errorf("got %q, want %q", string(got), tt.want)
			}
		})
	}
}

func TestParseRSAPublicKey(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}

	pubBytes, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatalf("MarshalPKIXPublicKey: %v", err)
	}

	pemData := pem.EncodeToMemory(&pem.Block{
		Type:  "PUBLIC KEY",
		Bytes: pubBytes,
	})

	parsed, err := parseRSAPublicKey(pemData)
	if err != nil {
		t.Fatalf("parseRSAPublicKey: %v", err)
	}

	if parsed.N.Cmp(key.N) != 0 {
		t.Error("modulus mismatch")
	}
}

func TestValidateTokenRS256(t *testing.T) {
	// Generate a real RSA key
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}

	// Build JWK
	nBytes := key.N.Bytes()
	eBytes := bigEndianBytes(key.E)

	jwk := JWK{
		Kty: "RSA",
		Kid: "test-key-1",
		Alg: "RS256",
		N:   base64.RawURLEncoding.EncodeToString(nBytes),
		E:   base64.RawURLEncoding.EncodeToString(eBytes),
	}

	srv, srvURL := newTestOIDCServer("/.well-known/openid-configuration", "/jwks", []JWK{jwk})
	defer srv.Close()

	m := New(Config{
		Mode:     "oidc",
		Issuer:   srvURL,
		ClientID: "test-client",
	})

	m.jwksURL = srvURL + "/jwks"
	if err := m.refreshJWKS(); err != nil {
		t.Fatalf("refreshJWKS: %v", err)
	}
	m.started = true

	// Create a valid JWT with future expiry
	future := time.Now().Add(24 * time.Hour).Unix()
	header := `{"alg":"RS256","kid":"test-key-1","typ":"JWT"}`
	payload := fmt.Sprintf(`{"iss":"%s","sub":"user-1","aud":"test-client","exp":%d,"iat":%d}`,
		srvURL, future, future-3600)

	headerB64 := base64.RawURLEncoding.EncodeToString([]byte(header))
	payloadB64 := base64.RawURLEncoding.EncodeToString([]byte(payload))
	signedContent := headerB64 + "." + payloadB64

	hash := sha256.Sum256([]byte(signedContent))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hash[:])
	if err != nil {
		t.Fatalf("Sign: %v", err)
	}
	sigB64 := base64.RawURLEncoding.EncodeToString(sig)

	token := signedContent + "." + sigB64

	claims, err := m.validateToken(token)
	if err != nil {
		t.Fatalf("validateToken: %v", err)
	}

	if !strings.Contains(claims, `"sub":"user-1"`) {
		t.Errorf("expected user-1 in claims, got %s", claims)
	}
}

func TestValidateTokenExpired(t *testing.T) {
	m := New(Config{Mode: "oidc"})
	m.jwks = &JWKSSet{Keys: []JWK{}}
	m.started = true

	// Token that expired in the past
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none"}`))
	payload := base64.RawURLEncoding.EncodeToString([]byte(`{"exp":100}`))
	token := header + "." + payload + ".invalidsig"

	_, err := m.validateToken(token)
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Errorf("expected expired error, got %v", err)
	}
}

func TestValidateTokenES256(t *testing.T) {
	// Generate an ECDSA P-256 key
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}

	// Build JWK
	xBytes := key.X.Bytes()
	yBytes := key.Y.Bytes()
	// Pad to 32 bytes for P-256
	xPadded := padToSize(xBytes)
	yPadded := padToSize(yBytes)

	jwk := JWK{
		Kty: "EC",
		Kid: "ec-key-1",
		Alg: "ES256",
		Crv: "P-256",
		X:   base64.RawURLEncoding.EncodeToString(xPadded),
		Y:   base64.RawURLEncoding.EncodeToString(yPadded),
	}

	srv, srvURL := newTestOIDCServer("/.well-known/openid-configuration", "/jwks", []JWK{jwk})
	defer srv.Close()

	m := New(Config{Mode: "oidc", Issuer: srvURL, ClientID: "test-client"})
	m.jwksURL = srvURL + "/jwks"
	if err := m.refreshJWKS(); err != nil {
		t.Fatalf("refreshJWKS: %v", err)
	}
	m.started = true

	// Create a valid ECDSA-signed JWT
	future := time.Now().Add(24 * time.Hour).Unix()
	header := `{"alg":"ES256","kid":"ec-key-1","typ":"JWT"}`
	payload := fmt.Sprintf(`{"iss":"%s","sub":"ec-user","aud":"test-client","exp":%d,"iat":%d}`,
		srvURL, future, future-3600)

	headerB64 := base64.RawURLEncoding.EncodeToString([]byte(header))
	payloadB64 := base64.RawURLEncoding.EncodeToString([]byte(payload))
	signedContent := headerB64 + "." + payloadB64

	hash := sha256.Sum256([]byte(signedContent))
	r, s, err := ecdsa.Sign(rand.Reader, key, hash[:])
	if err != nil {
		t.Fatalf("Sign: %v", err)
	}

	// ECDSA signature is DER-encoded
	sigBytes := encodeECDSASignatureDER(r, s)
	sigB64 := base64.RawURLEncoding.EncodeToString(sigBytes)
	token := signedContent + "." + sigB64

	claims, err := m.validateToken(token)
	if err != nil {
		t.Fatalf("validateToken ES256: %v", err)
	}
	if !strings.Contains(claims, `"sub":"ec-user"`) {
		t.Errorf("expected ec-user in claims, got %s", claims)
	}
}

func TestValidateTokenES256RawSig(t *testing.T) {
	// Generate an ECDSA P-256 key
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}

	xBytes := key.X.Bytes()
	yBytes := key.Y.Bytes()
	xPadded := padToSize(xBytes)
	yPadded := padToSize(yBytes)

	jwk := JWK{
		Kty: "EC",
		Kid: "ec-key-2",
		Alg: "ES256",
		Crv: "P-256",
		X:   base64.RawURLEncoding.EncodeToString(xPadded),
		Y:   base64.RawURLEncoding.EncodeToString(yPadded),
	}

	srv, srvURL := newTestOIDCServer("/.well-known/openid-configuration", "/jwks", []JWK{jwk})
	defer srv.Close()

	m := New(Config{Mode: "oidc", Issuer: srvURL, ClientID: "test-client"})
	m.jwksURL = srvURL + "/jwks"
	if err := m.refreshJWKS(); err != nil {
		t.Fatalf("refreshJWKS: %v", err)
	}
	m.started = true

	// Create JWT with raw R||S signature (alternative ECDSA format)
	future := time.Now().Add(24 * time.Hour).Unix()
	header := `{"alg":"ES256","kid":"ec-key-2","typ":"JWT"}`
	payload := fmt.Sprintf(`{"iss":"%s","sub":"raw-ec","aud":"test-client","exp":%d,"iat":%d}`,
		srvURL, future, future-3600)

	headerB64 := base64.RawURLEncoding.EncodeToString([]byte(header))
	payloadB64 := base64.RawURLEncoding.EncodeToString([]byte(payload))
	signedContent := headerB64 + "." + payloadB64

	hash := sha256.Sum256([]byte(signedContent))
	r, s, err := ecdsa.Sign(rand.Reader, key, hash[:])
	if err != nil {
		t.Fatalf("Sign: %v", err)
	}

	// R||S raw format
	rPadded := padToSize(r.Bytes())
	sPadded := padToSize(s.Bytes())
	rawSig := append(rPadded, sPadded...)
	sigB64 := base64.RawURLEncoding.EncodeToString(rawSig)
	token := signedContent + "." + sigB64

	claims, err := m.validateToken(token)
	if err != nil {
		t.Fatalf("validateToken ES256 raw sig: %v", err)
	}
	if !strings.Contains(claims, `"sub":"raw-ec"`) {
		t.Errorf("expected raw-ec in claims, got %s", claims)
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

// padToSize left-pads a byte slice to 32 bytes.
func padToSize(b []byte) []byte {
	const size = 32
	if len(b) >= size {
		return b
	}
	padded := make([]byte, size)
	copy(padded[size-len(b):], b)
	return padded
}

// encodeECDSASignatureDER encodes R and S as an ASN.1 DER SEQUENCE.
func encodeECDSASignatureDER(r, s *big.Int) []byte {
	rBytes := r.Bytes()
	sBytes := s.Bytes()

	// Add leading zero if high bit is set
	if r.BitLen()%8 == 0 {
		rBytes = append([]byte{0}, rBytes...)
	}
	if s.BitLen()%8 == 0 {
		sBytes = append([]byte{0}, sBytes...)
	}

	// Build DER: SEQUENCE { INTEGER r, INTEGER s }
	contents := append([]byte{0x02, byte(len(rBytes))}, rBytes...)
	contents = append(contents, 0x02, byte(len(sBytes)))
	contents = append(contents, sBytes...)

	return append([]byte{0x30, byte(len(contents))}, contents...)
}

func bigEndianBytes(e int) []byte {
	if e == 0 {
		return []byte{0}
	}
	var b []byte
	for e > 0 {
		b = append([]byte{byte(e & 0xFF)}, b...)
		e >>= 8
	}
	return b
}
