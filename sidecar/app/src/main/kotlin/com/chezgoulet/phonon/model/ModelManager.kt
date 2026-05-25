package com.chezgoulet.phonon.model

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the OlliteRT inference engine process.
 *
 * Lifecycle:
 * 1. loadModel() → checks for cached model file, starts OlliteRT
 * 2. unloadModel() → gracefully stops OlliteRT
 * 3. isRunning() → whether OlliteRT is active
 *
 * OlliteRT exposes an OpenAI-compatible HTTP endpoint on localhost:8085.
 * The InferenceServer proxies inference requests to it.
 */
class ModelManager(private val context: Context) {
    private val tag = "ModelManager"

    private var olliteProcess: Process? = null
    private var currentModel: String? = null
    private var modelFile: File? = null

    fun currentModelName(): String? = currentModel
    fun isRunning(): Boolean = olliteProcess?.isAlive == true

    /**
     * Loads a model and starts OlliteRT.
     */
    suspend fun loadModel(modelName: String, modelUrl: String) {
        Log.i(tag, "Loading model: $modelName")

        if (isRunning()) {
            unloadModel()
        }

        currentModel = modelName

        val cacheDir = File(context.cacheDir, "models")
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, modelName.replace("/", "_").replace(":", "_"))

        if (cachedFile.exists() && cachedFile.length() > 0) {
            modelFile = cachedFile
            Log.i(tag, "Model cached: ${cachedFile.absolutePath} (${cachedFile.length()} bytes)")
        } else if (modelUrl.isNotBlank()) {
            Log.i(tag, "Model not cached — expected download from coordinator")
            modelFile = cachedFile
        } else {
            Log.w(tag, "No model URL or cached file for $modelName")
            return
        }

        startOlliteRT()
    }

    /**
     * Gracefully stops OlliteRT.
     */
    suspend fun unloadModel() {
        Log.i(tag, "Unloading model: $currentModel")

        olliteProcess?.let { proc ->
            if (proc.isAlive) {
                proc.destroy()
                try {
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {}
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }

        olliteProcess = null
        currentModel = null
        modelFile = null
        Log.i(tag, "Model unloaded")
    }

    private fun startOlliteRT() {
        val modelPath = modelFile?.absolutePath ?: return

        try {
            val processBuilder = ProcessBuilder(
                "ollitert",
                "--model", modelPath,
                "--port", "8085",
                "--host", "127.0.0.1"
            )
            processBuilder.directory(context.filesDir)
            processBuilder.redirectErrorStream(true)

            olliteProcess = processBuilder.start()
            Log.i(tag, "OlliteRT started")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start OlliteRT: ${e.message}")
            olliteProcess = null
        }
    }
}
