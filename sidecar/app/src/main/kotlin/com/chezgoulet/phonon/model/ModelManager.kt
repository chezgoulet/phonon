package com.chezgoulet.phonon.model

import android.content.Context
import android.os.Build
import android.util.Log
import com.chezgoulet.phonon.accel.BackendFactory
import com.chezgoulet.phonon.accel.BackendPlanner
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

    /** The accelerator the engine actually initialized with: "npu", "gpu", or "cpu". */
    @Volatile
    private var activeBackend: String? = null

    // ─── Public API ───

    fun currentModelName(): String? = currentModel
    fun isRunning(): Boolean = engine != null
    fun currentEngine(): String? = "litert-lm"

    /**
     * Returns the accelerator the engine is actually running on, or null if
     * no model is loaded. Reported to the coordinator in heartbeats so the
     * dashboard can show requested-vs-active backend per phone.
     */
    fun currentBackend(): String? = if (isRunning()) activeBackend else null

    /**
     * Loads a .litertlm model and initializes the LiteRT-LM engine.
     *
     * This is the expensive step (up to ~10 s on first load) — the model
     * is loaded into memory and the runtime is initialized. Call on a
     * background coroutine.
     *
     * @param modelName Logical model name
     * @param modelUrl  URL to download from coordinator (blank if cached)
     * @param requestedBackend Accelerator requested by the coordinator:
     *   "auto" (default), "npu", "gpu", or "cpu". The actual backend used
     *   may differ — see [currentBackend] and [BackendPlanner].
     */
    suspend fun loadModel(modelName: String, modelUrl: String, requestedBackend: String? = null) {
        Log.i(tag, "Loading model: $modelName (LiteRT-LM, backend=${requestedBackend ?: "auto"})")

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

        // Resolve the accelerator chain for this device and try each in order.
        // NPU initialization can fail for many reasons (unsupported op in the
        // model, driver issues, SDK without that backend) — every failure
        // falls through to the next candidate so the node stays serviceable.
        val deviceInfo = BackendPlanner.DeviceInfo(
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL ?: "" else "",
            hardware = Build.HARDWARE ?: "",
            apiLevel = Build.VERSION.SDK_INT,
        )
        val chain = BackendPlanner.candidates(requestedBackend, deviceInfo)
        Log.i(tag, "Backend chain for ${deviceInfo.socModel.ifEmpty { deviceInfo.hardware }}: $chain")

        withContext(Dispatchers.Default) {
            for (backendName in chain) {
                val backend = BackendFactory.create(backendName)
                if (backend == null) {
                    Log.i(tag, "Backend $backendName unavailable, trying next")
                    continue
                }
                try {
                    val engineConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    engine = Engine(engineConfig).also { it.initialize() }
                    activeBackend = backendName
                    Log.i(tag, "LiteRT-LM engine initialized: model=$modelName backend=$backendName")
                    return@withContext
                } catch (e: Exception) {
                    Log.w(tag, "Backend $backendName init failed (${e.message}), trying next")
                    engine?.close()
                    engine = null
                }
            }
            activeBackend = null
            Log.e(tag, "All backends in chain $chain failed for model $modelName")
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
        activeBackend = null
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
    suspend fun generate(prompt: String): String {
        val eng = engine ?: throw IllegalStateException("No model loaded")

        return withContext(Dispatchers.Default) {
            val config = ConversationConfig(
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
