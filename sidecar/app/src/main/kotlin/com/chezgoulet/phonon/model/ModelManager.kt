package com.chezgoulet.phonon.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages LiteRT-LM inference engine lifecycle.
 *
 * LiteRT-LM replaces the old dual-engine architecture (prima.cpp CPU +
 * OlliteRT NPU). A single Engine instance loads a .litertlm model file
 * and serves all inference requests via Conversations.
 *
 * Hardware acceleration (NPU on Pixel Tensor, GPU fallback) is handled
 * automatically by the LiteRT-LM SDK based on the configured backend.
 */
class ModelManager(private val context: Context) {
    private val tag = "ModelManager"

    private var engine: Engine? = null
    private var currentModel: String? = null
    private var modelFile: File? = null

    // ─── Public API ───

    fun currentModelName(): String? = currentModel
    fun isRunning(): Boolean = engine != null
    fun currentEngine(): String? = "litert-lm"

    /**
     * Loads a .litertlm model and initializes the LiteRT-LM engine.
     *
     * This is the expensive step (up to ~10 s on first load) — the model
     * is loaded into memory and the runtime is initialized. Call on a
     * background coroutine.
     *
     * @param modelName Logical model name
     * @param modelUrl  URL to download from coordinator (blank if cached)
     */
    suspend fun loadModel(modelName: String, modelUrl: String, engine: String = "litert-lm") {
        Log.i(tag, "Loading model: $modelName (LiteRT-LM)")

        if (isRunning()) {
            unloadModel()
        }

        currentModel = modelName

        // Locate or prepare the model file
        val cacheDir = File(context.cacheDir, "models")
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, modelName.replace("/", "_").replace(":", "_"))

        if (cachedFile.exists() && cachedFile.length() > 0) {
            modelFile = cachedFile
            Log.i(tag, "Model cached: ${cachedFile.absolutePath} (${cachedFile.length()} bytes)")
        } else if (modelUrl.isNotBlank()) {
            modelFile = cachedFile
            Log.i(tag, "Model not cached — expecting download from coordinator to ${cachedFile.absolutePath}")
        } else {
            Log.w(tag, "No model URL or cached file for $modelName")
            return
        }

        val modelPath = modelFile?.absolutePath ?: run {
            Log.e(tag, "Model file path is null")
            return
        }

        try {
            withContext(Dispatchers.Default) {
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),  // TODO: switch to NPU(…) for Pixel Tensor chips
                    cacheDir = context.cacheDir.absolutePath
                )
                engine = Engine(engineConfig).also { it.initialize() }
            }
            Log.i(tag, "LiteRT-LM engine initialized with model: $modelName")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize LiteRT-LM engine: ${e.message}")
            engine?.close()
            engine = null
        }
    }

    /**
     * Gracefully stops the inference engine and releases model resources.
     */
    suspend fun unloadModel() {
        Log.i(tag, "Unloading model: $currentModel")
        withContext(Dispatchers.Default) {
            engine?.close()
        }
        engine = null
        currentModel = null
        modelFile = null
        Log.i(tag, "Model unloaded")
    }

    /**
     * Runs synchronous inference on the loaded model.
     *
     * Creates a fresh conversation for each call (stateless — the HTTP
     * inference API sends the full message history with every request).
     *
     * @param prompt  The input text to generate from
     * @param maxTokens  Maximum tokens to generate (default 2048)
     * @return The generated text response
     * @throws IllegalStateException if no model is loaded
     */
    suspend fun generate(prompt: String, maxTokens: Int = 2048): String {
        val eng = engine ?: throw IllegalStateException("No model loaded")

        return withContext(Dispatchers.Default) {
            val config = ConversationConfig(
                maxTokens = maxTokens,
                samplerConfig = SamplerConfig(
                    temperature = 0.7,
                    topK = 40,
                    topP = 0.95
                )
            )

            eng.createConversation(config).use { conversation ->
                val message = conversation.sendMessage(prompt)
                message.toString()
            }
        }
    }
}
