//go:build !plan9

package model

import "syscall"

// eloopErrno is the errno openat(..., O_NOFOLLOW) produces when the final
// path component is a symlink. It lives behind a build tag because plan9's
// syscall package has no ELOOP symbol (see cache_eloop_plan9.go).
var eloopErrno = syscall.ELOOP
