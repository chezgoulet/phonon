package api

import "net/http"

// DeviceTokenHeader carries the per-device auth token established at
// pairing on sidecar→coordinator requests (heartbeat, model-status,
// WebSocket upgrade). Must match the sidecar's CoordinatorClient.
const DeviceTokenHeader = "X-Phonon-Device-Token"

// DeviceAuthorizer authenticates sidecar requests using the pairing
// state. Implemented by *pair.Manager.
type DeviceAuthorizer interface {
	// IsPaired reports whether the device has completed pairing.
	IsPaired(deviceID string) bool
	// Authorize reports whether token is the device's auth token
	// (constant-time; false for unpaired devices).
	Authorize(deviceID, token string) bool
}

// authorizeDevice enforces device-token auth on a sidecar request.
//
// Rules:
//   - auth == nil: enforcement disabled (tests only — production wiring
//     in main.go always sets an authorizer).
//   - Device not paired: allowed. Unpaired devices may register and
//     heartbeat so they show up in the UI for the operator to pair, but
//     the registry never promotes them to Online, so they are never
//     selected for inference and cannot receive commands.
//   - Device paired: the request must carry the correct token in
//     DeviceTokenHeader, otherwise 401.
//
// Returns true if the request may proceed (the 401 has already been
// written when it returns false).
func authorizeDevice(w http.ResponseWriter, r *http.Request, auth DeviceAuthorizer, deviceID string) bool {
	if auth == nil {
		return true
	}
	if !auth.IsPaired(deviceID) {
		return true
	}
	if auth.Authorize(deviceID, r.Header.Get(DeviceTokenHeader)) {
		return true
	}
	writeError(w, http.StatusUnauthorized,
		"device is paired but request is missing a valid "+DeviceTokenHeader+" header")
	return false
}
