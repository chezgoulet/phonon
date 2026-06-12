package api

import (
	"crypto/tls"
	"fmt"
	"net/http"
	"testing"
)

func TestCheckOrigin(t *testing.T) {
	tests := []struct {
		name     string
		host     string
		origin   string
		want     bool
	}{
		// Empty origin (app clients)
		{"empty origin", "example.com:8080", "", true},

		// Simple same-origin
		{"same origin http", "example.com:8080", "http://example.com:8080", true},
		{"same origin https", "example.com:443", "https://example.com:443", true},

		// Cross-origin
		{"different host", "example.com:8080", "http://evil.com:8080", false},
		{"different scheme only", "example.com:8080", "https://example.com:8080", false},

		// Default port normalization
		{"http default port", "example.com:80", "http://example.com", true},
		{"https default port", "example.com:443", "https://example.com", true},
		{"origin with no port, request with default", "example.com:80", "http://example.com", true},
		{"ws scheme default port", "example.com:80", "ws://example.com", true},
		{"wss scheme default port", "example.com:443", "wss://example.com", true},

		// IPv6
		{"ipv6 same", "[::1]:8080", "http://[::1]:8080", true},
		{"ipv6 default port", "[::1]:80", "http://[::1]", true},
		{"ipv6 cross-origin", "[::1]:8080", "http://[::2]:8080", false},

		// With path/query (should be stripped)
		{"origin with path", "example.com:8080", "http://example.com:8080/some/path?q=1", true},
		{"origin with query", "example.com:8080", "http://example.com:8080?q=1", true},

		// Userinfo (should be rejected or ignored)
		{"origin with userinfo", "example.com:8080", "http://user:pass@example.com:8080", false},

		// Invalid origin
		{"invalid url", "example.com:8080", "not a url at all", false},
		{"empty origin string", "example.com:8080", "about:blank", false},

		// Same with explicit port
		{"explicit port match", "myhost.local:9876", "http://myhost.local:9876", true},
		{"explicit port mismatch", "myhost.local:9876", "http://myhost.local:8080", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Build a request with the given Host header and Origin
			req := &http.Request{
				Host: tt.host,
				Header: make(http.Header),
			}
			req.Header.Set("Origin", tt.origin)

			got := checkOrigin(req)
			if got != tt.want {
				t.Errorf("checkOrigin(%q on host %q) = %v, want %v", tt.origin, tt.host, got, tt.want)
			}
		})
	}
}

func TestPortForScheme(t *testing.T) {
	tests := []struct {
		scheme string
		want   string
	}{
		{"http", "80"},
		{"https", "443"},
		{"ws", "80"},
		{"wss", "443"},
		{"ftp", ""},
		{"", ""},
	}
	for _, tt := range tests {
		t.Run(fmt.Sprintf("port for %q", tt.scheme), func(t *testing.T) {
			if got := portForScheme(tt.scheme); got != tt.want {
				t.Errorf("portForScheme(%q) = %q, want %q", tt.scheme, got, tt.want)
			}
		})
	}
}

func TestSchemeForRequest(t *testing.T) {
	// HTTP request
	reqHTTP, _ := http.NewRequest("GET", "http://example.com", nil)
	if got := schemeForRequest(reqHTTP); got != "http" {
		t.Errorf("expected http, got %s", got)
	}
	// HTTPS request via TLS field
	reqHTTPS, _ := http.NewRequest("GET", "https://example.com", nil)
	reqHTTPS.TLS = &tls.ConnectionState{} // non-nil signals HTTPS
	if got := schemeForRequest(reqHTTPS); got != "https" {
		t.Errorf("expected https, got %s", got)
	}
}
