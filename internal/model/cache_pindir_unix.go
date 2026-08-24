//go:build linux

package model

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"
)

// pinnedDir is a directory held open by file descriptor. Every file
// operation routed through it resolves a BARE FILENAME (single path
// component) against the held inode via openat/renameat/unlinkat — no
// ancestor directory is ever re-resolved from a path string. An attacker
// who swaps directory entries under the cache root therefore cannot
// redirect operations: we hold the real directories, not paths pointing
// at them. If a known subdirectory was replaced by a symlink, pinning it
// fails with ELOOP/ENOTDIR (openat O_NOFOLLOW|O_DIRECTORY) — fail-visible
// containment by construction.
type pinnedDir struct {
	fd   int
	disp string // display path for diagnostics only; never resolved
}

// closePinned releases the directory descriptor.
func closePinned(d *pinnedDir) {
	if d.fd >= 0 {
		syscall.Close(d.fd)
		d.fd = -1
	}
}

// pinRootDir resolves the cache root ONCE per operation (EvalSymlinks of
// the root itself, as before) and pins it with O_DIRECTORY|O_NOFOLLOW.
// The root is operator-controlled configuration, not an attacker-swappable
// entry inside the cache tree.
func pinRootDir(root string) (pinnedDir, error) {
	real, err := filepath.EvalSymlinks(root)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("resolve cache root %q: %w", root, err)
	}
	fd, err := syscall.Open(real, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC|openNoFollow, 0)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("pin cache root %q: %w", real, err)
	}
	return pinnedDir{fd: fd, disp: real}, nil
}

// openSub pins the known constant subdirectory name below a pinned dir.
// A symlink planted in place of the subdirectory makes openat fail
// (ELOOP/ENOTDIR) instead of following it out of the cache root.
func (d pinnedDir) openSub(name string) (pinnedDir, error) {
	fd, err := syscall.Openat(d.fd, name, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC|openNoFollow, 0)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("pin subdirectory %q within %q: %w (replaced by symlink or not a directory?)", name, d.disp, err)
	}
	return pinnedDir{fd: fd, disp: filepath.Join(d.disp, name)}, nil
}

// openFile opens name — a bare filename, never containing "/" — below the
// pinned dir. openNoFollow turns a planted final-component symlink into an
// ELOOP failure BEFORE any O_CREATE/O_TRUNC/O_APPEND side effect can reach
// anything outside the held directory inode. Single-component resolution
// through a dirfd cannot traverse a swapped ancestor because no ancestor
// is ever resolved from a path string. oNonBlock is added to every open
// (write-opens included) so a swapped-in FIFO cannot block the goroutine on
// O_WRONLY|O_TRUNC|O_APPEND with no reader present; it is a no-op for
// regular files.
func (d pinnedDir) openFile(name string, flag int, perm os.FileMode) (*os.File, error) {
	if err := bareName(name); err != nil {
		return nil, err
	}
	fd, err := syscall.Openat(d.fd, name, flag|syscall.O_CLOEXEC|openNoFollow|oNonBlock, uint32(perm.Perm()))
	if err != nil {
		return nil, &os.PathError{Op: "openat", Path: filepath.Join(d.disp, name), Err: err}
	}
	return os.NewFile(uintptr(fd), filepath.Join(d.disp, name)), nil
}

// openRead opens an existing entry read-only through the pinned dir.
// (O_NONBLOCK comes from openFile itself.)
func (d pinnedDir) openRead(name string) (*os.File, error) {
	return d.openFile(name, os.O_RDONLY|oNonBlock, 0)
}

// statSize reports the size of name if it exists as a regular file within
// the pinned dir, or 0 when there is nothing resumable (missing, symlink
// — ELOOP via O_NOFOLLOW — or special file). ENOENT maps to (0, nil);
// other errors are returned for the caller to log/ignore.
func (d pinnedDir) statSize(name string) (int64, error) {
	f, err := d.openRead(name)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return 0, nil
		}
		return 0, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return 0, err
	}
	if !fi.Mode().IsRegular() {
		return 0, nil
	}
	return fi.Size(), nil
}

// remove unlinks name within the pinned dir (unlinkat). The unlink lands
// on the held inode regardless of what entry now sits at the old path.
// Issued as a raw SYS_UNLINKAT syscall (this file builds for linux only):
// some Go toolchains expose a 2-arg syscall.Unlinkat wrapper without the
// flags parameter, and wrapper signatures are not portable across
// platforms — the raw syscall sidesteps whichever variant a toolchain
// ships (flags=0 → plain unlink).
func (d pinnedDir) remove(name string) error {
	if err := bareName(name); err != nil {
		return err
	}
	nameB, err := syscall.ByteSliceFromString(name)
	if err != nil {
		return err
	}
	_, _, errno := syscall.Syscall(syscall.SYS_UNLINKAT,
		uintptr(d.fd), uintptr(unsafe.Pointer(&nameB[0])), 0)
	if errno != 0 {
		return &os.PathError{Op: "unlinkat", Path: filepath.Join(d.disp, name), Err: errno}
	}
	return nil
}

// renameAt atomically moves oldName (in src) to newName (in dst). Both
// directories are pinned fds, so renameat(2) resolves exactly two bare
// filenames against inodes we already hold — unlike os.Rename, there is no
// path-string resolution left to race on, so the promoted file cannot be
// redirected outside the cache models dir mid-call.
func renameAt(src pinnedDir, oldName string, dst pinnedDir, newName string) error {
	if err := bareName(oldName); err != nil {
		return err
	}
	if err := bareName(newName); err != nil {
		return err
	}
	oldB, err := syscall.ByteSliceFromString(oldName)
	if err != nil {
		return err
	}
	newB, err := syscall.ByteSliceFromString(newName)
	if err != nil {
		return err
	}
	_, _, errno := syscall.Syscall6(syscall.SYS_RENAMEAT,
		uintptr(src.fd), uintptr(unsafe.Pointer(&oldB[0])),
		uintptr(dst.fd), uintptr(unsafe.Pointer(&newB[0])),
		0, 0)
	if errno != 0 {
		return &os.LinkError{
			Op:  "renameat",
			Old: filepath.Join(src.disp, oldName),
			New: filepath.Join(dst.disp, newName),
			Err: errno,
		}
	}
	return nil
}

// pinForeignDir pins an arbitrary destination directory by PATH. This is
// legacy resolution used ONLY for destinations outside the managed cache
// subdirs (tools/tests pointing downloads at a scratch directory).
//
// RESIDUAL RACE (documented, fail-visible): between EvalSymlinks(dir) and
// the open below, dir itself could still be swapped by a local attacker —
// this is the round-1 check-then-open window, kept alive only for foreign
// destinations. Cache-internal destinations never take this path:
// Get/Put/downloadOnce route <root>/models and <root>/.tmp through a
// freshly pinned cache-root fd (see Cache.pinDestination).
func pinForeignDir(path string) (pinnedDir, error) {
	real, err := filepath.EvalSymlinks(path)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("resolve %q: %w", path, err)
	}
	fd, err := syscall.Open(real, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC|openNoFollow, 0)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("pin directory %q: %w", real, err)
	}
	return pinnedDir{fd: fd, disp: real}, nil
}
