package org.phononai.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The Ollama wire format as Home Assistant's built-in `ollama` integration
 * speaks it.
 *
 * Phonon is not an Ollama server and does not want to be one: the model is
 * always resident, there is exactly one, and nothing is pulled. This file is a
 * translation layer over llama-server's OpenAI endpoint, kept free of Android
 * imports so the framing — the part an HA release can break — is unit tested
 * against recorded requests rather than by a user.
 *
 * Two rules drive the shapes here:
 *
 *  - `/api/chat` streams **NDJSON**, one JSON object per line, not SSE. HA
 *    parses the line, so a chunk boundary in the wrong place is a bug.
 *  - `model` on the request is ignored. There is one served model; requiring an
 *    exact match to a GGUF filename would make the integration fail at setup
 *    for every file the catalog renames.
 */
object OllamaCompat {

    /** What `/api/tags` advertises, and what the chat responses echo back. */
    data class ServedModel(
        val name: String,
        val digest: String,
        val sizeBytes: Long,
        val family: String,
        val parameterSize: String,
        val quantization: String,
        val contextLength: Int,
        val modifiedAt: String,
        val displayName: String,
    ) {
        /** Ollama accepts `name` with or without a tag; advertise `:latest`. */
        val tagged: String get() = if (name.contains(':')) name else "$name:latest"
    }

    /** The version a client sees. Not a lie about upstream: the API surface. */
    const val API_VERSION = "0.6.8"

    /** Models HA may route to; both entry points are the same model. */
    fun tagsJson(model: ServedModel): String = JSONObject().apply {
        put("models", JSONArray().put(tagEntry(model)))
    }.toString()

    private fun tagEntry(model: ServedModel): JSONObject = JSONObject().apply {
        put("name", model.tagged)
        put("model", model.tagged)
        put("modified_at", model.modifiedAt)
        put("size", model.sizeBytes)
        put("digest", model.digest)
        put("details", details(model))
    }

    fun versionJson(): String = JSONObject().put("version", API_VERSION).toString()

    /** `/api/show`: the setup check. Capabilities are what gates HA's tools. */
    fun showJson(model: ServedModel): String = JSONObject().apply {
        put("license", "")
        put("modelfile", "# served by Phonon; weights live on the device\n")
        put("parameters", "")
        put("template", "")
        put("details", details(model))
        put("model_info", JSONObject().apply {
            put("general.architecture", model.family)
            put("general.basename", model.displayName)
            put("phonon.context_length", model.contextLength)
        })
        put("capabilities", JSONArray().put("completion").put("tools"))
        put("modified_at", model.modifiedAt)
    }.toString()

    /** `/api/ps`: the model is always resident; report it with no expiry. */
    fun psJson(model: ServedModel): String = JSONObject().apply {
        put("models", JSONArray().put(JSONObject().apply {
            put("name", model.tagged)
            put("model", model.tagged)
            put("size", model.sizeBytes)
            put("digest", model.digest)
            put("details", details(model))
            put("expires_at", "0001-01-01T00:00:00Z")
            put("size_vram", 0)
        }))
    }.toString()

    private fun details(model: ServedModel): JSONObject = JSONObject().apply {
        put("parent_model", "")
        put("format", "gguf")
        put("family", model.family)
        put("families", JSONArray().put(model.family))
        put("parameter_size", model.parameterSize)
        put("quantization_level", model.quantization)
    }

    /** Refusing is honest: there is nothing to pull, the weights are on disk. */
    fun pullRefused(): String = errorJson(
        "pulling is not supported: Phonon serves one model already installed on " +
            "the device. Pick the model this server advertises from /api/tags.",
    )

    fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    /**
     * "Qwen3.5-2B" -> "2B", for the `parameter_size` HA shows in its picker.
     * A filename without a parameter count reports `unknown` rather than a
     * number nobody measured.
     */
    fun parameterSizeOf(label: String): String {
        val match = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*b(?![a-z0-9])").find(label) ?: return "unknown"
        return match.groupValues[1].trimEnd('.') + "B"
    }

    // --- request translation ------------------------------------------------

    /**
     * Ollama's chat request to llama-server's OpenAI request.
     *
     * `model` is deliberately replaced with [servedName]: HA sends back what
     * `/api/tags` advertised, which is the alias, but a user may type anything
     * into the integration's model picker and it must still work.
     */
    fun toOpenAiChatRequest(body: JSONObject, servedName: String): JSONObject {
        val out = JSONObject()
        out.put("model", servedName)
        out.put("messages", messagesToOpenAi(body.optJSONArray("messages") ?: JSONArray()))
        body.optJSONArray("tools")?.let { tools ->
            // Ollama's tool schema is OpenAI's; pass it through when non-empty.
            if (tools.length() > 0) out.put("tools", tools)
        }
        val options = body.optJSONObject("options")
        val stream = if (body.has("stream")) body.optBoolean("stream", true) else true
        out.put("stream", stream)
        if (stream) {
            // Counts for the final NDJSON line. Without this llama-server sends
            // no usage block on a streamed completion.
            out.put("stream_options", JSONObject().put("include_usage", true))
        }
        options?.let { o ->
            o.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
                ?.let { out.put("temperature", it) }
            o.optDouble("top_p", Double.NaN).takeIf { !it.isNaN() }
                ?.let { out.put("top_p", it) }
            o.optDouble("top_k", Double.NaN).takeIf { !it.isNaN() }
                ?.let { out.put("top_k", it) }
            o.optInt("num_predict", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?.let { out.put("max_tokens", it) }
            o.optDouble("repeat_penalty", Double.NaN).takeIf { !it.isNaN() }
                ?.let { out.put("repeat_penalty", it) }
            o.optInt("seed", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?.let { out.put("seed", it) }
            o.optJSONArray("stop")?.let { if (it.length() > 0) out.put("stop", it) }
        }
        // Ollama's `format` is either "json" or an OpenAPI schema (HA's AI Task
        // passes the latter). Both become a response_format llama-server knows.
        when (val format = body.opt("format")) {
            is String -> if (format == "json") {
                out.put("response_format", JSONObject().put("type", "json_object"))
            }
            is JSONObject -> out.put(
                "response_format",
                JSONObject().apply {
                    put("type", "json_schema")
                    put(
                        "json_schema",
                        JSONObject().apply {
                            put("name", "phonon_response")
                            put("schema", format)
                        },
                    )
                },
            )
        }
        // keep_alive and options.num_ctx are no-ops by design: the model never
        // unloads and its context is fixed at launch, so neither can change.
        return out
    }

    /**
     * Messages are near-identical; the two differences that matter are tool
     * call ids (OpenAI needs one, Ollama may omit it) and images, which Ollama
     * carries as base64 on the message and OpenAI as a data URL in content.
     */
    private fun messagesToOpenAi(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role", "user")
            val converted = JSONObject().put("role", role)
            val images = message.optJSONArray("images")
            if (images != null && images.length() > 0) {
                val parts = JSONArray()
                val text = message.optString("content", "")
                if (text.isNotEmpty()) {
                    parts.put(JSONObject().put("type", "text").put("text", text))
                }
                for (n in 0 until images.length()) {
                    parts.put(JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,${images.optString(n)}"),
                    ))
                }
                converted.put("content", parts)
            } else {
                converted.put("content", message.optString("content", ""))
            }
            if (role == "assistant") {
                message.optJSONArray("tool_calls")?.let { calls ->
                    converted.put("tool_calls", ollamaToolCallsToOpenAi(calls))
                }
            }
            if (role == "tool") {
                // OpenAI requires a tool_call_id. Ollama's tool messages often
                // have only a name; llama-server tolerates the placeholder id.
                converted.put(
                    "tool_call_id",
                    message.optString("tool_call_id", "call_${message.optString("name", "tool")}"),
                )
            }
            out.put(converted)
        }
        return out
    }

    /** Ollama arguments are an object; OpenAI wants a JSON string. */
    fun ollamaToolCallsToOpenAi(calls: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function") ?: continue
            val args = function.opt("arguments")
            val encoded = when (args) {
                is JSONObject -> args.toString()
                is String -> args
                else -> "{}"
            }
            out.put(JSONObject().apply {
                put("id", call.optString("id", "call_$i"))
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", function.optString("name"))
                    put("arguments", encoded)
                })
            })
        }
        return out
    }

    /** OpenAI arguments are a JSON string; Ollama wants an object. */
    fun openAiToolCallsToOllama(calls: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function") ?: continue
            out.put(JSONObject().put(
                "function",
                JSONObject().apply {
                    put("name", function.optString("name"))
                    put("arguments", argumentsObject(function.opt("arguments")))
                },
            ))
        }
        return out
    }

    /**
     * A model that emits malformed arguments still produces a response: an
     * empty object is better than a 500 the user cannot act on. Callers that
     * care can see the raw text under `_raw`.
     */
    private fun argumentsObject(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> try {
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject().put("_raw", raw)
        }
        else -> JSONObject()
    }

    // --- response translation ----------------------------------------------

    /**
     * Turns a llama-server SSE stream into Ollama NDJSON lines.
     *
     * Stateful because tool calls arrive split across chunks (name in one,
     * arguments in the next, keyed by index) and Ollama wants them whole. The
     * translator is fed one `data:` payload at a time and returns the lines to
     * write, so the test can assert the exact byte framing.
     */
    class StreamTranslator(
        private val model: String,
        private val nowMs: () -> Long = { System.currentTimeMillis() },
    ) {
        private val startedAtMs = nowMs()
        private var firstTokenAtMs = 0L
        private var finished = false
        private var promptEvalCount = 0
        private var evalCount = 0
        private val toolCalls = sortedMapOf<Int, ToolCallBuilder>()

        private class ToolCallBuilder(var name: String = "", var arguments: StringBuilder = StringBuilder())

        /** One SSE `data:` payload in; zero or more complete NDJSON lines out. */
        fun onData(payload: String): List<String> {
            val text = payload.trim()
            if (text.isEmpty()) return emptyList()
            if (text == "[DONE]") return finalLines()
            val chunk = try {
                JSONObject(text)
            } catch (_: Throwable) {
                return emptyList()
            }
            chunk.optJSONObject("error")?.let { return listOf(errorJson(it.optString("message", "backend error"))) }
            chunk.optJSONObject("usage")?.let { usage ->
                promptEvalCount = usage.optInt("prompt_tokens", promptEvalCount)
                evalCount = usage.optInt("completion_tokens", evalCount)
            }
            val choices = chunk.optJSONArray("choices") ?: return emptyList()
            if (choices.length() == 0) return emptyList()
            val choice = choices.optJSONObject(0) ?: return emptyList()
            val delta = choice.optJSONObject("delta") ?: JSONObject()
            val lines = ArrayList<String>(1)
            // llama-server sends `"content":null` on the role-only chunk and on
            // the finish chunk. org.json's optString turns JSON null into the
            // four-character string "null", so read through this instead.
            val content = delta.text("content")
            // llama-server emits `reasoning_content` when --reasoning is on;
            // Ollama's field for the same thing is `thinking`.
            val thinking = delta.text("reasoning_content")
            if (content.isNotEmpty() || thinking.isNotEmpty()) {
                if (firstTokenAtMs == 0L) firstTokenAtMs = nowMs()
                lines.add(chunkLine(content, thinking))
            }
            delta.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val index = call.optInt("index", i)
                    val builder = toolCalls.getOrPut(index) { ToolCallBuilder() }
                    call.optJSONObject("function")?.let { function ->
                        function.optString("name").takeIf { it.isNotEmpty() }?.let {
                            builder.name = it
                        }
                        builder.arguments.append(function.optString("arguments", ""))
                    }
                }
            }
            // The finish chunk carries `finish_reason` but no usage: llama-server
            // sends the usage block in a separate chunk with `choices: []`
            // *after* it. Ending the stream here would report zero tokens, so
            // the final line waits for [DONE] or EOF.
            val finish = choice.optString("finish_reason", "")
            if (finish.isNotEmpty() && finish != "null") finishedReason = finish
            return lines
        }

        private var finishedReason = "stop"

        /** Emitted once, whether the stream said [DONE] or just stopped. */
        fun finalLines(): List<String> {
            if (finished) return emptyList()
            finished = true
            val lines = ArrayList<String>(2)
            if (toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for ((_, builder) in toolCalls) {
                    calls.put(JSONObject().put(
                        "function",
                        JSONObject().apply {
                            put("name", builder.name)
                            put("arguments", argumentsObject(builder.arguments.toString()))
                        },
                    ))
                }
                lines.add(JSONObject().apply {
                    put("model", model)
                    put("created_at", createdAt(nowMs()))
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", "")
                        put("tool_calls", calls)
                    })
                    put("done", false)
                }.toString())
            }
            lines.add(JSONObject().apply {
                put("model", model)
                put("created_at", createdAt(nowMs()))
                put("message", JSONObject().put("role", "assistant").put("content", ""))
                put("done", true)
                put("done_reason", if (finishedReason == "length") "length" else "stop")
                put("total_duration", nsSince(startedAtMs))
                put("load_duration", 0L)
                put("prompt_eval_count", promptEvalCount)
                put("prompt_eval_duration", if (firstTokenAtMs == 0L) 0L else nsBetween(startedAtMs, firstTokenAtMs))
                put("eval_count", evalCount)
                put("eval_duration", if (firstTokenAtMs == 0L) 0L else nsSince(firstTokenAtMs))
            }.toString())
            return lines
        }

        fun chunkLine(content: String, thinking: String = ""): String = JSONObject().apply {
            put("model", model)
            put("created_at", createdAt(nowMs()))
            put(
                "message",
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                    if (thinking.isNotEmpty()) put("thinking", thinking)
                },
            )
            put("done", false)
        }.toString()

        private fun nsSince(fromMs: Long): Long = (nowMs() - fromMs).coerceAtLeast(0L) * 1_000_000L
        private fun nsBetween(a: Long, b: Long): Long = (b - a).coerceAtLeast(0L) * 1_000_000L
    }

    /**
     * Drives an SSE body into an Ollama NDJSON stream.
     *
     * [emit] is called once per complete NDJSON line, in order, as soon as the
     * line exists — that is what makes HA's streamed answer incremental instead
     * of one buffered blob at the end. Returns the number of lines emitted.
     *
     * Kept here, away from the socket, so the framing is tested against a
     * recorded stream instead of only on a phone.
     */
    fun pumpNdjson(
        input: java.io.BufferedReader,
        translator: StreamTranslator,
        emit: (String) -> Unit,
    ): Int {
        var lines = 0
        while (true) {
            val line = input.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            for (ndjson in translator.onData(data)) {
                emit(ndjson)
                lines++
            }
            if (data == "[DONE]") break
        }
        for (ndjson in translator.finalLines()) {
            emit(ndjson)
            lines++
        }
        return lines
    }

    /** A non-streamed OpenAI completion, as one Ollama chat response object. */
    fun openAiResponseToOllama(body: JSONObject, model: String, elapsedMs: Long): String {
        val choice = body.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val usage = body.optJSONObject("usage")
        return JSONObject().apply {
            put("model", model)
            put("created_at", createdAt(System.currentTimeMillis()))
            put("message", JSONObject().apply {
                put("role", "assistant")
                put("content", message?.optString("content", "").orEmpty())
                message?.optJSONArray("tool_calls")?.let { calls ->
                    if (calls.length() > 0) put("tool_calls", openAiToolCallsToOllama(calls))
                }
            })
            put("done", true)
            put("done_reason", "stop")
            put("total_duration", elapsedMs.coerceAtLeast(0L) * 1_000_000L)
            put("load_duration", 0L)
            put("prompt_eval_count", usage?.optInt("prompt_tokens", 0) ?: 0)
            put("prompt_eval_duration", 0L)
            put("eval_count", usage?.optInt("completion_tokens", 0) ?: 0)
            put("eval_duration", 0L)
        }.toString()
    }

    /**
     * RFC3339 in UTC. Ollama emits nanosecond precision; milliseconds are the
     * same format and every client that parses Ollama's field parses this.
     */
    fun createdAt(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()
}

/**
 * A JSON string field, with JSON `null` read as empty.
 *
 * `optString` returns the four characters `null` for an explicit null, which
 * llama-server sends on every role-only and finish chunk. Reading that as
 * content is how a stream quietly becomes the word "nullnullnull".
 */
private fun JSONObject.text(key: String): String {
    val value = opt(key) ?: return ""
    return if (value == JSONObject.NULL) "" else value.toString()
}
