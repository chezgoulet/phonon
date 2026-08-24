//go:build plan9

package model

import "errors"

// eloopErrno is an unmatchable sentinel on plan9: the platform has no ELOOP
// errno symbol. Symlink refusals are still classified there via the
// diagnosed "symlink" containment message in eloopClassRefusal.
var eloopErrno = errors.New("ELOOP (errno unsupported on plan9)")
