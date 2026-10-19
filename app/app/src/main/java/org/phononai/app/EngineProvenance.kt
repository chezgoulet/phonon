package org.phononai.app

import org.json.JSONObject

/**
 * Which engine this build carries and how the server talks to the model.
 *
 * Both values are build-time constants (see app/build.gradle.kts): the
 * revision is the pinned llama.cpp submodule's HEAD read at configure
 * time, so a report can never name an engine other than the one that was
 * compiled. Nothing here is a literal that could drift from the submodule.
 *
 * The server runs `llama-server --jinja` and lets the model's own chat
 * template format the prompt; no template file is passed. [templateMode]
 * and [templateSource] state that as data, so a reviewer can tell whether
 * a custom template was ever in play, and which one.
 */
object EngineProvenance {

    /** The pinned llama.cpp revision, e.g. "797de63b2f", or "unknown". */
    val engineRev: String = BuildConfig.ENGINE_REV

    /** How prompts are formatted: "jinja" today. */
    val templateMode: String = BuildConfig.TEMPLATE_MODE

    /** Where the template comes from: "model" (the model's own), or a file. */
    val templateSource: String = BuildConfig.TEMPLATE_SOURCE

    /**
     * The provenance as JSON, for the device report and /health to embed
     * verbatim. One source means the two surfaces cannot disagree.
     */
    fun fields(): JSONObject = JSONObject().apply {
        put("engine_rev", engineRev)
        put("template_mode", templateMode)
        put("template_source", templateSource)
    }
}
