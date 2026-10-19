package org.phononai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Replays **recorded** llama-server SSE bodies through the same translator and
 * pump the HTTP route uses, and asserts the NDJSON Home Assistant's client
 * would read.
 *
 * Provenance of the fixtures in `src/test/resources/ollama/`: captured on
 * 2026-10-19 from llama.cpp `797de63` (the revision the app pins) built for the
 * host, serving `Qwen3.5-2B-Q4_K_M.gguf`, with `POST /v1/chat/completions`,
 * `stream: true` and `stream_options.include_usage`. `chat-toolcall.sse` is the
 * same call with one `HassTurnOn` tool defined. Re-capture with
 * `tools/record-ha-stream.sh` when the engine moves.
 */
class OllamaRelayTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/ollama/$name")!!.bufferedReader().readText()

    private fun sseText(): String = resource("chat-stream.sse")

    /**
     * A JSON field as text, with JSON null read as empty. Mirrors the
     * production helper: `optString` would return the word "null", which is how
     * a stream silently becomes "nullnullnull".
     */
    private fun text(source: JSONObject, key: String): String {
        val value = source.opt(key) ?: return ""
        return if (value == JSONObject.NULL) "" else value.toString()
    }

    /** What the backend said, chunk by chunk, so the assertion is not a guess. */
    private fun expectedContent(sse: String): String {
        val out = StringBuilder()
        for (line in sse.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val choices = JSONObject(data).optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
            out.append(text(delta, "content"))
        }
        return out.toString()
    }

    private fun expectedChunkCount(sse: String): Int = sse.lineSequence().count { line ->
        if (!line.startsWith("data:")) {
            false
        } else {
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") {
                false
            } else {
                val choices = JSONObject(data).optJSONArray("choices")
                val delta = choices?.takeIf { it.length() > 0 }
                    ?.optJSONObject(0)?.optJSONObject("delta")
                delta != null && (text(delta, "content").isNotEmpty() || text(delta, "reasoning_content").isNotEmpty())
            }
        }
    }

    @Test
    fun `stream becomes one NDJSON object per chunk plus a final done`() {
        val sse = sseText()
        val emitted = mutableListOf<String>()
        val translator = OllamaCompat.StreamTranslator("qwen3.5-2b:latest") { 1_000L }
        val lines = OllamaCompat.pumpNdjson(
            BufferedReader(StringReader(sse)),
            translator,
        ) { emitted.add(it) }

        assertEquals(emitted.size, lines)
        // Not one buffered blob: one line per content chunk, then the final.
        assertEquals(expectedChunkCount(sse) + 1, emitted.size)

        for (line in emitted) {
            // A newline inside a line would split the object and break the
            // client's framing.
            assertTrue("embedded newline in $line", !line.contains('\n'))
            JSONObject(line)
        }
        val parsed = emitted.map { JSONObject(it) }
        assertTrue(parsed.dropLast(1).all { !it.getBoolean("done") })
        assertEquals("assistant", parsed.first().getJSONObject("message").getString("role"))

        val final = parsed.last()
        assertTrue(final.getBoolean("done"))
        // The usage chunk arrives after finish_reason; it must still be counted.
        assertTrue("eval_count was not carried", final.getInt("eval_count") > 0)
        assertTrue(final.getInt("prompt_eval_count") > 0)
        assertTrue(final.has("total_duration"))
        assertEquals(1, parsed.count { it.getBoolean("done") })

        val content = parsed.joinToString("") {
            it.getJSONObject("message").optString("content", "")
        }
        assertEquals(expectedContent(sse), content)
        // The role-only and finish chunks carry `"content": null`; reading that
        // as text is how a model appears to answer "null".
        assertTrue(!content.contains("null"))
    }

    @Test
    fun `lines are emitted as they arrive, not collected`() {
        val sse = sseText()
        val readsAtEmit = mutableListOf<Int>()
        val counting = object : BufferedReader(StringReader(sse)) {
            var reads = 0
                private set

            override fun readLine(): String? {
                reads++
                return super.readLine()
            }
        }
        val totalLines = sse.lineSequence().count()
        val translator = OllamaCompat.StreamTranslator("m") { 1_000L }
        OllamaCompat.pumpNdjson(counting, translator) { readsAtEmit.add(counting.reads) }

        assertTrue("no lines emitted", readsAtEmit.isNotEmpty())
        // The first chunk is handed to the client before the stream is read to
        // the end: that is what makes HA's answer appear incrementally.
        assertTrue(
            "first line only after EOF (${readsAtEmit.first()} of $totalLines)",
            readsAtEmit.first() < totalLines,
        )
        // And the emission order is monotonic with the read position.
        assertEquals(readsAtEmit.sorted(), readsAtEmit)
    }

    @Test
    fun `a stream that ends without DONE still terminates with done true`() {
        val truncated = sseText().lineSequence()
            .filterNot { it.trim() == "data: [DONE]" }
            .joinToString("\n")
        val emitted = mutableListOf<String>()
        val translator = OllamaCompat.StreamTranslator("m") { 1_000L }
        OllamaCompat.pumpNdjson(BufferedReader(StringReader(truncated)), translator) {
            emitted.add(it)
        }
        assertTrue(emitted.isNotEmpty())
        assertTrue(JSONObject(emitted.last()).getBoolean("done"))
        assertEquals(1, emitted.count { JSONObject(it).getBoolean("done") })
    }

    @Test
    fun `a backend error line is passed through as an error object`() {
        val translator = OllamaCompat.StreamTranslator("m") { 1_000L }
        val lines = translator.onData("""{"error":{"message":"context shifted"}}""")
        assertEquals(1, lines.size)
        assertEquals("context shifted", JSONObject(lines[0]).getString("error"))
    }

    @Test
    fun `a real streamed tool call is reassembled into one Ollama tool call`() {
        val sse = resource("chat-toolcall.sse")
        val emitted = mutableListOf<String>()
        val translator = OllamaCompat.StreamTranslator("qwen3.5-2b:latest") { 1_000L }
        OllamaCompat.pumpNdjson(BufferedReader(StringReader(sse)), translator) { emitted.add(it) }

        val messages = emitted.map { JSONObject(it) }
        val withCalls = messages.firstOrNull { it.getJSONObject("message").has("tool_calls") }
        assertNotNull("no tool_calls line emitted", withCalls)
        val calls = withCalls!!.getJSONObject("message").getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        val function = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("HassTurnOn", function.getString("name"))
        // Arguments arrived as seven separate fragments and must be one object.
        assertTrue(function.getJSONObject("arguments").has("name"))
        assertEquals("stop", messages.last().getString("done_reason"))
        assertTrue(messages.last().getInt("eval_count") > 0)
    }
}
