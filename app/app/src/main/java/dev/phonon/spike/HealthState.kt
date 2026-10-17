package dev.phonon.spike

import org.json.JSONObject

/** Result of the single probe execution, shared by the service, activity and HTTP endpoint. */
object HealthState {
    @Volatile var execOk: Boolean = false
    @Volatile var exitCode: Int = -1
    @Volatile var stdout: String = ""
    @Volatile var stderr: String = ""
    @Volatile var abi: String = ""
    @Volatile var pid: Int = -1
    @Volatile var serviceStartedAt: String = ""

    @Synchronized
    fun record(
        execOk: Boolean,
        exitCode: Int,
        stdout: String,
        stderr: String,
        abi: String,
        pid: Int,
    ) {
        this.execOk = execOk
        this.exitCode = exitCode
        this.stdout = stdout
        this.stderr = stderr
        this.abi = abi
        this.pid = pid
    }

    @Synchronized
    fun toJson(): String = JSONObject().apply {
        put("exec_ok", execOk)
        put("exit_code", exitCode)
        put("stdout", stdout)
        put("stderr", stderr)
        put("abi", abi)
        put("pid", pid)
        put("service_started_at", serviceStartedAt)
    }.toString()

    @Synchronized
    fun toPrettyJson(): String = JSONObject().apply {
        put("exec_ok", execOk)
        put("exit_code", exitCode)
        put("stdout", stdout)
        put("stderr", stderr)
        put("abi", abi)
        put("pid", pid)
        put("service_started_at", serviceStartedAt)
    }.toString(2)
}
