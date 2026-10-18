package org.phononai.app

import android.content.Context
import java.io.File

/**
 * The model families Phonon offers, in the order the picker shows them.
 * Qwen is first because it is the faster default on the reference phone; the
 * ordering is presentation only and does not constrain the choice.
 */
enum class ModelFamily(val key: String, val label: String, val summary: String) {
    QWEN(
        key = "qwen",
        label = "Qwen3.5",
        summary = "Faster on this hardware. The default.",
    ),
    GEMMA(
        key = "gemma",
        label = "Gemma 4",
        summary = "Multimodal, with Gemma's own tool format. Slower here.",
    ),
    MINICPM(
        key = "minicpm",
        label = "MiniCPM5",
        summary = "OpenBMB's on-device model: strongest tool use per gigabyte. Slower than Qwen here.",
    ),
    GRANITE(
        key = "granite",
        label = "Granite",
        summary = "IBM's tool-calling line, Apache-2.0. About half Qwen's speed here.",
    ),
    NEMOTRON(
        key = "nemotron",
        label = "Nemotron",
        summary = "NVIDIA's edge line, non-OSI licence. The slowest measured here.",
    ),
}

/**
 * One downloadable model. Everything the Models screen shows comes from here;
 * there are no model names or URLs elsewhere in the UI.
 *
 * [decodeRuns] are the two 128-token greedy runs measured on the reference
 * Pixel 7 (serial <device-serial>) with llama.cpp `797de63` and
 * `GGML_CPU_REPACK=ON`, 2 threads on cores 6-7. Empty means **unmeasured**:
 * the UI says so rather than guessing.
 */
data class ModelEntry(
    val id: String,
    val family: ModelFamily,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val contextSize: Int,
    val quant: String,
    val decodeRuns: List<Double>,
    val tradeOff: String,
    val ramTier: String,
    val multimodal: Boolean = false,
    /** SPDX identifier, and whether the OSI recognises it. */
    val license: String = "",
    val osiApproved: Boolean = true,
    /** Architecture in one phrase: dense, hybrid, MoE with active params. */
    val arch: String = "",
    /** What this model is actually good at, in the operator's terms. */
    val jobFit: String = "",
    /** The device the numbers came from; blank when unmeasured. */
    val measuredOn: String = "",
    /** A vendor-reported figure and where it came from, if any. */
    val vendorClaim: String = "",
    val source: String = "",
) {
    val measured: Boolean get() = decodeRuns.isNotEmpty()

    /** `9.20 / 8.84 tok/s`; blank when unmeasured. */
    val decodeLabel: String
        get() = decodeRuns.joinToString(" / ") { "%.2f".format(it) } + " tok/s"

    val contextLabel: String
        get() = "${contextSize / 1024}K"

    val tierLabel: String
        get() = when (ramTier) {
            "S" -> "8 GB"
            "M" -> "12 GB"
            "L" -> "16 GB"
            else -> ramTier
        }

    val repo: String
        get() = url.removePrefix("https://huggingface.co/")
            .substringBefore("/resolve/")
}

/**
 * The catalog. Sizes and hashes are the values Hugging Face declares for the
 * pinned files; every URL was checked with a HEAD request on 2026-10-17 and
 * returned 200 with that Content-Length. See docs/models.md for provenance.
 */
object ModelCatalog {

    /** Date of the device measurements below, not of the catalog itself. */
    const val MEASURED_DATE = "2026-10-17"

    const val DEFAULT_ID = "qwen3.5-2b"

    val entries: List<ModelEntry> = listOf(
        ModelEntry(
            id = "qwen3.5-2b",
            family = ModelFamily.QWEN,
            displayName = "Qwen3.5-2B",
            fileName = "Qwen3.5-2B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            sizeBytes = 1_280_835_840L,
            sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            contextSize = 32_768,
            quant = "Q4_K_M",
            decodeRuns = listOf(9.20, 8.84),
            tradeOff = "Fastest here and the 8 GB default: 32K context with headroom to spare.",
            ramTier = "S",
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense 2B, hybrid attention",
            jobFit = "agent work: the fastest measured model, and the default",
            measuredOn = "Pixel 7",
        ),
        ModelEntry(
            id = "qwen3.5-4b",
            family = ModelFamily.QWEN,
            displayName = "Qwen3.5-4B",
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            sizeBytes = 2_740_937_888L,
            sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            contextSize = 16_384,
            quant = "Q4_K_M",
            decodeRuns = listOf(4.13, 3.94),
            tradeOff = "Better answers than the 2B at about 4 tok/s. Runs on 8 GB at 16K " +
                "with ~600 MB free; at 32K it fits but leaves ~250 MB.",
            ramTier = "S",
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense 4B, hybrid attention",
            jobFit = "better answers when speed matters less",
            measuredOn = "Pixel 7",
        ),
        ModelEntry(
            id = "qwen3.5-9b",
            family = ModelFamily.QWEN,
            displayName = "Qwen3.5-9B",
            fileName = "Qwen3.5-9B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf",
            sizeBytes = 5_680_522_464L,
            sha256 = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",
            contextSize = 49_152,
            quant = "Q4_K_M",
            decodeRuns = emptyList(),
            tradeOff = "Highest-quality Qwen option; unmeasured and only sensible on 16 GB.",
            ramTier = "L",
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense 9B, hybrid attention",
            jobFit = "12-16 GB phones only; unmeasured here",
            measuredOn = "",
        ),
        ModelEntry(
            id = "gemma-4-e2b",
            family = ModelFamily.GEMMA,
            displayName = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it-Q4_0.gguf",
            url = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
            sizeBytes = 2_841_481_184L,
            sha256 = "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
            contextSize = 16_384,
            quant = "Q4_0",
            decodeRuns = listOf(7.05, 6.74),
            tradeOff = "Multimodal and Gemma's own tool format, at a measured ~2 tok/s less than Qwen 2B.",
            ramTier = "S",
            multimodal = true,
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense E2B",
            jobFit = "chat and images, at about two tok/s less than Qwen",
            measuredOn = "Pixel 7",
        ),
        ModelEntry(
            id = "gemma-4-e4b",
            family = ModelFamily.GEMMA,
            displayName = "Gemma 4 E4B",
            fileName = "gemma-4-E4B-it-Q4_0.gguf",
            url = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf",
            sizeBytes = 4_590_807_392L,
            sha256 = "a555b900214b477d8880e7832e0b8925e139b0159640036b09fe472b6f2097f2",
            contextSize = 16_384,
            quant = "Q4_0",
            decodeRuns = listOf(4.12, 3.85),
            tradeOff = "The larger multimodal Gemma at about 4 tok/s. Runs on 8 GB at 16K " +
                "with ~560 MB free.",
            ramTier = "S",
            multimodal = true,
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense E4B",
            jobFit = "larger Gemma; runs on 8 GB at 16K",
            measuredOn = "Pixel 7",
        ),
        ModelEntry(
            id = "minicpm5-2b",
            family = ModelFamily.MINICPM,
            displayName = "MiniCPM5-2B",
            fileName = "MiniCPM5-2B-Q4_K_M.gguf",
            url = "https://huggingface.co/openbmb/MiniCPM5-2B-GGUF/resolve/main/MiniCPM5-2B-Q4_K_M.gguf",
            sizeBytes = 1_561_318_368L,
            sha256 = "ec2d5801640099e97d8d7e8003ad4d81f336e757811f03a26173dddf386602fd",
            contextSize = 32_768,
            quant = "Q4_K_M",
            decodeRuns = listOf(6.82, 6.57),
            tradeOff = "Apache-2.0. 2B-class tool-use leader (tau2-Bench 97.1); about 25% slower than Qwen 2B here.",
            ramTier = "S",
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense 2B, Llama architecture",
            jobFit = "tool use per gigabyte, if its agent behaviour holds up",
            measuredOn = "Pixel 7",
            vendorClaim = "tau2-Bench Telecom 97.1 (vendor-reported)",
            source = "https://huggingface.co/openbmb/MiniCPM5-2B",
        ),
        ModelEntry(
            id = "granite-4.2-3b",
            family = ModelFamily.GRANITE,
            fileName = "granite-4.2-3b-Q4_K_M.gguf",
            displayName = "Granite 4.2 3B",
            url = "https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF/resolve/main/granite-4.2-3b-Q4_K_M.gguf",
            sizeBytes = 2_244_011_552L,
            sha256 = "e0406663965846ae22a403456eb826ccce5f450840491f71952f18a7cb78e7d5",
            contextSize = 32_768,
            quant = "Q4_K_M",
            decodeRuns = listOf(4.41, 4.39),
            tradeOff = "Apache-2.0 with Granite's tool-calling focus; about half Qwen's speed here.",
            ramTier = "S",
            license = "Apache-2.0",
            osiApproved = true,
            arch = "dense 3B",
            jobFit = "tool calling on IBM's line; about half Qwen's speed",
            measuredOn = "Pixel 7",
        ),
        ModelEntry(
            id = "nemotron-3-nano-4b",
            family = ModelFamily.NEMOTRON,
            displayName = "Nemotron 3 Nano 4B",
            fileName = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
            url = "https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
            sizeBytes = 2_837_072_864L,
            sha256 = "be5d9a656a51922f24f1f09a759cebb694e1f5d9728bf0ef9f8c972c5a0b5ef2",
            contextSize = 32_768,
            quant = "Q4_K_M",
            decodeRuns = listOf(3.57, 3.46),
            tradeOff = "NVIDIA Open Model License (non-OSI), 4B class; the slowest measured here.",
            ramTier = "S",
            license = "NVIDIA Open Model License",
            osiApproved = false,
            arch = "dense 4B",
            jobFit = "slowest measured; offered for completeness",
            measuredOn = "Pixel 7",
        ),
    )

    val default: ModelEntry get() = byId(DEFAULT_ID)!!

    fun byId(id: String): ModelEntry? = entries.firstOrNull { it.id == id }

    fun byFileName(name: String): ModelEntry? = entries.firstOrNull { it.fileName == name }

    fun ofFamily(family: ModelFamily): List<ModelEntry> = entries.filter { it.family == family }

    /** The entry the RAM tier recommends inside [family]; null if that family
     * has nothing for the tier. */
    fun forTier(tierId: String, family: ModelFamily = ModelFamily.QWEN): ModelEntry? =
        ofFamily(family).firstOrNull { it.ramTier == tierId }
            // A 16 GB phone can still run the largest entry of a family that
            // only declares 12 GB; it is a recommendation, not a limit.
            ?: ofFamily(family).lastOrNull()

    fun path(context: Context, entry: ModelEntry): File =
        File(File(context.filesDir, "models"), entry.fileName)
}
