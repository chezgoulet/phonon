package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
)

// ErrNoClaims is returned by ClaimsFrom / SubjectFrom when no authenticated
// claims are present in the request context — i.e. the request did not pass
// through OIDC validation (auth mode "none"/"psk", or a handler invoked
// outside the middleware chain). Callers should treat it as "deny".
var ErrNoClaims = errors.New("auth: no claims in request context")

// maxRawClaimsBytes bounds the validated claim set before parsing. Real ID
// tokens are a few KiB; this only guards against a pathological provider.
const maxRawClaimsBytes = 1 << 16 // 64 KiB

// Claims is the server-side view of an authenticated identity, parsed from
// the validated ID token by the auth middleware. It is populated exclusively
// inside handleOIDC from tokens whose signature, expiry, issuer, and audience
// were verified by the OIDC provider. Clients cannot influence it: any
// X-Auth-Claims header arriving on the wire is stripped before validation and
// never re-injected.
//
// Handlers must read authorization data from this struct (via ClaimsFrom /
// SubjectFrom), never from request headers.
type Claims struct {
	// Subject is the `sub` claim — the stable identity of the caller at
	// the issuer.
	Subject string `json:"sub"`
	// Issuer is the `iss` claim (the OIDC provider URL).
	Issuer string `json:"iss"`
	// Audience holds the `aud` claim values; the verified client_id is
	// among them. Populated by UnmarshalJSON, which accepts both the
	// string form ("aud":"phonon") and the array form ("aud":["phonon"])
	// the OIDC core spec allows.
	Audience []string `json:"-"`
	// Expiry is the Unix-seconds `exp` claim of the token.
	Expiry int64 `json:"exp,omitempty"`
	// Email is the optional `email` claim (profile scope), for logs/UI.
	Email string `json:"email,omitempty"`
	// Roles carries app-assigned role names from the `roles` claim
	// (e.g. "admin"). Empty when absent.
	Roles []string `json:"roles,omitempty"`
	// Scopes carries scopes parsed from the space-delimited `scope`
	// claim. Empty when absent.
	Scopes []string `json:"-"`

	// raw retains the exact validated claim set as JSON so handlers can
	// read provider-specific claims without this package modelling all of
	// them.
	raw json.RawMessage
}

// UnmarshalJSON decodes the standard claim members, normalizing `aud` to a
// slice regardless of whether the provider emitted it as a string or an
// array, and splitting the space-delimited `scope` claim into Scopes.
func (c *Claims) UnmarshalJSON(data []byte) error {
	// alias avoids infinite recursion through this method; Audience and
	// Scopes are decoded out-of-band below.
	type alias Claims
	aux := struct {
		*alias
		Aud json.RawMessage `json:"aud,omitempty"`
		Sco string          `json:"scope,omitempty"`
	}{alias: (*alias)(c)}

	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}

	if len(aux.Aud) > 0 && string(aux.Aud) != "null" {
		var one string
		if err := json.Unmarshal(aux.Aud, &one); err == nil {
			c.Audience = []string{one}
		} else {
			var many []string
			if err := json.Unmarshal(aux.Aud, &many); err != nil {
				return fmt.Errorf("decode aud claim: %w", err)
			}
			c.Audience = many
		}
	}
	c.Scopes = parseScope(aux.Sco)
	return nil
}

// HasRole reports whether the token carries the given role name.
func (c *Claims) HasRole(role string) bool {
	if c == nil {
		return false
	}
	for _, r := range c.Roles {
		if r == role {
			return true
		}
	}
	return false
}

// HasScope reports whether the token carries the given OAuth scope.
func (c *Claims) HasScope(scope string) bool {
	if c == nil {
		return false
	}
	for _, s := range c.Scopes {
		if s == scope {
			return true
		}
	}
	return false
}

// Raw returns the validated claim set exactly as it arrived from the
// provider (post signature verification). Treat the result as read-only.
func (c *Claims) Raw() json.RawMessage {
	if c == nil {
		return nil
	}
	return c.raw
}

// parseClaims decodes validated raw claim JSON into a Claims value.
func parseClaims(raw json.RawMessage) (*Claims, error) {
	if len(raw) > maxRawClaimsBytes {
		return nil, fmt.Errorf("claims too large (%d bytes)", len(raw))
	}
	var c Claims
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, fmt.Errorf("decode claims: %w", err)
	}
	c.raw = raw
	return &c, nil
}

// parseScope splits an OAuth `scope` claim ("openid email profile") into
// its component scopes. Unknown formats yield whatever fields are present.
func parseScope(scopeClaim string) []string {
	return strings.Fields(scopeClaim)
}

// claimsFromContext returns the parsed Claims stored by the middleware, or
// ok=false when absent. Unexported: callers outside this package use
// ClaimsFrom / SubjectFrom, which fail closed via ErrNoClaims.
func claimsFromContext(ctx context.Context) (*Claims, bool) {
	if ctx == nil {
		return nil, false
	}
	switch v := ctx.Value(claimsKey).(type) {
	case *Claims:
		return v, true
	case string:
		// Back-compat: contexts populated by the pre-migration middleware
		// as a raw JSON string still yield usable claims.
		c, err := parseClaims(json.RawMessage(v))
		if err != nil {
			return nil, false
		}
		return c, true
	default:
		return nil, false
	}
}

// ClaimsFrom returns the authenticated Claims for a request. Use it in
// handlers that require authentication; it fails closed with ErrNoClaims
// when the middleware did not run or the deployment does not use OIDC.
//
// Example:
//
//	claims, err := auth.ClaimsFrom(r.Context())
//	if err != nil { ... deny ... }
//	if claims.HasRole("admin") { ... }
func ClaimsFrom(ctx context.Context) (*Claims, error) {
	c, ok := claimsFromContext(ctx)
	if !ok || c == nil {
		return nil, ErrNoClaims
	}
	return c, nil
}

// SubjectFrom returns the authenticated subject, or ErrNoClaims. It is the
// cheap common case of ClaimsFrom for handlers that only need the caller's
// identity.
func SubjectFrom(ctx context.Context) (string, error) {
	c, err := ClaimsFrom(ctx)
	if err != nil {
		return "", err
	}
	return c.Subject, nil
}

// stripInjectedHeaders removes client-controllable headers that downstream
// code must never trust. X-Auth-Claims historically carried identity data;
// since claims moved to the server-side context nothing reads it, and it is
// dropped before validation so it can neither leak into proxies nor be used
// for authorization decisions anywhere in the chain.
func stripInjectedHeaders(r *http.Request) {
	r.Header.Del("X-Auth-Claims")
}
