package org.phononai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
 * One downloadable model. Everything the Models screen shows comes from
 * `app/app/src/main/assets/catalog.json`; there are no model names, URLs or
 * measured rates anywhere else in the UI.
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
    /** Vision projector, downloaded alongside the model. Blank means text-only. */
    val mmprojFileName: String = "",
    val mmprojUrl: String = "",
    val mmprojSizeBytes: Long = 0,
    val mmprojSha256: String = "",
) {
    val measured: Boolean get() = decodeRuns.isNotEmpty()

    /** `9.20 / 8.84 tok/s`; blank when unmeasured. */
    val decodeLabel: String
        get() = if (decodeRuns.isEmpty()) ""
        else decodeRuns.joinToString(" / ") { "%.2f".format(it) } + " tok/s"

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
 * The catalog, read from `app/app/src/main/assets/catalog.json`.
 *
 * The JSON file is the source of truth. Sizes and hashes are the values Hugging
 * Face declares for the pinned files, and every URL was checked with a HEAD
 * request on 2026-10-17 and returned 200 with that Content-Length; the measured
 * rates are the project's own device runs. See docs/models.md for provenance.
 *
 * A catalog packaged in an APK is meant to be complete, so a malformed entry is
 * a build defect: [loadFrom] throws rather than degrading to an empty picker.
 *
 * [load] must run before anything reads the catalog. `PhononApplication` calls
 * it at process start, so every other component sees a loaded catalog.
 */
object ModelCatalog {

    private const val ASSET = "catalog.json"

    private class Catalog(
        val measuredDate: String,
        val defaultId: String,
        val entries: List<ModelEntry>,
    )

    @Volatile
    private var loaded: Catalog? = null

    /** Read and parse the packaged catalog. Safe to call more than once. */
    fun load(context: Context) {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        loadFrom(text)
    }

    /**
     * Parse a catalog document. Pure JVM — no Android types — so the unit test
     * exercises exactly the parser the app uses.
     */
    fun loadFrom(json: String) {
        val root = JSONObject(json)
        val array: JSONArray = root.getJSONArray("models")
        val entries = ArrayList<ModelEntry>(array.length())
        for (i in 0 until array.length()) {
            entries += entryFrom(array.getJSONObject(i))
        }
        val seen = HashSet<String>()
        for (entry in entries) {
            require(seen.add(entry.id)) { "duplicate catalog id: ${entry.id}" }
            require(entry.sizeBytes > 0) { "${entry.id}: sizeBytes must be positive" }
            require(entry.sha256.isNotBlank()) { "${entry.id}: sha256 is required" }
        }
        val defaultId = root.getString("default_id")
        require(entries.any { it.id == defaultId }) {
            "default_id $defaultId names no catalog entry"
        }
        loaded = Catalog(
            measuredDate = root.getString("measured_date"),
            defaultId = defaultId,
            entries = entries,
        )
    }

    private fun entryFrom(o: JSONObject): ModelEntry {
        val familyKey = o.getString("family")
        val family = ModelFamily.values().firstOrNull { it.key == familyKey }
            ?: error("unknown model family in catalog: $familyKey")
        val runs = o.getJSONArray("decodeRuns")
        val decodeRuns = ArrayList<Double>(runs.length())
        for (i in 0 until runs.length()) decodeRuns += runs.getDouble(i)
        return ModelEntry(
            id = o.getString("id"),
            family = family,
            displayName = o.getString("displayName"),
            fileName = o.getString("fileName"),
            url = o.getString("url"),
            sizeBytes = o.getLong("sizeBytes"),
            sha256 = o.getString("sha256"),
            contextSize = o.getInt("contextSize"),
            quant = o.getString("quant"),
            decodeRuns = decodeRuns,
            tradeOff = o.getString("tradeOff"),
            ramTier = o.getString("ramTier"),
            multimodal = o.optBoolean("multimodal", false),
            license = o.optString("license", ""),
            osiApproved = o.optBoolean("osiApproved", true),
            arch = o.optString("arch", ""),
            jobFit = o.optString("jobFit", ""),
            measuredOn = o.optString("measuredOn", ""),
            vendorClaim = o.optString("vendorClaim", ""),
            source = o.optString("source", ""),
            mmprojFileName = o.optString("mmprojFileName", ""),
            mmprojUrl = o.optString("mmprojUrl", ""),
            mmprojSizeBytes = o.optLong("mmprojSizeBytes", 0),
            mmprojSha256 = o.optString("mmprojSha256", ""),
        )
    }

    private fun catalog(): Catalog =
        loaded ?: error("ModelCatalog.load(context) has not run; PhononApplication does this")

    /** Date of the device measurements, not of the catalog itself. */
    val MEASURED_DATE: String get() = catalog().measuredDate

    val DEFAULT_ID: String get() = catalog().defaultId

    val entries: List<ModelEntry> get() = catalog().entries

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
