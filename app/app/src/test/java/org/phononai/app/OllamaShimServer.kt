package org.phononai.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A dev harness that serves the **real** [OllamaApi] over a socket on the JVM.
 *
 * The app's `/api/` routes have no Android dependency by design, so the same
 * class Home Assistant talks to on the phone can be driven here by the real
 * `ollama` Python client against a real llama-server:
 *
 *     PHONON_SHIM_PORT=11434 PHONON_BACKEND_PORT=18099 \
 *       ./app/gradlew -p app :app:ollamaShim
 *
 *     python -c 'import ollama; print(ollama.Client(host="http://127.0.0.1:11434").list())'
 *
 * This is a test tool, not shipped: it lives in the test source set and is not
 * referenced by the app. It deliberately does no authentication — put the app
 * in front of anything real.
 */
object OllamaShimServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("PHONON_SHIM_PORT")?.toIntOrNull() ?: 11434
        val backendPort = System.getenv("PHONON_BACKEND_PORT")?.toIntOrNull() ?: 18099
        val name = System.getenv("PHONON_SHIM_MODEL") ?: "qwen3.5-2b"
        val size = System.getenv("PHONON_SHIM_SIZE")?.toLongOrNull() ?: 1_900_000_000L
        val context = System.getenv("PHONON_SHIM_CONTEXT")?.toIntOrNull() ?: 4096

        val model = OllamaCompat.ServedModel(
            name = name,
            digest = "sha256:unknown",
            sizeBytes = size,
            family = "qwen",
            parameterSize = OllamaCompat.parameterSizeOf(name),
            quantization = "Q4_K_M",
            contextLength = context,
            modifiedAt = OllamaCompat.createdAt(System.currentTimeMillis()),
            displayName = name,
        )
        val api = OllamaApi(
            backendHost = "127.0.0.1",
            backendPort = backendPort,
            servedModel = { model },
            ready = { true },
        )
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        println("ollama shim on http://127.0.0.1:$port -> backend 127.0.0.1:$backendPort ($name)")
        while (true) {
            val client = server.accept()
            thread(isDaemon = true, name = "shim-client") { serve(client, api) }
        }
    }

    private fun serve(socket: java.net.Socket, api: OllamaApi) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                val method = parts[0]
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
                var contentLength = 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        headers[key] = value
                        if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) readBytes(input, contentLength) else ByteArray(0)
                if (!api.handle(method, path, body, out)) {
                    val message = """{"error":"not found: $path"}""".toByteArray()
                    out.write(
                        (
                            "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${message.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    out.write(message)
                }
                out.flush()
            } catch (_: Throwable) {
                // A broken client connection is not the server's problem.
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) return out.toString("ISO-8859-1").removeSuffix("\r")
            out.write(b)
        }
    }

    private fun readBytes(input: BufferedInputStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream(count)
        val buffer = ByteArray(16 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }
}
