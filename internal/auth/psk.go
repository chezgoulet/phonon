package auth

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// validatePSK checks whether the request carries the correct pre-shared key.
// The PSK can be sent as:
//   - Authorization: Bearer <psk>
//   - X-Phonon-Token: <psk>
//
// Comparison uses constant-time to resist timing attacks.
func validatePSK(r *http.Request, psk []byte, pskLen int) bool {
	if pskLen == 0 {
		return false
	}

	token := extractPSK(r)
	if token == "" {
		return false
	}

	// Constant-time comparison
	tokenBytes := []byte(token)
	if len(tokenBytes) != pskLen {
		return false
	}
	return subtle.ConstantTimeCompare(tokenBytes, psk) == 1
}

// extractPSK extracts the PSK token from the request, checking the
// Authorization header first, then falling back to X-Phonon-Token.
func extractPSK(r *http.Request) string {
	// Try Authorization: Bearer first
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(strings.ToLower(auth), "bearer ") {
		return strings.TrimSpace(auth[7:])
	}

	// Fall back to custom header (useful for sidecar clients that can't
	// set arbitrary Authorization headers)
	return strings.TrimSpace(r.Header.Get("X-Phonon-Token"))
}
