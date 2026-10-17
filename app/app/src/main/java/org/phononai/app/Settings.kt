package org.phononai.app

import android.content.Context
import java.io.File

/**
 * All knobs live in one SharedPreferences file so they can be changed from the
 * activity and from the loopback API without the service holding stale values.
 * Getters read the store every time; there is no cache to invalidate.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val filesDir = context.filesDir

    fun defaultModelPath(): String =
        File(File(filesDir, "models"), DEFAULT_MODEL_NAME).absolutePath

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, null) ?: defaultModelPath()
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()

    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, 2048)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 4)
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

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
        get() = prefs.getString(KEY_DOWNLOAD_URL, DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL
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

    fun asJson(): String = org.json.JSONObject().apply {
        put("model_path", modelPath)
        put("context", contextSize)
        put("threads", threads)
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
        const val DEFAULT_MODEL_URL = "http://127.0.0.1:9000/gemma-4-E2B-it-Q4_0.gguf"
        const val DEFAULT_ALIAS = "gemma-4-e2b"
        const val LOAD_MODE_MMAP = "mmap"
        val LOAD_MODES = setOf(LOAD_MODE_MMAP, "mlock", "none")
        const val MIN_KEEP_WARM_INTERVAL_S = 15
        const val DEFAULT_KEEP_WARM_INTERVAL_S = 45

        private const val NAME = "phonon"
        private const val KEY_MODEL_PATH = "model_path"
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
    }
}
