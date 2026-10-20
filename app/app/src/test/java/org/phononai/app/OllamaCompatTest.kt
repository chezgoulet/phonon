package org.phononai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the request shapes Home Assistant's built-in `ollama` integration
 * sends, and asserts the shapes it parses back.
 *
 * The fixtures are the payloads from HA core's `ollama/entity.py` and
 * `ollama/config_flow.py`: `client.list()` at setup, then `client.chat(...)`
 * with `stream=True`, `keep_alive`, `options.num_ctx`, `tools` and a `format`
 * schema. A future HA release that changes one of them fails here rather than
 * in a user's house.
 */
class OllamaCompatTest {

    private val model = OllamaCompat.ServedModel(
        name = "qwen3.5-2b",
        digest = "sha256:" + "ab".repeat(32),
        sizeBytes = 1_900_000_000L,
        family = "qwen",
        parameterSize = "2B",
        quantization = "Q4_K_M",
        contextLength = 32768,
        modifiedAt = "2026-10-19T12:00:00Z",
        displayName = "Qwen3.5-2B",
    )

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/ollama/$name")!!.bufferedReader().readText()

    private fun fixture(name: String): JSONObject = JSONObject(resource(name))

    @Test
    fun `tags carries the fields the HA picker reads`() {
        val tags = JSONObject(OllamaCompat.tagsJson(model))
        val listed = tags.getJSONArray("models")
        assertEquals(1, listed.length())
        val entry = listed.getJSONObject(0)
        // config_flow.py does model_info["model"] for the selector value.
        assertEquals("qwen3.5-2b:latest", entry.getString("model"))
        assertEquals("qwen3.5-2b:latest", entry.getString("name"))
        assertEquals(1_900_000_000L, entry.getLong("size"))
        assertTrue(entry.getString("digest").startsWith("sha256:"))
        // ollama's ListResponse parses modified_at as a datetime.
        assertTrue(entry.getString("modified_at").contains("T"))
        val details = entry.getJSONObject("details")
        assertEquals("gguf", details.getString("format"))
        assertEquals("2B", details.getString("parameter_size"))
        assertEquals("Q4_K_M", details.getString("quantization_level"))
    }

    @Test
    fun `show advertises tools as well as completion`() {
        val show = JSONObject(OllamaCompat.showJson(model))
        val caps = show.getJSONArray("capabilities")
        val names = (0 until caps.length()).map { caps.getString(it) }
        // HA gates its Control-Assistant tool path on this list.
        assertTrue(names.contains("completion"))
        assertTrue(names.contains("tools"))
        assertEquals("qwen", show.getJSONObject("details").getString("family"))
    }

    @Test
    fun `version answers the setup probe`() {
        assertTrue(JSONObject(OllamaCompat.versionJson()).getString("version").isNotBlank())
    }

    @Test
    fun `HA streaming chat request translates to the backend request`() {
        val ollama = fixture("ha-chat-stream.json")
        val openai = OllamaCompat.toOpenAiChatRequest(ollama, "qwen3.5-2b")

        assertEquals("qwen3.5-2b", openai.getString("model"))
        assertTrue(openai.getBoolean("stream"))
        assertTrue(openai.getJSONObject("stream_options").getBoolean("include_usage"))

        val messages = openai.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))

        // Tools are OpenAI-shaped already and must survive untouched.
        assertEquals(1, openai.getJSONArray("tools").length())
        assertEquals(
            "GetLiveContext",
            openai.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"),
        )

        // keep_alive and num_ctx are platform concepts Phonon does not have.
        assertFalse(openai.has("keep_alive"))
        assertFalse(openai.has("num_ctx"))
        assertFalse(openai.has("think"))
        // options DO map.
        assertEquals(0.2, openai.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `model name from the request is ignored`() {
        val ollama = fixture("ha-chat-stream.json").put("model", "something-else:7b")
        val openai = OllamaCompat.toOpenAiChatRequest(ollama, "qwen3.5-2b")
        assertEquals("qwen3.5-2b", openai.getString("model"))
    }

    @Test
    fun `an OpenAPI format becomes a json_schema response format`() {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("temperature", JSONObject().put("type", "number")))
        val ollama = JSONObject()
            .put("model", "x")
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            .put("format", schema)
        val openai = OllamaCompat.toOpenAiChatRequest(ollama, "m")
        val format = openai.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("object", format.getJSONObject("json_schema").getJSONObject("schema").getString("type"))
    }

    @Test
    fun `plain json format is json_object`() {
        val ollama = JSONObject()
            .put("model", "x")
            .put("messages", org.json.JSONArray())
            .put("format", "json")
        val openai = OllamaCompat.toOpenAiChatRequest(ollama, "m")
        assertEquals("json_object", openai.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun `a tool result message gets an id llama-server accepts`() {
        val ollama = fixture("ha-tool-result-nonstream.json")
        val openai = OllamaCompat.toOpenAiChatRequest(ollama, "qwen3.5-2b")
        val messages = openai.getJSONArray("messages")
        val toolMessage = messages.getJSONObject(messages.length() - 1)
        assertEquals("tool", toolMessage.getString("role"))
        assertTrue(toolMessage.getString("tool_call_id").isNotBlank())
        assertFalse(openai.getBoolean("stream"))
    }

    @Test
    fun `ollama arguments object becomes an OpenAI JSON string and back`() {
        val ollamaCalls = org.json.JSONArray().put(
            JSONObject().put(
                "function",
                JSONObject().put("name", "GetLiveContext")
                    .put("arguments", JSONObject().put("entity_id", "light.kitchen")),
            ),
        )
        val openai = OllamaCompat.ollamaToolCallsToOpenAi(ollamaCalls)
        val args = openai.getJSONObject(0).getJSONObject("function").getString("arguments")
        assertEquals("light.kitchen", JSONObject(args).getString("entity_id"))

        val back = OllamaCompat.openAiToolCallsToOllama(openai)
        val roundTripped = back.getJSONObject(0).getJSONObject("function")
        assertEquals("GetLiveContext", roundTripped.getString("name"))
        assertEquals("light.kitchen", roundTripped.getJSONObject("arguments").getString("entity_id"))
    }

    @Test
    fun `unparseable arguments do not throw`() {
        val calls = org.json.JSONArray().put(
            JSONObject().put(
                "function",
                JSONObject().put("name", "GetLiveContext").put("arguments", "{not json"),
            ),
        )
        val mapped = OllamaCompat.openAiToolCallsToOllama(calls)
        // A broken model still produces a parseable response, with the raw text
        // preserved so the failure is visible instead of silent.
        assertEquals("{not json", mapped.getJSONObject(0).getJSONObject("function")
            .getJSONObject("arguments").getString("_raw"))
    }

    @Test
    fun `pull is refused rather than faked`() {
        val refusal = JSONObject(OllamaCompat.pullRefused())
        assertTrue(refusal.getString("error").contains("not supported"))
        assertTrue(refusal.getString("error").contains("/api/tags"))
    }

    @Test
    fun `parameter size comes from the model label`() {
        assertEquals("2B", OllamaCompat.parameterSizeOf("Qwen3.5-2B"))
        assertEquals("4B", OllamaCompat.parameterSizeOf("NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"))
        assertEquals("1.5B", OllamaCompat.parameterSizeOf("qwen2.5-1.5b-instruct"))
        assertEquals("unknown", OllamaCompat.parameterSizeOf("model.gguf"))
    }

    @Test
    fun `created_at is RFC3339 in UTC`() {
        val stamp = OllamaCompat.createdAt(1_700_000_000_000L)
        assertTrue(stamp.endsWith("Z"))
        assertNotNull(java.time.Instant.parse(stamp))
    }
}
