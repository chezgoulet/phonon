package org.phononai.app

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The Ollama-compatible HTTP surface, kept free of Android types.
 *
 * [ApiServer] hands every `/api/` request here. Nothing in this class needs a
 * `Context`, which is what lets the exact code Home Assistant talks to run
 * against a real llama-server on the JVM — see `OllamaShimServer` in the test
 * sources and `docs/home-assistant.md`.
 *
 * One served model, no pull, no unload: the route set is deliberately the one
 * HA's built-in integration uses, plus `/api/ps` so a client that asks "what is
 * loaded" gets an answer instead of a 404.
 */
class OllamaApi(
    private val backendHost: String,
    private val backendPort: Int,
    private val servedModel: () -> OllamaCompat.ServedModel,
    private val ready: () -> Boolean,
    private val onRequestStarted: (String) -> Unit = {},
    private val onRequestFinished: (String) -> Unit = {},
) {

    /** Returns false when [path] is not one of ours, so the caller keeps routing. */
    fun handle(method: String, path: String, body: ByteArray, out: OutputStream): Boolean {
        when {
            method == "GET" && path == "/api/version" -> respond(
                out, 200, OllamaCompat.versionJson(),
            )
            method == "GET" && path == "/api/tags" -> respond(
                out, 200, OllamaCompat.tagsJson(servedModel()),
            )
            method == "GET" && path == "/api/ps" -> respond(
                out, 200, OllamaCompat.psJson(servedModel()),
            )
            method == "POST" && path == "/api/show" -> respond(
                out, 200, OllamaCompat.showJson(servedModel()),
            )
            // Refusing is the honest answer: the weights are already on the
            // device. A pull would either lie or fail halfway.
            path == "/api/pull" -> respond(out, 400, OllamaCompat.pullRefused())
            method == "POST" && path == "/api/chat" -> chat(body, out)
            else -> return false
        }
        return true
    }

    private fun chat(body: ByteArray, out: OutputStream) {
        val request = try {
            JSONObject(body.toString(Charsets.UTF_8))
        } catch (_: Throwable) {
            respond(out, 400, OllamaCompat.errorJson("invalid JSON body"))
            return
        }
        if (!ready()) {
            respond(out, 503, OllamaCompat.errorJson("llama-server not ready"))
            return
        }
        val model = servedModel()
        val stream = if (request.has("stream")) request.optBoolean("stream", true) else true
        val payload = OllamaCompat.toOpenAiChatRequest(
            request,
            model.name,
        ).toString().toByteArray(Charsets.UTF_8)
        onRequestStarted("/api/chat")
        try {
            relay(payload, model, stream, out)
        } finally {
            onRequestFinished("/api/chat")
        }
    }

    private fun relay(
        payload: ByteArray,
        model: OllamaCompat.ServedModel,
        stream: Boolean,
        out: OutputStream,
    ) {
        val socket = Socket()
        val startedAtMs = System.currentTimeMillis()
        try {
            socket.connect(InetSocketAddress(InetAddress.getByName(backendHost), backendPort), 3_000)
            socket.tcpNoDelay = true
            // Prefill silence is normal on this hardware; the proxy uses the
            // same budget so this route is never the component that gives up.
            socket.soTimeout = BACKEND_READ_TIMEOUT_MS
            val bin = BufferedInputStream(socket.getInputStream())
            val bout = BufferedOutputStream(socket.getOutputStream())
            val head = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: $backendHost:$backendPort\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            bout.write(head.toByteArray(Charsets.ISO_8859_1))
            bout.write(payload)
            bout.flush()

            val statusLine = readLine(bin) ?: throw IOException("empty backend response")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 502
            val headers = readHeaders(bin)
            if (status != 200) {
                val text = readBody(bin, headers).toString(Charsets.UTF_8)
                val message = try {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                } catch (_: Throwable) {
                    null
                }
                respond(
                    out, 502,
                    OllamaCompat.errorJson(
                        message?.takeIf { it.isNotBlank() } ?: "backend returned $status",
                    ),
                )
                return
            }

            if (!stream) {
                val text = readBody(bin, headers).toString(Charsets.UTF_8)
                respond(
                    out, 200,
                    OllamaCompat.openAiResponseToOllama(
                        JSONObject(text), model.tagged,
                        System.currentTimeMillis() - startedAtMs,
                    ),
                )
                return
            }

            // NDJSON, chunked: HA's client reads a line at a time.
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            out.flush()
            val translator = OllamaCompat.StreamTranslator(model.tagged)
            val reader = BufferedReader(InputStreamReader(bin, Charsets.UTF_8))
            OllamaCompat.pumpNdjson(reader, translator) { line -> writeChunk(out, line) }
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeChunk(out: OutputStream, line: String) {
        // A newline inside the object would split it; the translator never
        // emits one, and this asserts the framing is one object per chunk.
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        out.write(Integer.toHexString(payload.size).toByteArray(Charsets.ISO_8859_1))
        out.write(CRLF)
        out.write(payload)
        out.write(CRLF)
        out.flush()
    }

    private fun readHeaders(bin: BufferedInputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(bin) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return headers
    }

    private fun readBody(bin: BufferedInputStream, headers: Map<String, String>): ByteArray {
        val collected = ByteArrayOutputStream()
        val transferEncoding = headers["transfer-encoding"]?.lowercase() ?: ""
        val length = headers["content-length"]?.toLongOrNull()
        when {
            transferEncoding.contains("chunked") -> {
                while (true) {
                    val line = readLine(bin) ?: throw EOFException("truncated chunked response")
                    val size = line.substringBefore(';').trim().toIntOrNull(16)
                        ?: throw IOException("bad chunk size: $line")
                    if (size == 0) {
                        while (true) {
                            val trailer = readLine(bin) ?: break
                            if (trailer.isEmpty()) break
                        }
                        break
                    }
                    collected.write(readBytes(bin, size.toLong()))
                    readLine(bin)
                }
            }
            length != null -> if (length > 0) collected.write(readBytes(bin, length))
            else -> {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = bin.read(buffer)
                    if (n < 0) break
                    collected.write(buffer, 0, n)
                }
            }
        }
        return collected.toByteArray()
    }

    private fun readBytes(bin: BufferedInputStream, count: Long): ByteArray {
        val out = ByteArrayOutputStream(count.toInt().coerceAtLeast(1))
        val buffer = ByteArray(16 * 1024)
        var remaining = count
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val n = bin.read(buffer, 0, want)
            if (n < 0) throw EOFException("truncated body")
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /** SSE lines are short; the cap is a guard against a runaway backend. */
    private fun readLine(bin: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = bin.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) return out.toString("ISO-8859-1").removeSuffix("\r")
            out.write(b)
            if (out.size() > MAX_LINE_BYTES) throw IOException("backend line too long")
        }
    }

    private fun respond(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private companion object {
        val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        const val MAX_LINE_BYTES = 4 * 1024 * 1024

        /** Matches ApiServer's backend read budget: 30 minutes of prefill. */
        const val BACKEND_READ_TIMEOUT_MS = 1_800_000
    }
}
