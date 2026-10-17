package dev.phonon.spike

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Loopback-only HTTP server. One route: GET /health. */
class HealthServer(private val port: Int = 8080) {

    fun start() {
        thread(name = "phonon-health", isDaemon = true) {
            try {
                ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")).use { server ->
                    Log.i(TAG, "health endpoint listening on 127.0.0.1:$port")
                    while (!server.isClosed) {
                        val client = server.accept()
                        thread(name = "phonon-health-client", isDaemon = true) {
                            serve(client)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "health server failed: ${t.javaClass.name}: ${t.message}", t)
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5_000
            try {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                // Drain request headers; requests have no body.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val path = requestLine.split(' ').getOrNull(1) ?: "/"
                val (status, body) = if (path == "/health") {
                    "200 OK" to HealthState.toJson()
                } else {
                    "404 Not Found" to """{"error":"not found"}"""
                }

                val bytes = body.toByteArray(Charsets.UTF_8)
                val header = buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(header.toByteArray(Charsets.US_ASCII))
                    write(bytes)
                    flush()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "health request failed: ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    private companion object {
        const val TAG = "PhononProbe"
    }
}
