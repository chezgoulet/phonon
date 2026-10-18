package org.phononai.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

interface ApiHandler {
    fun healthJson(): String
    fun isReady(): Boolean
    fun logs(): String
    fun downloadJson(): String
    fun startDownload(url: String?): String

    /** Start a catalog download by entry id: the only path that carries a hash. */
    fun startCatalogDownload(id: String): String
    fun startServer(): String
    fun stopServer()
    fun settingsJson(): String
    fun updateSettings(body: String): String
    fun recordResponseBody(body: String)
    fun requestStarted(path: String)
    fun requestFinished(path: String)

    /** A serialized reload is in flight; control endpoints answer 503. */
    fun isRestarting(): Boolean

    /** The bind-time invariant failed; refuse to listen and report it. */
    fun refuseUnsafeExposure(message: String)
}

/**
 * One port for operators: /health, /logs, /model/download,
 * /server/{start,stop}, /settings and a transparent proxy for everything
 * under /v1/.
 *
 * The proxy deliberately speaks raw HTTP rather than re-implementing the
 * OpenAI schema. It relays chunked responses as they arrive (SSE streaming
 * works), and it fails with 503/502 instead of hanging when the backend is
 * not there.
 */
class ApiServer(
    private val settings: Settings,
    private val api: ApiHandler,
    private val bindAddress: String,
    private val port: Int,
    /** Key to check at bind time; the settings value is committed after bind. */
    private val bindKey: String? = null,
) {
    @Volatile private var running = false
    @Volatile private var stopped = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var retryThread: Thread? = null
    private val clients = Executors.newCachedThreadPool { r ->
        Thread(r, "phonon-http-client").apply { isDaemon = true }
    }

    /**
     * Bind synchronously without accepting. The caller closes the old
     * listener and opens this one inside the settings request, before its
     * response is written, so no client that observes the response can find
     * the port unbound. Any failure is reported to the caller rather than
     * being retried in the background.
     */
    fun bind(): Boolean {
        if (serverSocket != null) return true
        if (stopped) return false
        val socket = synchronized(settings.configLock) {
            if (stopped) return false
            val key = bindKey ?: settings.apiKey
            // Assert the invariant at the last possible moment, and hold the
            // settings lock across check+bind so a concurrent key change
            // cannot race the listener onto the LAN. The socket is not bound
            // before this check passes.
            if (!Settings.isLoopbackBind(bindAddress) && key.isBlank()) {
                val message = "bind_address requires api_key"
                Log.e(TAG, "refusing to bind $bindAddress:$port: $message")
                api.refuseUnsafeExposure(message)
                return false
            }
            // The port was just released by stop(). On a busy port the
            // kernel can take a moment to make it reusable, so retry briefly
            // instead of losing the settings change.
            var bound: ServerSocket? = null
            var last: Throwable? = null
            for (attempt in 0 until BIND_ATTEMPTS) {
                if (stopped) return false
                try {
                    bound = openSocket()
                    break
                } catch (t: Throwable) {
                    last = t
                    if (attempt + 1 < BIND_ATTEMPTS) {
                        try {
                            Thread.sleep(BIND_RETRY_MS)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
            if (bound == null) {
                Log.e(
                    TAG,
                    "could not bind $bindAddress:$port: ${last?.message} [$instanceTag]",
                )
                return false
            }
            // stop() may have won the race while the bind was in progress.
            if (stopped) {
                try {
                    bound.close()
                } catch (_: Throwable) {
                }
                return false
            }
            bound
        }
        serverSocket = socket
        Log.i(TAG, "API listening on $bindAddress:$port [$instanceTag]")
        return true
    }

    private val instanceTag: String
        get() = Integer.toHexString(System.identityHashCode(this))

    private fun openSocket(): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
        } catch (_: Throwable) {
        }
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), 50)
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            throw t
        }
        return socket
    }

    /** Start accepting on a socket that [bind] already opened. */
    fun startAccepting() {
        if (stopped || serverSocket == null) return
        running = true
        if (acceptThread?.isAlive == true) return
        acceptThread = thread(name = "phonon-api", isDaemon = true) { acceptLoop() }
    }

    /** Boot path: bind and accept, retrying if the address is not up yet. */
    fun start() {
        if (running || stopped) return
        running = true
        if (bind()) {
            startAccepting()
            return
        }
        retryThread = thread(name = "phonon-api-retry", isDaemon = true) {
            while (running && !stopped) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!running || stopped) return@thread
                if (bind()) {
                    startAccepting()
                    return@thread
                }
            }
        }
    }

    fun stop() {
        stopped = true
        running = false
        val closing = serverSocket
        serverSocket = null
        try {
            closing?.close()
            Log.i(TAG, "API stopped $bindAddress:$port [$instanceTag]")
        } catch (t: Throwable) {
            Log.w(TAG, "API stop failed $bindAddress:$port: ${t.message} [$instanceTag]")
        }
        acceptThread?.interrupt()
        acceptThread = null
        retryThread?.interrupt()
        retryThread = null
        // Not shutdownNow: the request that triggered a rebind is still
        // running on this instance and must be able to write its response.
        clients.shutdown()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = serverSocket ?: break
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running) Log.e(TAG, "accept failed: ${t.message}")
                break
            }
            try {
                clients.execute { handle(client) }
            } catch (_: Throwable) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val req = readRequest(input, output) ?: return
                route(req, output)
                output.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "request failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun route(req: Request, out: OutputStream) {
        // /health stays unauthenticated so a liveness probe works without the
        // key. Everything else on the operator port requires the key when one
        // is configured. With no key and loopback binding this is unchanged.
        if (req.path != "/health" && !authorized(req)) {
            respond(
                out, 401, "application/json; charset=utf-8",
                """{"error":"unauthorized"}""".toByteArray(),
                mapOf("WWW-Authenticate" to "Bearer"),
            )
            return
        }
        // A reload owns the listener for a moment while the backend is
        // replaced. Control requests must not race it: answer with a body and
        // a retry hint instead of disappearing with the old socket.
        if (isControlPath(req.path) && api.isRestarting()) {
            respond(
                out, 503, "application/json; charset=utf-8",
                """{"error":"server restarting","retry_after_s":1}""".toByteArray(),
                mapOf("Retry-After" to "1"),
            )
            return
        }
        when {
            req.method == "GET" && req.path == "/health" -> {
                val body = api.healthJson()
                val status = if (api.isReady()) 200 else 503
                respond(out, status, "application/json; charset=utf-8", body.toByteArray())
            }

            req.method == "GET" && req.path == "/logs" -> {
                respond(out, 200, "text/plain; charset=utf-8", api.logs().toByteArray())
            }

            req.method == "GET" && req.path == "/model/download" -> {
                respond(out, 200, "application/json; charset=utf-8", api.downloadJson().toByteArray())
            }

            req.method == "POST" && req.path == "/model/download" -> {
                val text = req.body.toString(Charsets.UTF_8)
                val url = try {
                    if (text.isBlank()) null else JSONObject(text).optString("url").ifBlank { null }
                } catch (t: Throwable) {
                    respond(
                        out, 400, "application/json",
                        """{"error":"invalid JSON body"}""".toByteArray(),
                    )
                    return
                }
                val id = try {
                    if (text.isBlank()) null else JSONObject(text).optString("id").ifBlank { null }
                } catch (t: Throwable) {
                    null
                }
                val result = if (id != null) api.startCatalogDownload(id) else api.startDownload(url)
                val status = if (hasError(result)) 409 else 202
                respond(out, status, "application/json; charset=utf-8", result.toByteArray())
            }

            req.method == "POST" && req.path == "/server/start" -> {
                val result = api.startServer()
                val status = if (hasError(result)) 409 else 202
                respond(out, status, "application/json; charset=utf-8", result.toByteArray())
            }

            req.method == "POST" && req.path == "/server/stop" -> {
                api.stopServer()
                respond(out, 202, "application/json", """{"accepted":true}""".toByteArray())
            }

            req.method == "GET" && req.path == "/settings" -> {
                respond(out, 200, "application/json; charset=utf-8", api.settingsJson().toByteArray())
            }

            req.method == "POST" && req.path == "/settings" -> {
                val result = api.updateSettings(req.body.toString(Charsets.UTF_8))
                val status = if (hasError(result)) 400 else 200
                respond(out, status, "application/json; charset=utf-8", result.toByteArray())
            }

            // Debug-only test instrument, not a feature. Asks the kernel to
            // drop the model file's clean page cache. Release builds fall
            // through to the 404 below because BuildConfig.DEBUG is false.
            req.method == "POST" && req.path == "/debug/evict" && BuildConfig.DEBUG -> {
                val (status, body) = evictModelCache()
                respond(out, status, "application/json; charset=utf-8", body.toByteArray())
            }

            req.path == "/v1" || req.path.startsWith("/v1/") -> {
                if (!api.isReady()) {
                    val body = JSONObject().apply {
                        put("error", "llama-server not ready")
                        put("state", JSONObject(api.healthJson()).optString("state"))
                    }.toString()
                    respond(out, 503, "application/json; charset=utf-8", body.toByteArray())
                } else {
                    api.requestStarted(req.path)
                    try {
                        proxy(req, out)
                    } finally {
                        api.requestFinished(req.path)
                    }
                }
            }

            else -> respond(
                out, 404, "application/json; charset=utf-8",
                """{"error":"not found"}""".toByteArray(),
            )
        }
    }

    /** Routes that change or report operator state; they answer 503 mid-reload. */
    private fun isControlPath(path: String): Boolean =
        path == "/settings" || path == "/logs" ||
            path.startsWith("/model/") || path.startsWith("/server/") ||
            path.startsWith("/debug/")

    private fun authorized(req: Request): Boolean {
        val key = settings.apiKey
        if (key.isBlank()) return true
        val header = req.headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = header.substring(7).trim()
        if (presented.isEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            key.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Test instrument for the keep-warm experiment: ask the kernel to drop the
     * clean, file-backed pages of the model the server is using.
     *
     * The call is deliberately fadvise(DONTNEED) and not munmap: it must not
     * disturb the server. Note that Linux skips pages another process has
     * mapped, so against a live mmap this is a no-op for the weights (measured;
     * see docs/app.md). It does drop the unmapped part of the file.
     */
    private fun evictModelCache(): Pair<Int, String> {
        val path = settings.modelPath
        return try {
            val errno = DebugEvict.nativeFadviseDontNeed(path)
            if (errno != 0) throw IOException("posix_fadvise failed: errno $errno")
            Log.i(TAG, "debug evict: posix_fadvise(DONTNEED) accepted for $path")
            200 to JSONObject().apply {
                put("evicted", true)
                put("path", path)
                put("bytes", File(path).length())
            }.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "debug evict failed: ${t.javaClass.simpleName}: ${t.message}")
            500 to JSONObject().apply {
                put("evicted", false)
                put("path", path)
                put("error", "${t.javaClass.simpleName}: ${t.message}")
            }.toString()
        }
    }

    /** The JSON always carries an `error` key; only a non-null value is a failure. */
    private fun hasError(json: String): Boolean = try {
        val obj = JSONObject(json)
        obj.has("error") && !obj.isNull("error")
    } catch (t: Throwable) {
        true
    }

    private fun proxy(req: Request, out: OutputStream) {
        val socket = Socket()
        var wroteHeaders = false
        val backendHost = settings.backendHost()
        try {
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName(backendHost), settings.backendPort), 3_000)
            } catch (t: Throwable) {
                respond(
                    out, 502, "application/json; charset=utf-8",
                    ("""{"error":"cannot reach llama-server on """ +
                        backendHost + ":" + settings.backendPort + """"}""").toByteArray(),
                )
                return
            }
            socket.tcpNoDelay = true
            socket.soTimeout = 600_000
            val bin = BufferedInputStream(socket.getInputStream())
            val bout = BufferedOutputStream(socket.getOutputStream())

            val target = if (req.query.isBlank()) req.path else "${req.path}?${req.query}"
            val headers = LinkedHashMap<String, String>()
            for ((k, v) in req.headers) {
                if (k in HOP_BY_HOP) continue
                headers[k] = v
            }
            headers["host"] = "$backendHost:${settings.backendPort}"
            headers["connection"] = "close"
            headers["content-length"] = req.body.size.toString()
            if (settings.apiKey.isNotBlank() && !req.headers.containsKey("authorization")) {
                headers["authorization"] = "Bearer ${settings.apiKey}"
            }

            val head = StringBuilder()
            head.append(req.method).append(' ').append(target).append(" HTTP/1.1\r\n")
            for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
            head.append("\r\n")
            bout.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (req.body.isNotEmpty()) bout.write(req.body)
            bout.flush()

            val statusLine = readLine(bin)
            if (statusLine == null) {
                respond(out, 502, "application/json", """{"error":"empty response"}""".toByteArray())
                return
            }
            val respHeaders = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(bin) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                respHeaders[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            val responseHead = StringBuilder()
            responseHead.append(statusLine).append("\r\n")
            for ((k, v) in respHeaders) {
                if (k == "connection" || k == "keep-alive") continue
                responseHead.append(k).append(": ").append(v).append("\r\n")
            }
            responseHead.append("connection: close\r\n\r\n")
            out.write(responseHead.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            wroteHeaders = true

            val contentType = respHeaders["content-type"] ?: ""
            val capture = if (contentType.startsWith("application/json")) {
                ByteArrayOutputStream()
            } else {
                null
            }
            val transferEncoding = respHeaders["transfer-encoding"]?.lowercase() ?: ""
            val contentLength = respHeaders["content-length"]?.toLongOrNull()
            when {
                transferEncoding.contains("chunked") -> relayChunked(bin, out, capture)
                contentLength != null -> copyExact(bin, out, contentLength, capture)
                else -> copyUntilEof(bin, out, capture)
            }
            if (capture != null && capture.size() > 0) {
                api.recordResponseBody(capture.toString(Charsets.UTF_8.name()))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "proxy failed: ${t.javaClass.simpleName}: ${t.message}")
            if (!wroteHeaders) {
                try {
                    respond(
                        out, 502, "application/json; charset=utf-8",
                        """{"error":"proxy failed before response headers"}""".toByteArray(),
                    )
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun relayChunked(
        input: BufferedInputStream,
        out: OutputStream,
        capture: ByteArrayOutputStream?,
    ) {
        val crlf = "\r\n".toByteArray(Charsets.ISO_8859_1)
        while (true) {
            val line = readLine(input) ?: throw EOFException("truncated chunked response")
            out.write(line.toByteArray(Charsets.ISO_8859_1))
            out.write(crlf)
            out.flush()
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("bad chunk size: $line")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    out.write(trailer.toByteArray(Charsets.ISO_8859_1))
                    out.write(crlf)
                    if (trailer.isEmpty()) break
                }
                out.flush()
                return
            }
            copyExact(input, out, size.toLong(), capture)
            val end = ByteArray(2)
            readFully(input, end)
            out.write(end)
            out.flush()
        }
    }

    private fun copyExact(
        input: InputStream,
        out: OutputStream,
        count: Long,
        capture: ByteArrayOutputStream?,
    ) {
        val buffer = ByteArray(16 * 1024)
        var remaining = count
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) throw EOFException("truncated body: $remaining bytes missing")
            out.write(buffer, 0, n)
            out.flush()
            capture?.let { if (it.size() + n <= MAX_CAPTURE) it.write(buffer, 0, n) }
            remaining -= n
        }
    }

    private fun copyUntilEof(
        input: InputStream,
        out: OutputStream,
        capture: ByteArrayOutputStream?,
    ) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            out.write(buffer, 0, n)
            out.flush()
            capture?.let { if (it.size() + n <= MAX_CAPTURE) it.write(buffer, 0, n) }
        }
    }

    private fun readRequest(input: BufferedInputStream, out: OutputStream): Request? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("bad request line")
        val method = parts[0]
        val rawTarget = parts[1]
        var headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }
        if (headers["expect"]?.lowercase()?.contains("100-continue") == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val body = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true ->
                readChunkedBody(input)
            else -> {
                val len = headers["content-length"]?.toLongOrNull() ?: 0L
                if (len > MAX_REQUEST_BODY) throw IOException("request body too large")
                readBytes(input, len)
            }
        }
        val q = rawTarget.indexOf('?')
        val path = if (q < 0) rawTarget else rawTarget.substring(0, q)
        val query = if (q < 0) "" else rawTarget.substring(q + 1)
        return Request(method, path, query, headers, body)
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated chunked request")
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("bad chunk size: $line")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                return out.toByteArray()
            }
            if (out.size() + size > MAX_REQUEST_BODY) throw IOException("request body too large")
            out.write(readBytes(input, size.toLong()))
            readLine(input)
        }
    }

    private fun readBytes(input: InputStream, count: Long): ByteArray {
        if (count <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(count.toInt().coerceAtMost(1 shl 20))
        val buffer = ByteArray(16 * 1024)
        var remaining = count
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) throw EOFException("truncated request body")
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) {
                val s = out.toString(Charsets.ISO_8859_1.name())
                return s.removeSuffix("\r")
            }
            out.write(b)
            if (out.size() > 64 * 1024) throw IOException("header line too long")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) throw EOFException("unexpected end of stream")
            off += n
        }
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            for ((k, v) in extraHeaders) append(k).append(": ").append(v).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private companion object {
        const val TAG = "Phonon"
        const val MAX_REQUEST_BODY = 64L * 1024 * 1024
        const val MAX_CAPTURE = 4 * 1024 * 1024
        const val BIND_ATTEMPTS = 20
        const val BIND_RETRY_MS = 25L
        val HOP_BY_HOP = setOf(
            "host", "connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "expect", "content-length",
        )
    }
}
