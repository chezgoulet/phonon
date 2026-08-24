//go:build unix

package model

import "syscall"

// openNoFollow adds O_NOFOLLOW to write-open flags on unix platforms: if the
// final path component is a symlink, the open itself fails with ELOOP instead
// of following the link — before any O_TRUNC/O_APPEND side effect can reach
// the link's target outside the cache root.
const openNoFollow = syscall.O_NOFOLLOW

// oNonBlock is added to read-only probes through pinned dirfds so a planted
// FIFO at a destination name cannot block the download loop (no-op for
// regular files).
const oNonBlock = syscall.O_NONBLOCK
