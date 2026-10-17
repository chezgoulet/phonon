package org.phononai.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

interface ApiHandler {
    fun healthJson(): String
    fun isReady(): Boolean
    fun logs(): String
    fun downloadJson(): String
    fun startDownload(url: String?): String
    fun startServer()
    fun stopServer()
    fun settingsJson(): String
    fun updateSettings(body: String): String
    fun recordResponseBody(body: String)
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
) {
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = Executors.newCachedThreadPool { r ->
        Thread(r, "phonon-http-client").apply { isDaemon = true }
    }

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "phonon-api", isDaemon = true) {
            while (running) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), settings.port))
                    serverSocket = ss
                    Log.i(TAG, "API listening on 127.0.0.1:${settings.port}")
                    while (running && !ss.isClosed) {
                        val client = try {
                            ss.accept()
                        } catch (t: Throwable) {
                            if (running) Log.e(TAG, "accept failed: ${t.message}")
                            break
                        }
                        clients.execute { handle(client) }
                    }
                } catch (t: Throwable) {
                    if (running) Log.e(TAG, "could not bind 127.0.0.1:${settings.port}: ${t.message}")
                }
                if (running) {
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        clients.shutdownNow()
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
                val result = api.startDownload(url)
                val status = if (result.contains("\"error\"")) 409 else 202
                respond(out, status, "application/json; charset=utf-8", result.toByteArray())
            }

            req.method == "POST" && req.path == "/server/start" -> {
                api.startServer()
                respond(out, 202, "application/json", """{"accepted":true}""".toByteArray())
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
                val status = if (result.contains("\"error\"")) 400 else 200
                respond(out, status, "application/json; charset=utf-8", result.toByteArray())
            }

            req.path == "/v1" || req.path.startsWith("/v1/") -> {
                if (!api.isReady()) {
                    val body = JSONObject().apply {
                        put("error", "llama-server not ready")
                        put("state", JSONObject(api.healthJson()).optString("state"))
                    }.toString()
                    respond(out, 503, "application/json; charset=utf-8", body.toByteArray())
                } else {
                    proxy(req, out)
                }
            }

            else -> respond(
                out, 404, "application/json; charset=utf-8",
                """{"error":"not found"}""".toByteArray(),
            )
        }
    }

    private fun proxy(req: Request, out: OutputStream) {
        val socket = Socket()
        var wroteHeaders = false
        try {
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), settings.backendPort), 3_000)
            } catch (t: Throwable) {
                respond(
                    out, 502, "application/json; charset=utf-8",
                    ("""{"error":"cannot reach llama-server on 127.0.0.1:""" +
                        settings.backendPort + """"}""").toByteArray(),
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
            headers["host"] = "127.0.0.1:${settings.backendPort}"
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

    private fun respond(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
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
        404 -> "Not Found"
        409 -> "Conflict"
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
        val HOP_BY_HOP = setOf(
            "host", "connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "expect", "content-length",
        )
    }
}
