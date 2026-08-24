//go:build plan9

package model

import (
	"errors"
	"strings"
)

// eloopErrno is an unmatchable sentinel on plan9: the platform has no ELOOP
// errno symbol.
var eloopErrno = errors.New("ELOOP (errno unsupported on plan9)")

// eloopClassRefusalBase is the plan9 half of eloopClassRefusal (#318).
// Because eloopErrno never matches on this platform (no ELOOP errno exists),
// the diagnosed "symlink" containment message stays as the OR'd fallback —
// it is the only signal available here.
func eloopClassRefusalBase(err error) bool {
	return errors.Is(err, eloopErrno) || strings.Contains(err.Error(), "symlink")
}
