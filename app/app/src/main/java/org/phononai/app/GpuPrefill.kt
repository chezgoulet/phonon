package org.phononai.app

import java.io.File

/**
 * Whether this APK carries the optional OpenCL prefill backend.
 *
 * The backend is built only by the opt-in `PHONON_OPENCL=1 app/build-native.sh`
 * path (see docs/native-backends.md) and is never enabled by default: it is
 * expected to help *prefill* throughput only on Adreno GPUs, and has not been
 * tested on one. This class only reports presence, never enables anything — the
 * child is launched with the same arguments either way.
 */
object GpuPrefill {

    /** The dynamic backend ggml builds when GGML_OPENCL=ON. */
    const val LIB_NAME = "libggml-opencl.so"

    const val NOT_INCLUDED = "GPU prefill: not included in this build"
    const val INCLUDED_UNTESTED = "GPU prefill: included — untested on this GPU"

    /** True when the OpenCL backend library sits beside the executable. */
    fun present(nativeLibraryDir: String?): Boolean {
        if (nativeLibraryDir.isNullOrBlank()) return false
        return try {
            File(nativeLibraryDir, LIB_NAME).isFile
        } catch (_: Throwable) {
            false
        }
    }

    /** The status line the Settings card and the device report both use. */
    fun statusLine(present: Boolean): String =
        if (present) INCLUDED_UNTESTED else NOT_INCLUDED
}
