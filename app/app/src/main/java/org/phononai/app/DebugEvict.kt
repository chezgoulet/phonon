package org.phononai.app

/**
 * Debug-only bridge to libc `posix_fadvise(2)`.
 *
 * This exists solely for the keep-warm experiment (`POST /debug/evict`). The
 * public `android.system.Os` class has no `posixFadvise` (only
 * `posix_fallocate`), so the one call is made through a small JNI shim.
 * The native library is excluded from release packages, and the route is
 * guarded by BuildConfig.DEBUG and returns 404 in release.
 */
object DebugEvict {
    init {
        System.loadLibrary("phonon-fadvise")
    }

    /** Returns 0 on success, otherwise the errno reported by the kernel. */
    external fun nativeFadviseDontNeed(path: String): Int
}
