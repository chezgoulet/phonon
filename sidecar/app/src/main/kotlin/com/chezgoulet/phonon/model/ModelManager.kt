package com.chezgoulet.phonon.model

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages native inference engine processes (prima.cpp CPU / OlliteRT NPU).
 *
 * Binaries are extracted from APK assets at first run:
 * - assets/prima/llama-server — CPU inference binary (shard mode)
 * - assets/ollitert/libollitert.so — NPU inference binary (pool mode)
 *
 * Binaries are extracted to filesDir/bin/ and executed as subprocesses.
 * The libllama.so shared library (prima.cpp core) is loaded via jniLibs.
 */
class ModelManager(private val context: Context) {
    private val tag = "ModelManager"

    // Engine processes
    private var activeProcess: Process? = null
    private var currentModel: String? = null
    private var currentEngine: String? = null
    private var modelFile: File? = null

    // Track whether binaries have been extracted
    private var binariesExtracted = false

    // ─── Public API ───

    fun currentModelName(): String? = currentModel
    fun isRunning(): Boolean = activeProcess?.isAlive == true
    fun currentEngine(): String? = currentEngine

    /**
     * Extracts bundled native binaries from APK assets to app-private storage.
     * Called once on service startup.
     */
    fun extractBinaries() {
        if (binariesExtracted) return

        val binDir = File(context.filesDir, "bin")
        binDir.mkdirs()

        val binaries = listOf(
            "prima/llama-server",
            "ollitert/libollitert.so"
        )

        for (assetPath in binaries) {
            try {
                val name = File(assetPath).name
                val outputFile = File(binDir, name)
                if (outputFile.exists()) continue

                context.assets.open(assetPath).use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                outputFile.setExecutable(true)
                Log.i(tag, "Extracted binary: $assetPath -> ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(tag, "Binary not in assets: $assetPath (${e.message})")
            }
        }

        binariesExtracted = true
    }

    /**
     * Returns the path to the inference binary root directory.
     */
    fun binaryDir(): File = File(context.filesDir, "bin")

    /**
     * Loads a model and starts the appropriate inference engine.
     * @param modelName Logical model name
     * @param modelUrl  URL to download from coordinator (empty if cached)
     * @param engine    "prima" or "ollitert" (defaults to "prima")
     */
    suspend fun loadModel(modelName: String, modelUrl: String, engine: String = "prima") {
        Log.i(tag, "Loading model: $modelName (engine=$engine)")

        if (isRunning()) {
            unloadModel()
        }

        currentModel = modelName
        currentEngine = engine

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

        // Ensure binaries are extracted
        extractBinaries()

        when (engine) {
            "prima" -> startPrima()
            "ollitert" -> startOlliteRT()
            else -> {
                Log.e(tag, "Unknown engine: $engine, falling back to prima")
                startPrima()
            }
        }
    }

    /**
     * Gracefully stops the running inference engine.
     */
    suspend fun unloadModel() {
        Log.i(tag, "Unloading model: $currentModel")

        activeProcess?.let { proc ->
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

        activeProcess = null
        currentModel = null
        currentEngine = null
        modelFile = null
        Log.i(tag, "Model unloaded")
    }

    // ─── Engine launchers ───

    private fun startPrima() {
        val modelPath = modelFile?.absolutePath ?: return
        val binaryPath = binaryDir().resolve("llama-server").absolutePath

        if (!File(binaryPath).exists()) {
            Log.e(tag, "llama-server binary not found at $binaryPath — run build-prima.sh first")
            return
        }

        try {
            val processBuilder = ProcessBuilder(
                binaryPath,
                "--model", modelPath,
                "--port", "8085",
                "--host", "127.0.0.1",
                "--n-gpu-layers", "0"  // CPU-only for shard mode
            )
            processBuilder.directory(context.filesDir)
            processBuilder.redirectErrorStream(true)

            activeProcess = processBuilder.start()
            Log.i(tag, "prima.cpp (llama-server) started")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start prima.cpp: ${e.message}")
            activeProcess = null
        }
    }

    private fun startOlliteRT() {
        val modelPath = modelFile?.absolutePath ?: return
        val binaryPath = binaryDir().resolve("libollitert.so").absolutePath

        if (!File(binaryPath).exists()) {
            Log.e(tag, "libollitert.so not found at $binaryPath — check extern/ollitert/README.md")
            return
        }

        try {
            val processBuilder = ProcessBuilder(
                binaryPath,
                "--model", modelPath,
                "--port", "8085",
                "--host", "127.0.0.1"
            )
            processBuilder.directory(context.filesDir)
            processBuilder.redirectErrorStream(true)

            activeProcess = processBuilder.start()
            Log.i(tag, "OlliteRT started")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start OlliteRT: ${e.message}")
            activeProcess = null
        }
    }
}
