package org.phononai.app

import java.io.OutputStream

/**
 * The harness tests the proxy, not the Home Assistant surface.
 *
 * The real OllamaApi/OllamaCompat need org.json, which this harness
 * deliberately does not carry: its cases are about prefill silence, stalls,
 * body caps and abandonment, and a JSON implementation there would only add a
 * second thing to keep true. The Ollama surface has its own unit tests and a
 * device run; here it answers "not mine" and the proxy returns 404.
 */
object OllamaCompat {
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
    )

    fun errorJson(message: String): String = "{\"error\":\"stub\"}"
}

class OllamaApi(
    private val backendHost: String,
    private val backendPort: Int,
    private val servedModel: () -> OllamaCompat.ServedModel,
    private val ready: () -> Boolean,
    private val onRequestStarted: (String) -> Unit = {},
    private val onRequestFinished: (String) -> Unit = {},
) {
    fun handle(method: String, path: String, body: ByteArray, out: OutputStream): Boolean = false
}
