//go:build !unix

package model

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// NON-UNIX FALLBACK — READ BEFORE TOUCHING.
//
// Platforms whose syscall package lacks openat/renameat/unlinkat (Windows,
// Plan 9, js/wasm) cannot pin directories by file descriptor. The type
// below emulates the pinnedDir API over full paths, keeping the round-1
// pre-open checks.
//
// RESIDUAL RACE (documented, fail-visible): every operation below resolves
// its FULL PATH at call time, so a local attacker able to swap directory
// entries between the pre-open checks and the OS call retains a
// check-then-use window that the unix implementation closes by
// construction. Containment on this platform is best-effort
// defense-in-depth (Lstat/EvalSymlinks checks plus O_NOFOLLOW where
// available), NOT guaranteed. Callers keep verifyDestinationForWrite as a
// load-bearing check here; on unix it is only a cheap backstop.

type pinnedDir struct {
	disp string // directory path resolved per operation (no fd available)
}

// closePinned is a no-op without fd-backed directories.
func closePinned(d *pinnedDir) {}

// pinRootDir resolves the cache root once per operation.
func pinRootDir(root string) (pinnedDir, error) {
	real, err := filepath.EvalSymlinks(root)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("resolve cache root %q: %w", root, err)
	}
	return pinnedDir{disp: real}, nil
}

// openSub validates the known constant subdirectory below d: it must exist,
// be a real directory, and not be a symlink.
func (d pinnedDir) openSub(name string) (pinnedDir, error) {
	sub := filepath.Join(d.disp, name)
	fi, err := os.Lstat(sub)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("pin subdirectory %q within %q: %w", name, d.disp, err)
	}
	if fi.Mode()&os.ModeSymlink != 0 || !fi.IsDir() {
		return pinnedDir{}, fmt.Errorf("pin subdirectory %q within %q: replaced by symlink or not a directory (symlink attack?)", name, d.disp)
	}
	return pinnedDir{disp: sub}, nil
}

// openFile opens name below d after refusing a planted final-component
// symlink. RESIDUAL RACE: Lstat-then-open window documented in the file
// header — fail-visible best-effort only.
func (d pinnedDir) openFile(name string, flag int, perm os.FileMode) (*os.File, error) {
	if err := bareName(name); err != nil {
		return nil, err
	}
	full := filepath.Join(d.disp, name)
	if fi, err := os.Lstat(full); err == nil && fi.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("refusing to open %q: destination is a symlink resolving outside cache root (symlink attack?)", full)
	}
	f, err := os.OpenFile(full, flag|openNoFollow, perm)
	if err != nil && flag&os.O_CREATE != 0 {
		if fi, lerr := os.Lstat(full); lerr == nil && fi.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("refusing to open %q: destination is a symlink resolving outside cache root (symlink attack?)", full)
		}
	}
	return f, err
}

// openRead opens an existing entry read-only through the emulated dir.
func (d pinnedDir) openRead(name string) (*os.File, error) {
	return d.openFile(name, os.O_RDONLY|oNonBlock, 0)
}

// statSize reports the size of name if it exists as a regular file, or 0
// when there is nothing resumable (missing, symlink, or special file).
func (d pinnedDir) statSize(name string) (int64, error) {
	full := filepath.Join(d.disp, name)
	fi, err := os.Lstat(full)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return 0, nil
		}
		return 0, err
	}
	if !fi.Mode().IsRegular() {
		return 0, nil
	}
	return fi.Size(), nil
}

// remove unlinks name within the emulated dir. RESIDUAL RACE: path-based,
// see file header.
func (d pinnedDir) remove(name string) error {
	if err := bareName(name); err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(d.disp, name)); err != nil {
		return err
	}
	return nil
}

// renameAt moves oldName (in src) to newName (in dst). RESIDUAL RACE:
// os.Rename resolves both path strings at call time; the pre-rename
// verifyDestinationForWrite performed by callers remains load-bearing on
// this platform. See file header.
func renameAt(src pinnedDir, oldName string, dst pinnedDir, newName string) error {
	if err := bareName(oldName); err != nil {
		return err
	}
	if err := bareName(newName); err != nil {
		return err
	}
	return os.Rename(filepath.Join(src.disp, oldName), filepath.Join(dst.disp, newName))
}

// pinForeignDir pins an arbitrary destination directory by path.
// RESIDUAL RACE: identical to the cache-subdir fallback above — documented,
// fail-visible.
func pinForeignDir(path string) (pinnedDir, error) {
	real, err := filepath.EvalSymlinks(path)
	if err != nil {
		return pinnedDir{}, fmt.Errorf("resolve %q: %w", path, err)
	}
	return pinnedDir{disp: real}, nil
}
