package org.phononai.app

/**
 * Dev harness: runs the real [LiteLlmRegistrar] against a live gateway.
 *
 *     GATEWAY_KEY=... ./app/gradlew -p app :app:gatewayProbe \
 *       -PgatewayUrl=http://127.0.0.1:4001 -PnodeApiBase=http://host.docker.internal:18099/v1
 *
 * Prints the outcome of check / register / register-again / deregister. The key
 * comes from the environment and is never printed or echoed in an error: the
 * registrar redacts it from anything the gateway sends back.
 *
 * This is a test tool: it lives in the test source set and is not shipped.
 */
object GatewayProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = args.getOrNull(0)
            ?: System.getProperty("gatewayUrl")
            ?: error("usage: GatewayProbe <gateway-url>")
        val key = System.getenv("GATEWAY_KEY") ?: error("GATEWAY_KEY is not set")
        val apiBase = args.getOrNull(1)
            ?: System.getProperty("nodeApiBase")
            ?: "http://host.docker.internal:18099/v1"
        val modelName = System.getProperty("modelName") ?: "phone-probe0042"

        val registrar = LiteLlmRegistrar(
            baseUrl = url,
            masterKey = key,
            modelName = modelName,
            servedModel = "qwen3.5-2b",
            apiBase = apiBase,
            nodeKey = System.getenv("NODE_KEY") ?: "probe-node-key",
        )

        println("gateway: $url")
        println("check:      ${render(registrar.check())}")
        println("register:   ${render(registrar.register())}")
        println("again:      ${render(registrar.register())}")
        if (System.getProperty("keep") == "true") {
            println("deregister: skipped (keep=true); the entry stays for inspection")
        } else {
            println("deregister: ${render(registrar.deregister())}")
        }
    }

    private fun render(result: GatewayResult): String = when (result) {
        is GatewayResult.Ok -> "OK"
        is GatewayResult.Failure ->
            "FAILED: ${result.message}" + (result.hint?.let { " | hint: $it" } ?: "")
    }
}
