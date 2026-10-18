package org.phononai.app

import org.json.JSONObject

/**
 * Progress of the single model download. Shared by the service and the
 * activity (same process) and exposed read-only over GET /model/download.
 */
object DownloadState {
    @Volatile var active: Boolean = false
        private set
    @Volatile var done: Boolean = false
        private set
    @Volatile var bytes: Long = 0
        private set
    @Volatile var total: Long = -1
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var url: String = ""
        private set
    @Volatile var dest: String = ""
        private set
    @Volatile var startedAtMs: Long = 0
        private set

    /** Atomically claims the download slot. False when one is already running. */
    @Synchronized
    fun beginIfIdle(url: String, dest: String): Boolean {
        if (active) return false
        this.url = url
        this.dest = dest
        active = true
        done = false
        bytes = 0
        total = -1
        error = null
        startedAtMs = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun begin(url: String, dest: String) {
        this.url = url
        this.dest = dest
        active = true
        done = false
        bytes = 0
        total = -1
        error = null
        startedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun progress(bytes: Long, total: Long) {
        this.bytes = bytes
        if (total >= 0) this.total = total
    }

    @Synchronized
    fun success(dest: String) {
        active = false
        done = true
        error = null
        this.dest = dest
        val len = java.io.File(dest).length()
        if (len > 0) {
            bytes = len
            total = len
        }
    }

    @Synchronized
    fun failure(message: String) {
        active = false
        done = false
        error = message
    }

    @Synchronized
    fun toJson(): String = JSONObject().apply {
        put("active", active)
        put("done", done)
        put("bytes", bytes)
        put("total", if (total >= 0) total else JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("url", url)
        put("dest", dest)
        put("started_at_ms", if (startedAtMs > 0) startedAtMs else JSONObject.NULL)
    }.toString()
}
