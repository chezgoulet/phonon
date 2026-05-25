package registry

import "errors"

var (
	// ErrNotFound is returned when a device is not in the registry.
	ErrNotFound = errors.New("device not found")
	// ErrAlreadyRegistered is returned when registering a device that exists.
	ErrAlreadyRegistered = errors.New("device already registered")
	// ErrWrongState is returned when an operation is invalid for the current node state.
	ErrWrongState = errors.New("invalid node state for operation")
)

// IsNotFound reports whether err is a not-found error.
func IsNotFound(err error) bool {
	return errors.Is(err, ErrNotFound)
}

// IsAlreadyRegistered reports whether err is an already-registered error.
func IsAlreadyRegistered(err error) bool {
	return errors.Is(err, ErrAlreadyRegistered)
}

// IsWrongState reports whether err is a wrong-state error.
func IsWrongState(err error) bool {
	return errors.Is(err, ErrWrongState)
}
