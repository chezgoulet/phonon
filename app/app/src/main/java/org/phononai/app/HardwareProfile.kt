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

    /** Highest tier whose lower bound the device does not exceed. */
    private val TIERS = listOf(
        RamTier(
            id = "S",
            modelFile = "Qwen3.5-2B-Q4_K_M.gguf",
            modelLabel = "Qwen3.5-2B Q4_K_M",
            alias = "qwen3.5-2b",
            contextSize = 32_768,
            threads = 4,
        ),
        RamTier(
            id = "M",
            modelFile = "Qwen3.5-4B-Q4_K_M.gguf",
            modelLabel = "Qwen3.5-4B Q4_K_M",
            alias = "qwen3.5-4b",
            contextSize = 32_768,
            threads = 6,
        ),
        RamTier(
            id = "L",
            modelFile = "Qwen3.5-8B-Q4_K_M.gguf",
            modelLabel = "Qwen3.5-8B Q4_K_M",
            alias = "qwen3.5-8b",
            contextSize = 49_152,
            threads = 8,
        ),
    )

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
        // SOC_MODEL is API 31+; HARDWARE is the vendor codename fallback.
        val soc = try {
            Build.SOC_MODEL
        } catch (_: Throwable) {
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
