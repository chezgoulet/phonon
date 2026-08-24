//go:build !plan9

package model

import (
	"errors"
	"syscall"
)

// eloopErrno is the errno openat(..., O_NOFOLLOW) produces when the final
// path component is a symlink. It lives behind a build tag because plan9's
// syscall package has no ELOOP symbol (see cache_eloop_plan9.go).
var eloopErrno = syscall.ELOOP

// eloopClassRefusalBase is the !plan9 half of eloopClassRefusal (#318). It
// is STRICTLY errno-primary: diagnoseOpenFailure wraps the real openat errno
// via %w, so errors.Is(err, eloopErrno) alone is the correct and sufficient
// signal. There is deliberately NO string fallback here — an errno-free
// refusal must NOT classify as an ELOOP-class symlink refusal.
func eloopClassRefusalBase(err error) bool {
	return errors.Is(err, eloopErrno)
}
