//go:build unix

package model

import "syscall"

// openNoFollow adds O_NOFOLLOW to write-open flags on unix platforms: if the
// final path component is a symlink, the open itself fails with ELOOP instead
// of following the link — before any O_TRUNC/O_APPEND side effect can reach
// the link's target outside the cache root.
const openNoFollow = syscall.O_NOFOLLOW
