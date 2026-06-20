package api

import (
	"net"
	"net/http"
	"net/url"
	"strings"
)

// checkOrigin validates the Origin header for WebSocket connections.
// Returns true if the origin is allowed (same-origin), false otherwise.
// Empty origins (app clients) are always allowed.
func checkOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}

	// Parse and validate the origin URL
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}

	// Reject about:blank, data:, file:, and other non-standard schemes
	if u.Scheme == "" || u.Host == "" {
		// If it has no scheme and no host, it might be a plain URL
		// Try parsing more strictly
		if !strings.Contains(origin, "://") {
			// Check for common non-origin values
			if origin == "null" || origin == "about:blank" || origin == "" {
				return false
			}
		}
		if u.Host == "" {
			return false
		}
	}

	// Reject origins with userinfo (credentials embedded in URL)
	if u.User != nil {
		return false
	}

	// Compare schemes (normalize ws→http, wss→https)
	originScheme := normalizeScheme(u.Scheme)
	requestScheme := normalizeScheme(schemeForRequest(r))
	if originScheme != requestScheme {
		return false
	}

	// Get the origin port
	originPort := u.Port()

	// Default port mapping for scheme
	defaultPort := portForScheme(u.Scheme)

	// Normalize: if origin port matches default, treat as no port
	if originPort == defaultPort {
		originPort = ""
	}

	// Get request host and normalize port
	host := r.Host
	requestHost, requestPort, err := net.SplitHostPort(host)
	if err != nil {
		// No port in Host header (e.g., "example.com" instead of "example.com:80")
		requestHost = host
		requestPort = ""
	}

	// Normalize request port — if it matches default for the scheme, treat as no port
	scheme := schemeForRequest(r)
	requestDefaultPort := portForScheme(scheme)
	if requestPort == requestDefaultPort {
		requestPort = ""
	}

	// Compare origin host with request host
	// Strip brackets from IPv6 for comparison
	originHost := strings.Trim(u.Hostname(), "[]")
	requestHost = strings.Trim(requestHost, "[]")

	if originHost != requestHost {
		return false
	}

	// Compare ports
	if originPort != requestPort {
		return false
	}

	return true
}

// portForScheme returns the default TCP port for a given URI scheme.
// Returns empty string for unknown schemes.
func portForScheme(scheme string) string {
	switch scheme {
	case "http", "ws":
		return "80"
	case "https", "wss":
		return "443"
	default:
		return ""
	}
}

// schemeForRequest returns the URI scheme for an HTTP request,
// detecting HTTPS from the TLS connection state.
// normalizeScheme maps WebSocket schemes to their HTTP equivalents for comparison.
func normalizeScheme(scheme string) string {
	switch scheme {
	case "ws":
		return "http"
	case "wss":
		return "https"
	default:
		return scheme
	}
}

// schemeForRequest returns the URI scheme for an HTTP request,
// detecting HTTPS from the TLS connection state.
func schemeForRequest(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	// If the Host header carries the standard HTTPS port, infer https.
	// This handles reverse-proxy deployments where TLS terminates upstream
	// and the Go server receives plain HTTP on the TLS port.
	if host, port, err := net.SplitHostPort(r.Host); err == nil {
		_ = host
		if port == "443" {
			return "https"
		}
	}
	return "http"
}
