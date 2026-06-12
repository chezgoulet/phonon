// Package web will embed compiled React UI assets once the web UI is built.
// The current UI is a server-rendered Go template served from
// cmd/phonon-coordinator/ui_embed.go (the embed-ui/ directory).
// See issue #13 — Web UI (React + Tailwind, go:embed).
// Until that lands, this package is reserved for the API-level
// components that the React UI will call.
package web
