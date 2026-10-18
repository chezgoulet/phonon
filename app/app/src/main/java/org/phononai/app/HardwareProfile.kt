package org.phononai.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

/**
 * One RAM tier and the defaults that follow from it. The model names are the
 * files the downloader writes; the tier is chosen from total physical memory,
 * not from the size of the installed model.
 */
data class RamTier(
    val id: String,
    val modelFile: String,
    val modelLabel: String,
    val alias: String,
    val contextSize: Int,
    val threads: Int,
)

data class DeviceProfile(
    val model: String,
    val soc: String,
    val hardware: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val totalRamBytes: Long,
    val ramGb: Int,
    val tier: RamTier,
) {
    /** `Pixel 7 · 8 GB · tier S` — the line the Serve screen shows. */
    val summary: String get() = "$model · $ramGb GB · tier ${tier.id}"
}

/**
 * Hardware detection and the tier defaults measured in docs/model-tiers.md.
 *
 * Boundaries are deliberately on decimal GB, the way RAM is advertised:
 * a 7.65 GB Pixel 7 is an 8 GB phone, and a 12 GB phone is not a 16 GB phone.
 */
object HardwareProfile {
    const val GB = 1_000_000_000L

    /**
     * Highest tier whose lower bound the device does not exceed. The model in
     * each tier is the Qwen entry the catalog recommends for it, so there is
     * one place to change a default. A family the user picked is never
     * replaced by these defaults (see Settings.applyAutoConfigIfNeeded).
     */
    private val TIERS = listOf(
        tier("S", "qwen3.5-2b", threads = 4),
        tier("M", "qwen3.5-4b", threads = 6),
        tier("L", "qwen3.5-9b", threads = 8),
    )

    private fun tier(id: String, modelId: String, threads: Int): RamTier {
        val entry = requireNotNull(ModelCatalog.byId(modelId)) { "no catalog entry $modelId" }
        return RamTier(
            id = id,
            modelFile = entry.fileName,
            modelLabel = "${entry.displayName} ${entry.quant}",
            alias = entry.id,
            contextSize = entry.contextSize,
            threads = threads,
        )
    }

    /** Tier boundary: S below 9 GB, M from 9 GB to under 13 GB, L at 13 GB. */
    fun tierForRam(totalRamBytes: Long): RamTier = when {
        totalRamBytes < 9L * GB -> TIERS[0]
        totalRamBytes < 13L * GB -> TIERS[1]
        else -> TIERS[2]
    }

    fun totalRamBytes(context: Context): Long {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        return try {
            manager.getMemoryInfo(info)
            info.totalMem
        } catch (_: Throwable) {
            0L
        }
    }

    fun ramGb(totalRamBytes: Long): Int =
        (totalRamBytes / GB.toDouble()).roundToInt().coerceAtLeast(1)

    fun soc(): String {
        // SOC_MODEL is API 31+; HARDWARE is the vendor codename fallback for
        // Android 10/11, which this app also supports (minSdk 29).
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Build.SOC_MODEL
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        return soc.ifBlank { Build.HARDWARE ?: "" }
    }

    fun detect(context: Context): DeviceProfile {
        val ram = totalRamBytes(context)
        return DeviceProfile(
            model = Build.MODEL ?: "",
            soc = soc(),
            hardware = Build.HARDWARE ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            totalRamBytes = ram,
            ramGb = ramGb(ram),
            tier = tierForRam(ram),
        )
    }
}
