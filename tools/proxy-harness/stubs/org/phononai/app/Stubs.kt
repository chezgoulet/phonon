package org.phononai.app

// The smallest surface ApiServer.kt needs to compile and run on a desktop JVM.
// Nothing here is a model of the app's behaviour beyond what the proxy reads.

object BuildConfig {
    const val DEBUG = false
}

object DebugEvict {
    fun nativeFadviseDontNeed(path: String): Int = 0
}

class Settings(val apiKey: String) {
    val configLock = Any()
    val rateLimitPerMin = 600
    val backendPort = (System.getenv("BACKEND_PORT") ?: "9090").toInt()
    val modelPath = "/dev/null"

    fun backendHost() = "127.0.0.1"

    companion object {
        const val RATE_LIMIT_BURST = 100

        fun isLoopbackBind(v: String) = v.startsWith("127.")
    }
}

class StubHandler : ApiHandler {
    override fun healthJson() = """{"state":"ready"}"""
    override fun isReady() = true
    override fun logs() = ""
    override fun downloadJson() = "{}"
    override fun startDownload(url: String?) = "{}"
    override fun startCatalogDownload(id: String) = "{}"
    override fun startServer() = "{}"
    override fun stopServer() {}
    override fun settingsJson() = "{}"
    override fun updateSettings(body: String) = "{}"
    override fun recordResponseBody(body: String) {}
    override fun requestStarted(path: String) {}
    override fun requestFinished(path: String) {}
    override fun isRestarting() = false
    override fun refuseUnsafeExposure(message: String) {}
}

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toInt()
    val server = ApiServer(Settings(System.getenv("API_KEY") ?: "secret"), StubHandler(), "127.0.0.1", port)
    server.start()
    System.err.println("listening on $port")
    Thread.sleep(Long.MAX_VALUE)
}
