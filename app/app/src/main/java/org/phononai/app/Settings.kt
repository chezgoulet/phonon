package org.phononai.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * All knobs live in one SharedPreferences file so they can be changed from the
 * activity and from the loopback API without the service holding stale values.
 * Getters read the store every time; there is no cache to invalidate.
 */
class Settings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val filesDir = appContext.filesDir

    /**
     * Serialises settings mutation with the bind-time exposure check, so the
     * operator port cannot be bound off-loopback while a key change is being
     * committed. Reentrant: callers that already hold it may bind or reload.
     */
    val configLock = Any()

    /** The tier this device falls into, recomputed from physical memory. */
    val hardware: DeviceProfile get() = HardwareProfile.detect(appContext)

    fun defaultModelPath(): String =
        File(File(filesDir, "models"), hardware.tier.modelFile).absolutePath

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, null) ?: defaultModelPath()
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()

    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, hardware.tier.contextSize)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    var threads: Int
        get() = prefs.getInt(KEY_THREADS, hardware.tier.threads)
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    /**
     * Set by the Settings screen's Save action. Auto-configuration never
     * replaces a value the operator has chosen, including after a restart.
     */
    var tunedByUser: Boolean
        get() = prefs.getBoolean(KEY_TUNED_BY_USER, false)
        set(value) = prefs.edit().putBoolean(KEY_TUNED_BY_USER, value).apply()

    /**
     * Address both the app API and llama-server bind to. Safe values are
     * loopback, the wildcard address, or an IPv4 address assigned to a local
     * interface. Anything else is rejected at the settings boundary.
     */
    var bindAddress: String
        get() = prefs.getString(KEY_BIND_ADDRESS, DEFAULT_BIND_ADDRESS) ?: DEFAULT_BIND_ADDRESS
        set(value) = prefs.edit().putString(KEY_BIND_ADDRESS, value).apply()

    /**
     * The one rule that must hold before either listener is bound off-loopback.
     * Null means the configuration is safe to start.
     */
    val exposureError: String?
        get() = if (!isLoopbackBind(bindAddress) && apiKey.isBlank()) {
            "bind_address requires api_key"
        } else {
            null
        }

    val exposedOffHost: Boolean
        get() = !isLoopbackBind(bindAddress)

    /** llama-server binds [bindAddress]; 0.0.0.0 and loopback are reached via 127.0.0.1. */
    fun backendHost(): String =
        if (bindAddress == BIND_ANY || isLoopbackBind(bindAddress)) "127.0.0.1" else bindAddress

    /** Port the app's own API listens on (health, /v1 proxy, control routes). */
    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** Internal port llama-server binds to; the app proxies /v1 to it. */
    var backendPort: Int
        get() = prefs.getInt(KEY_BACKEND_PORT, 8081)
        set(value) = prefs.edit().putInt(KEY_BACKEND_PORT, value).apply()

    var reasoning: Boolean
        get() = prefs.getBoolean(KEY_REASONING, false)
        set(value) = prefs.edit().putBoolean(KEY_REASONING, value).apply()

    var thermalStopC: Float
        get() = prefs.getFloat(KEY_THERMAL_STOP, 45f)
        set(value) = prefs.edit().putFloat(KEY_THERMAL_STOP, value).apply()

    var thermalResumeC: Float
        get() = prefs.getFloat(KEY_THERMAL_RESUME, 40f)
        set(value) = prefs.edit().putFloat(KEY_THERMAL_RESUME, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var alias: String
        get() = prefs.getString(KEY_ALIAS, DEFAULT_ALIAS) ?: DEFAULT_ALIAS
        set(value) = prefs.edit().putString(KEY_ALIAS, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var downloadUrl: String
        get() {
            val stored = prefs.getString(KEY_DOWNLOAD_URL, null)
            // Devices that ran an older build have the development loopback URL
            // persisted. Read it as "unset" so upgrades get the empty default
            // and the hint, without rewriting storage behind the service.
            if (stored == LEGACY_DEV_MODEL_URL) return DEFAULT_MODEL_URL
            return stored ?: DEFAULT_MODEL_URL
        }
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_URL, value).apply()

    /**
     * Send a tiny completion while idle so the kernel does not evict the
     * model's file-backed pages. See "Keeping the model warm" in docs/app.md.
     */
    var keepWarm: Boolean
        get() = prefs.getBoolean(KEY_KEEP_WARM, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_WARM, value).apply()

    var keepWarmIntervalS: Int
        get() = prefs.getInt(KEY_KEEP_WARM_INTERVAL, 45)
        set(value) = prefs.edit().putInt(KEY_KEEP_WARM_INTERVAL, value).apply()

    /** llama.cpp -lm: mmap (default), mlock or none. */
    var loadMode: String
        get() = prefs.getString(KEY_LOAD_MODE, LOAD_MODE_MMAP) ?: LOAD_MODE_MMAP
        set(value) = prefs.edit().putString(KEY_LOAD_MODE, value).apply()

    /**
     * Apply the RAM-tier defaults once, on the first start after install or
     * data clear. A value the operator saved from the Settings screen is
     * never overwritten: [tunedByUser] is set by that screen's Save action.
     */
    fun applyAutoConfigIfNeeded() {
        if (prefs.getBoolean(KEY_AUTO_CONFIG_DONE, false)) return
        if (!tunedByUser) {
            val tier = hardware.tier
            modelPath = File(File(filesDir, "models"), tier.modelFile).absolutePath
            contextSize = tier.contextSize
            threads = tier.threads
            alias = tier.alias
        }
        prefs.edit().putBoolean(KEY_AUTO_CONFIG_DONE, true).apply()
    }

    /** Newest first, capped at [MAX_BENCHMARK_HISTORY]. */
    val benchmarkHistory: List<JSONObject>
        get() {
            val raw = prefs.getString(KEY_BENCHMARK_HISTORY, null) ?: return emptyList()
            return try {
                val array = org.json.JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    fun appendBenchmark(result: JSONObject) {
        val trimmed = (listOf(result) + benchmarkHistory).take(MAX_BENCHMARK_HISTORY)
        val array = org.json.JSONArray()
        trimmed.forEach { array.put(it) }
        prefs.edit().putString(KEY_BENCHMARK_HISTORY, array.toString()).apply()
    }

    fun asJson(): String = org.json.JSONObject().apply {
        put("model_path", modelPath)
        put("context", contextSize)
        put("threads", threads)
        put("bind_address", bindAddress)
        put("port", port)
        put("backend_port", backendPort)
        put("reasoning", reasoning)
        put("thermal_stop_c", thermalStopC.toDouble())
        put("thermal_resume_c", thermalResumeC.toDouble())
        put("api_key_set", apiKey.isNotBlank())
        put("alias", alias)
        put("auto_start", autoStart)
        put("download_url", downloadUrl)
        put("keep_warm", keepWarm)
        put("keep_warm_interval_s", keepWarmIntervalS)
        put("load_mode", loadMode)
    }.toString()

    companion object {
        const val DEFAULT_MODEL_NAME = "gemma-4-E2B-it-Q4_0.gguf"

        /**
         * No model host is bundled: a development loopback URL read as a broken
         * default. The download screen asks for a URL instead.
         */
        const val DEFAULT_MODEL_URL = ""

        /** Only value written by builds before the default was removed. */
        private const val LEGACY_DEV_MODEL_URL = "http://127.0.0.1:9000/gemma-4-E2B-it-Q4_0.gguf"
        const val DEFAULT_ALIAS = "gemma-4-e2b"
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val BIND_ANY = "0.0.0.0"
        const val LOAD_MODE_MMAP = "mmap"
        val LOAD_MODES = setOf(LOAD_MODE_MMAP, "mlock", "none")
        const val MIN_KEEP_WARM_INTERVAL_S = 15
        const val DEFAULT_KEEP_WARM_INTERVAL_S = 45
        const val MAX_CONTEXT = 65_536
        const val MAX_BENCHMARK_HISTORY = 10

        fun isLoopbackBind(value: String): Boolean {
            val v = value.trim()
            return v == DEFAULT_BIND_ADDRESS || v.startsWith("127.")
        }

        /**
         * Validate a proposed bind address. Returns an error message, or null
         * when the value is 127.0.0.1, 0.0.0.0, or an IPv4 address currently
         * assigned to a local interface.
         */
        fun validateBindAddress(value: String): String? {
            val v = value.trim()
            if (!isIPv4(v)) return "bind_address must be an IPv4 address"
            if (v == DEFAULT_BIND_ADDRESS || v == BIND_ANY) return null
            if (!isAssignedToLocalInterface(v)) {
                return "bind_address is not a local IPv4 address: $v"
            }
            return null
        }

        private fun isIPv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    part.toInt() in 0..255
            }
        }

        private fun isAssignedToLocalInterface(value: String): Boolean = try {
            val target = InetAddress.getByName(value)
            if (target !is Inet4Address) {
                false
            } else {
                NetworkInterface.getNetworkInterfaces()?.toList()?.any { nif ->
                    nif.inetAddresses.toList().any { address ->
                        address is Inet4Address && address.hostAddress == target.hostAddress
                    }
                } ?: false
            }
        } catch (_: Throwable) {
            false
        }

        private const val NAME = "phonon"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_BIND_ADDRESS = "bind_address"
        private const val KEY_CONTEXT = "context"
        private const val KEY_THREADS = "threads"
        private const val KEY_PORT = "port"
        private const val KEY_BACKEND_PORT = "backend_port"
        private const val KEY_REASONING = "reasoning"
        private const val KEY_THERMAL_STOP = "thermal_stop_c"
        private const val KEY_THERMAL_RESUME = "thermal_resume_c"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ALIAS = "alias"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_KEEP_WARM = "keep_warm"
        private const val KEY_KEEP_WARM_INTERVAL = "keep_warm_interval_s"
        private const val KEY_LOAD_MODE = "load_mode"
        private const val KEY_TUNED_BY_USER = "tuned_by_user"
        private const val KEY_AUTO_CONFIG_DONE = "auto_config_done"
        private const val KEY_BENCHMARK_HISTORY = "benchmark_history"
    }
}
