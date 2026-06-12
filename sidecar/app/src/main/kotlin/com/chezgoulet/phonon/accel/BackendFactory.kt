package com.chezgoulet.phonon.accel

import android.util.Log
import com.google.ai.edge.litertlm.Backend

/**
 * BackendFactory maps the backend names produced by [BackendPlanner] onto
 * LiteRT-LM Backend instances.
 *
 * ── THIS IS THE ONE FILE THAT TOUCHES THE ACCELERATOR API ──
 *
 * Everything else (planner, ModelManager fallback loop, telemetry, the
 * coordinator config) is SDK-version-independent. If the pinned LiteRT-LM
 * version changes its Backend surface, this file is the only thing that
 * needs updating — and `create()` returning null simply causes the fallback
 * chain to move on, so an SDK drift degrades to GPU/CPU instead of crashing.
 *
 * NPU note: NPU support in LiteRT-LM is delivered per-SoC (Tensor Edge TPU,
 * Qualcomm QNN) and the constructor has moved between releases. We resolve
 * it reflectively so this module compiles against any litertlm version; once
 * the project pins a version with a stable NPU API, replace the reflection
 * in [createNpu] with the direct call and delete the proguard keep rule.
 */
object BackendFactory {
    private const val TAG = "BackendFactory"

    /**
     * Returns a Backend instance for [name] ("npu", "gpu", "cpu"), or null
     * if this SDK version cannot construct it (callers move to the next
     * candidate in the chain).
     */
    fun create(name: String): Backend? = when (name) {
        "cpu" -> Backend.CPU()
        "gpu" -> createGpu()
        "npu" -> createNpu()
        else -> {
            Log.w(TAG, "Unknown backend name: $name")
            null
        }
    }

    private fun createGpu(): Backend? = try {
        Backend.GPU()
    } catch (t: Throwable) {
        // NoSuchMethodError / LinkageError if the pinned SDK lacks GPU;
        // runtime init failures are caught later by ModelManager.
        Log.w(TAG, "GPU backend unavailable in this LiteRT-LM build: ${t.message}")
        null
    }

    /**
     * Resolves Backend.NPU reflectively.
     *
     * Tries, in order:
     *  1. com.google.ai.edge.litertlm.Backend$NPU no-arg constructor
     *  2. Backend.NPU as a Kotlin `object` (INSTANCE field)
     *
     * Returns null if neither exists — i.e., the pinned litertlm version
     * does not ship an NPU backend — and the chain falls through to GPU.
     */
    private fun createNpu(): Backend? {
        return try {
            val cls = Class.forName("com.google.ai.edge.litertlm.Backend\$NPU")

            // Case 1: class with a no-arg constructor — Backend.NPU()
            val ctor = cls.constructors.firstOrNull { it.parameterCount == 0 }
            if (ctor != null) {
                return ctor.newInstance() as? Backend
            }

            // Case 2: Kotlin object — Backend.NPU.INSTANCE
            val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
            instance as? Backend
        } catch (t: Throwable) {
            Log.i(TAG, "NPU backend not present in this LiteRT-LM build (${t.javaClass.simpleName}) — will fall back")
            null
        }
    }
}
