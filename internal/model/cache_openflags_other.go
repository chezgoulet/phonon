//go:build !unix

package model

// openNoFollow is zero on platforms whose syscall package lacks O_NOFOLLOW
// (e.g. Windows, Plan 9, js/wasm). Containment there relies on the pre-open
// Lstat/parent-resolution checks and the post-open EvalSymlinks verification.
const openNoFollow = 0

// oNonBlock has no portable equivalent outside unix; the non-blocking probe
// optimization applies to unix only.
const oNonBlock = 0
