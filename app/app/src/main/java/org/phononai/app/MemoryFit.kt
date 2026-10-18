package org.phononai.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Whether the model about to be loaded can actually fit in the memory the phone
 * has *right now*.
 *
 * totalMem answers the wrong question: an 8 GB phone with 7 GB already committed
 * is not an 8 GB phone for this purpose. Everything here compares against
 * [ActivityManager.MemoryInfo.availMem] — the reclaimable-and-free figure the
 * kernel would give a new allocation — plus a working margin for the allocator,
 * the tokenizer, and the transient peak while the weights are mapped.
 *
 * The KV cache is estimated rather than read from the GGUF header: parsing the
 * file would mean a model-format dependency this build does not carry. The
 * estimate scales the per-token cache with the file size, which tracks layer and
 * KV-head count closely enough to answer "will this fit" without pretending to
 * be exact.
 */
object MemoryFit {

    /** Headroom kept free so the load itself and the UI do not trip lmkd. */
    const val WORKING_MARGIN_BYTES = 384L * 1024 * 1024

    /**
     * Bytes of KV cache per token, from the model file size. Anchored on the
     * measured ~28 KB/token of a 1.28 GB Q4 2B model at 32K context; clamped so
     * a tiny or huge file cannot produce an absurd number. An estimate, stated
     * as such wherever it is shown.
     */
    fun kvBytesPerToken(modelBytes: Long): Long =
        (modelBytes / 45_000L).coerceIn(12_288L, 131_072L)

    fun estimateKvCacheBytes(modelBytes: Long, contextSize: Int): Long {
        if (modelBytes <= 0 || contextSize <= 0) return 0L
        return kvBytesPerToken(modelBytes) * contextSize.toLong()
    }

    data class Verdict(
        val fits: Boolean,
        /** Memory the kernel cannot reclaim quickly: KV, margin, dirty weights. */
        val requiredBytes: Long,
        val availBytes: Long,
        val kvBytes: Long,
        val modelBytes: Long,
        /** Everything the model would touch if every page stayed resident. */
        val totalBytes: Long,
    ) {
        val message: String
            get() {
                val need = gb(requiredBytes)
                val have = gb(availBytes)
                return "this model needs about $need GB of memory that cannot be reclaimed " +
                    "(${gb(kvBytes)} GB KV cache + ${gb(WORKING_MARGIN_BYTES)} GB working margin + " +
                    "a resident share of ${gb(modelBytes)} GB of mapped weights), but only $have GB " +
                    "is available right now; choose a smaller model or a shorter context"
            }
    }

    /**
     * Decide whether [modelBytes] (plus an optional vision projector) at
     * [contextSize] fits, comparing the requirement against the device's current
     * available memory.
     */
    fun check(context: Context, modelBytes: Long, mmprojBytes: Long, contextSize: Int): Verdict {
        val kv = estimateKvCacheBytes(modelBytes, contextSize)
        val weights = modelBytes + mmprojBytes
        // The weights are mmap'd file pages. Counting the whole file as demand
        // refused a 2B model at 32K on the reference phone with 3.2 GB free —
        // a configuration that demonstrably runs, because the kernel drops clean
        // pages under pressure. What it cannot reclaim is the KV cache, the
        // compute buffers and the margin, plus the dirty share of the weights;
        // that is the number to compare against available memory.
        val anonymous = kv + WORKING_MARGIN_BYTES + weights / 8
        val total = weights + kv + WORKING_MARGIN_BYTES
        val avail = availBytes(context)
        // An unknown availability (0) must not refuse a load it cannot judge.
        val fits = avail <= 0 || anonymous <= avail
        return Verdict(fits, anonymous, avail, kv, modelBytes, total)
    }

    fun availBytes(context: Context): Long = try {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.availMem
    } catch (_: Throwable) {
        0L
    }

    /**
     * True for the exit codes a Linux OOM kill produces: the child is killed by
     * SIGKILL, so waitFor() reports 128 + 9.
     */
    fun isKilled(exitCode: Int): Boolean = exitCode == 137 || exitCode == -9

    /**
     * Ask the platform whether the child was killed for memory (API 30+).
     *
     * Best-effort: ApplicationExitInfo is keyed on the app's package, and the
     * llama-server child is a same-uid process the platform may or may not
     * attribute to it. When a low-memory record exists near [sinceMs] it is the
     * likely cause; when there is none, the caller falls back to the exit code.
     */
    fun lowMemoryExit(context: Context, sinceMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val manager = context.getSystemService(ActivityManager::class.java)
            val reasons = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            reasons.any { info ->
                info.timestamp >= sinceMs - EXIT_INFO_SLACK_MS &&
                    info.reason == ApplicationExitInfo.REASON_LOW_MEMORY
            }
        } catch (t: Throwable) {
            Log.i(TAG, "ApplicationExitInfo unavailable: ${t.javaClass.simpleName}")
            false
        }
    }

    /** The friendly label for a memory kill, vs an unknown crash. */
    const val TOO_BIG_MESSAGE = "this model is too big for this phone"

    private const val EXIT_INFO_SLACK_MS = 5_000L
    private const val TAG = "Phonon"

    fun gb(bytes: Long): String = "%.2f".format(bytes / 1_000_000_000.0)
}
