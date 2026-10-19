package org.phononai.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The registration client against a fake LiteLLM, shaped from what the real
 * proxy does (measured, not guessed):
 *
 *  - `/model/new` never rejects a duplicate name; it inserts another row;
 *  - `/model/update` demands `model_info.id`;
 *  - `/model/delete` takes `{"id": ...}`;
 *  - a proxy with no database answers `/model/new` with 500 and
 *    `No DB Connected...`.
 *
 * So idempotency has to be a lookup-then-update, and these tests pin that.
 */
class GatewayRegistrarTest {

    private lateinit var server: ServerSocket
    private lateinit var base: String

    @Volatile
    private var running = true

    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private var infoBody = """{"data":[]}"""
    private val infoQueue = ArrayDeque<String>()
    private var newStatus = 200
    private var newBody = """{"model_id":"new-id","model_name":"x"}"""
    private var updateStatus = 200
    private var updateBody = """{"model_id":"ok"}"""
    private var deleteStatus = 200
    private var deleteBody = """{"message":"deleted"}"""
    private var modelsStatus = 200

    @Before
    fun start() {
        server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0), 8)
        base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true, name = "fake-gateway") {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    @After
    fun stop() {
        running = false
        try {
            server.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * A socket-level fake: `com.sun.net.httpserver` is not on the Android unit
     * test classpath, and speaking HTTP directly also keeps the test honest
     * about what goes over the wire.
     */
    private fun serve(client: Socket) {
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1)?.substringBefore('?').orEmpty()
            var length = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (length > 0) readBytes(input, length) else ""
            synchronized(calls) {
                calls.add("$method $path")
                bodies.add(body)
            }
            val (status, payload) = when {
                path == "/model/info" ->
                    200 to (if (infoQueue.isEmpty()) infoBody else infoQueue.removeFirst())
                path == "/model/new" -> newStatus to newBody
                path == "/model/update" -> updateStatus to updateBody
                path == "/model/delete" -> deleteStatus to deleteBody
                path == "/v1/models" -> modelsStatus to """{"data":[]}"""
                else -> 404 to """{"error":{"message":"no route"}}"""
            }
            respond(out, status, payload)
        }
    }

    private fun respond(out: OutputStream, status: Int, payload: String) {
        val bytes = payload.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) return buffer.toString("ISO-8859-1").removeSuffix("\r")
            buffer.write(b)
        }
    }

    private fun readBytes(input: BufferedInputStream, count: Int): String {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun registrar(key: String = "sk-test-master") = LiteLlmRegistrar(
        baseUrl = base,
        masterKey = key,
        modelName = "phone-abc12345",
        servedModel = "qwen3.5-2b",
        apiBase = "http://10.0.10.125:8080/v1",
        nodeKey = "node-key-value",
    )

    /** One entry per id, all carrying this node's name. */
    private fun info(vararg ids: String): String {
        val entries = ids.joinToString(",") {
            """{"model_name":"phone-abc12345","model_info":{"id":"$it"}}"""
        }
        return """{"data":[$entries]}"""
    }

    @Test
    fun `a fresh node is created with the payload the gateway expects`() {
        val result = registrar().register()
        assertTrue(result is GatewayResult.Ok)
        assertEquals(listOf("GET /model/info", "POST /model/new"), calls)

        val payload = JSONObject(bodies.last())
        assertEquals("phone-abc12345", payload.getString("model_name"))
        val params = payload.getJSONObject("litellm_params")
        assertEquals("openai/qwen3.5-2b", params.getString("model"))
        assertEquals("http://10.0.10.125:8080/v1", params.getString("api_base"))
        assertEquals("node-key-value", params.getString("api_key"))
        // A phone needs minutes, not seconds.
        assertEquals(1800, params.getInt("timeout"))
        assertEquals(0, params.getInt("max_retries"))
    }

    @Test
    fun `an existing entry is updated, never created again`() {
        infoBody = info("id-1")
        val result = registrar().register()
        assertTrue(result is GatewayResult.Ok)
        assertEquals(listOf("GET /model/info", "POST /model/update"), calls)

        val payload = JSONObject(bodies.last())
        assertEquals("id-1", payload.getJSONObject("model_info").getString("id"))
        assertEquals("phone-abc12345", payload.getString("model_name"))
    }

    @Test
    fun `duplicate rows are converged to one`() {
        infoBody = info("id-1", "id-2", "id-3")
        val result = registrar().register()
        assertTrue(result is GatewayResult.Ok)
        assertEquals(
            listOf(
                "GET /model/info",
                "POST /model/delete",
                "POST /model/delete",
                "POST /model/update",
            ),
            calls,
        )
        // The first id is kept; the extras are removed.
        assertEquals("id-2", JSONObject(bodies[1]).getString("id"))
        assertEquals("id-3", JSONObject(bodies[2]).getString("id"))
        assertEquals("id-1", JSONObject(bodies[3]).getJSONObject("model_info").getString("id"))
    }

    @Test
    fun `a 409 from a different build falls back to update`() {
        newStatus = 409
        newBody = """{"error":{"message":"model already exists"}}"""
        // Empty on the first lookup, the entry on the second.
        infoBody = info("id-9")
        infoQueue.add("""{"data":[]}""")
        val result = registrar().register()
        assertTrue(result is GatewayResult.Ok)
        assertEquals(
            listOf("GET /model/info", "POST /model/new", "GET /model/info", "POST /model/update"),
            calls,
        )
        assertEquals("id-9", JSONObject(bodies.last()).getJSONObject("model_info").getString("id"))
    }

    @Test
    fun `a proxy without a database is explained, not retried`() {
        newStatus = 500
        newBody = """{"error":{"message":"No DB Connected. Here's how to do it - https://docs.litellm.ai/docs/proxy/virtual_keys","type":"auth_error"}}"""
        val result = registrar().register()
        assertTrue(result is GatewayResult.Failure)
        val failure = result as GatewayResult.Failure
        // The gateway's own sentence, verbatim.
        assertTrue(failure.message.contains("No DB Connected"))
        assertEquals(
            "the gateway must run with store_model_in_db: true and a DATABASE_URL",
            failure.hint,
        )
    }

    @Test
    fun `the older STORE_MODEL_IN_DB wording is recognised too`() {
        newStatus = 400
        newBody = """{"error":{"message":"Set STORE_MODEL_IN_DB=True to use /model/new"}}"""
        val failure = registrar().register() as GatewayResult.Failure
        assertNotNull(failure.hint)
        assertTrue(failure.hint!!.contains("store_model_in_db"))
    }

    @Test
    fun `a wrong key is a clear failure, not a crash`() {
        newStatus = 401
        newBody = """{"error":{"message":"Invalid proxy server token"}}"""
        val failure = registrar().register() as GatewayResult.Failure
        assertTrue(failure.message.contains("Invalid proxy server token"))
        assertTrue(failure.hint!!.contains("rejected the key"))
    }

    @Test
    fun `an unreachable gateway says so`() {
        running = false
        server.close()
        val failure = registrar().register() as GatewayResult.Failure
        assertTrue(failure.hint!!.contains("same LAN or tailnet"))
    }

    @Test
    fun `check accepts a good key and rejects a bad one`() {
        assertTrue(registrar().check() is GatewayResult.Ok)
        modelsStatus = 401
        assertTrue(registrar().check() is GatewayResult.Failure)
    }

    @Test
    fun `deregister removes every entry with this name`() {
        infoBody = info("id-1", "id-2")
        val result = registrar().deregister()
        assertTrue(result is GatewayResult.Ok)
        assertEquals(
            listOf("GET /model/info", "POST /model/delete", "POST /model/delete"),
            calls,
        )
        assertEquals("id-1", JSONObject(bodies[1]).getString("id"))
        assertEquals("id-2", JSONObject(bodies[2]).getString("id"))
    }

    @Test
    fun `deregister with nothing registered is a success`() {
        assertTrue(registrar().deregister() is GatewayResult.Ok)
        assertEquals(listOf("GET /model/info"), calls)
    }

    @Test
    fun `the key is redacted if the gateway echoes it`() {
        newStatus = 500
        newBody = """{"error":{"message":"bad token sk-test-master for node-key-value"}}"""
        val failure = registrar().register() as GatewayResult.Failure
        assertTrue(!failure.message.contains("sk-test-master"))
        assertTrue(!failure.message.contains("node-key-value"))
        assertTrue(failure.message.contains("[REDACTED]"))
    }

    @Test
    fun `a cloud gateway address is refused before it fails at request time`() {
        assertNull(GatewayAddress.violation("http://10.0.10.20:4000"))
        assertNull(GatewayAddress.violation("http://192.168.1.5:4000"))
        assertNull(GatewayAddress.violation("http://litellm.local:4000"))
        assertNotNull(GatewayAddress.violation("http://8.8.8.8:4000"))
        assertNotNull(GatewayAddress.violation("ftp://10.0.0.2:4000"))
        assertNotNull(GatewayAddress.violation("not a url"))
    }
}
