package org.phononai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadStatus: TextView
    private lateinit var modelPath: EditText
    private lateinit var contextField: EditText
    private lateinit var threadsField: EditText
    private lateinit var portField: EditText
    private lateinit var backendPortField: EditText
    private lateinit var bindAddressField: EditText
    private lateinit var thermalStopField: EditText
    private lateinit var thermalResumeField: EditText
    private lateinit var aliasField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var downloadUrlField: EditText
    private lateinit var keepWarmIntervalField: EditText
    private lateinit var loadModeField: EditText
    private lateinit var keepWarmBox: CheckBox
    private lateinit var reasoningBox: CheckBox

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        downloadProgress = findViewById(R.id.download_progress)
        downloadStatus = findViewById(R.id.download_status)
        modelPath = findViewById(R.id.model_path)
        contextField = findViewById(R.id.context)
        threadsField = findViewById(R.id.threads)
        portField = findViewById(R.id.port)
        backendPortField = findViewById(R.id.backend_port)
        bindAddressField = findViewById(R.id.bind_address)
        thermalStopField = findViewById(R.id.thermal_stop)
        thermalResumeField = findViewById(R.id.thermal_resume)
        aliasField = findViewById(R.id.alias)
        apiKeyField = findViewById(R.id.api_key)
        downloadUrlField = findViewById(R.id.download_url)
        keepWarmIntervalField = findViewById(R.id.keep_warm_interval)
        loadModeField = findViewById(R.id.load_mode)
        keepWarmBox = findViewById(R.id.keep_warm)
        reasoningBox = findViewById(R.id.reasoning)

        loadSettingsIntoFields()

        findViewById<Button>(R.id.start).setOnClickListener {
            send(PhononService.ACTION_START_SERVER)
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            send(PhononService.ACTION_STOP_SERVER)
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { render() }
        findViewById<Button>(R.id.download).setOnClickListener {
            saveSettings(restart = false)
            send(PhononService.ACTION_DOWNLOAD, downloadUrlField.text.toString().trim())
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            if (saveSettings(restart = true)) {
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        startForegroundService(Intent(this, PhononService::class.java))
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun send(action: String, url: String? = null) {
        val intent = Intent(this, PhononService::class.java).setAction(action)
        if (url != null) intent.putExtra(PhononService.EXTRA_URL, url)
        startForegroundService(intent)
    }

    private fun loadSettingsIntoFields() {
        val settings = Settings(this)
        modelPath.setText(settings.modelPath)
        contextField.setText(settings.contextSize.toString())
        threadsField.setText(settings.threads.toString())
        portField.setText(settings.port.toString())
        backendPortField.setText(settings.backendPort.toString())
        bindAddressField.setText(settings.bindAddress)
        thermalStopField.setText(settings.thermalStopC.toString())
        thermalResumeField.setText(settings.thermalResumeC.toString())
        aliasField.setText(settings.alias)
        apiKeyField.setText(settings.apiKey)
        downloadUrlField.setText(settings.downloadUrl)
        keepWarmIntervalField.setText(settings.keepWarmIntervalS.toString())
        loadModeField.setText(settings.loadMode)
        keepWarmBox.isChecked = settings.keepWarm
        reasoningBox.isChecked = settings.reasoning
    }

    private fun saveSettings(restart: Boolean): Boolean {
        val settings = Settings(this)
        val contextSize = contextField.text.toString().toIntOrNull()
        val threads = threadsField.text.toString().toIntOrNull()
        val port = portField.text.toString().toIntOrNull()
        val backendPort = backendPortField.text.toString().toIntOrNull()
        val stop = thermalStopField.text.toString().toFloatOrNull()
        val resume = thermalResumeField.text.toString().toFloatOrNull()
        val keepWarmInterval = keepWarmIntervalField.text.toString().toIntOrNull()
        if (contextSize == null || threads == null || port == null || backendPort == null ||
            stop == null || resume == null || keepWarmInterval == null
        ) {
            Toast.makeText(this, "Numeric settings are invalid", Toast.LENGTH_SHORT).show()
            return false
        }
        if (keepWarmInterval < Settings.MIN_KEEP_WARM_INTERVAL_S) {
            Toast.makeText(
                this,
                "Keep-warm interval must be >= ${Settings.MIN_KEEP_WARM_INTERVAL_S}s",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        val loadMode = loadModeField.text.toString().trim().lowercase()
        if (loadMode !in Settings.LOAD_MODES) {
            Toast.makeText(this, "Load mode must be mmap, mlock or none", Toast.LENGTH_SHORT).show()
            return false
        }
        if (stop <= resume) {
            Toast.makeText(this, "Pause threshold must be above resume threshold", Toast.LENGTH_SHORT).show()
            return false
        }
        if (port == backendPort) {
            Toast.makeText(this, "API and backend ports must differ", Toast.LENGTH_SHORT).show()
            return false
        }
        val bindAddress = bindAddressField.text.toString().trim()
        Settings.validateBindAddress(bindAddress)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return false
        }
        val apiKey = apiKeyField.text.toString()
        if (!Settings.isLoopbackBind(bindAddress) && apiKey.isBlank()) {
            Toast.makeText(
                this,
                "API key is required when bind address is not 127.0.0.1",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        val alias = aliasField.text.toString().trim().ifBlank { Settings.DEFAULT_ALIAS }
        val downloadUrl = downloadUrlField.text.toString().trim()
        // Apply through the service so the same prospective validation and
        // listener teardown used by POST /settings also covers the activity.
        // If the service is not up yet there is no listener to race, so a
        // direct write is safe.
        val service = AppState.service
        if (service != null) {
            val body = JSONObject().apply {
                put("model_path", modelPath.text.toString().trim())
                put("context", contextSize)
                put("threads", threads)
                put("bind_address", bindAddress)
                put("port", port)
                put("backend_port", backendPort)
                put("thermal_stop_c", stop.toDouble())
                put("thermal_resume_c", resume.toDouble())
                put("alias", alias)
                put("api_key", apiKey)
                put("download_url", downloadUrl)
                put("keep_warm_interval_s", keepWarmInterval)
                put("load_mode", loadMode)
                put("keep_warm", keepWarmBox.isChecked)
                put("reasoning", reasoningBox.isChecked)
            }
            val result = service.updateSettings(body.toString())
            if (result.contains("\"error\"")) {
                val message = try {
                    JSONObject(result).optString("error")
                } catch (_: Throwable) {
                    result
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return false
            }
        } else {
            settings.modelPath = modelPath.text.toString().trim()
            settings.contextSize = contextSize
            settings.threads = threads
            settings.bindAddress = bindAddress
            settings.port = port
            settings.backendPort = backendPort
            settings.thermalStopC = stop
            settings.thermalResumeC = resume
            settings.alias = alias
            settings.apiKey = apiKey
            settings.downloadUrl = downloadUrl
            settings.keepWarmIntervalS = keepWarmInterval
            settings.loadMode = loadMode
            settings.keepWarm = keepWarmBox.isChecked
            settings.reasoning = reasoningBox.isChecked
            if (restart) send(PhononService.ACTION_RELOAD)
        }
        return true
    }

    private fun render() {
        val service = AppState.service
        if (service == null) {
            statusView.text = "service starting…"
            logView.text = ""
            downloadStatus.text = "idle"
            downloadProgress.isIndeterminate = false
            downloadProgress.progress = 0
            return
        }
        statusView.text = try {
            val h = JSONObject(service.uiHealthJson())
            buildString {
                append("state:        ").append(h.optString("state")).append('\n')
                append("model:        ").append(h.optString("model")).append('\n')
                append("pid:          ").append(nullable(h, "pid")).append('\n')
                append("uptime_s:     ").append(nullable(h, "uptime_s")).append('\n')
                append("battery:      ").append(nullable(h, "battery_temp_c")).append(" °C\n")
                append("thermal_pause:").append(h.optBoolean("thermal_paused")).append('\n')
                append("decode t/s:   ").append(nullable(h, "last_decode_tps")).append('\n')
                append("prompt t/s:   ").append(nullable(h, "last_prompt_tps")).append('\n')
                append("restarts:     ").append(h.optInt("restart_count")).append('\n')
                append("bind:         ").append(h.optString("bind_address"))
                    .append(" exposed=").append(h.optBoolean("exposed_off_host"))
                    .append(" key_set=").append(h.optBoolean("api_key_set")).append('\n')
                append("keep_warm:    ").append(h.optBoolean("keep_warm"))
                    .append(" / ").append(h.optInt("keep_warm_interval_s")).append("s\n")
                append("keepalives:   ").append(h.optInt("keepalive_count"))
                    .append(" (fail ").append(h.optInt("keepalive_failures")).append(")\n")
                append("load mode:    ").append(h.optString("load_mode"))
                    .append(" mlock_refused=").append(h.optBoolean("mlock_refused")).append('\n')
                if (!h.isNull("error")) append("error:        ").append(h.optString("error"))
            }
        } catch (t: Throwable) {
            "cannot read state: ${t.message}"
        }

        val log = service.uiLogs().lines()
        logView.text = log.takeLast(20).joinToString("\n")

        try {
            val d = JSONObject(service.uiDownloadJson())
            val bytes = d.optLong("bytes", 0)
            val total = if (d.isNull("total")) -1L else d.optLong("total", -1)
            when {
                d.optBoolean("active") -> {
                    downloadProgress.isIndeterminate = total <= 0
                    if (total > 0) {
                        downloadProgress.progress = ((bytes * 100) / total).toInt().coerceIn(0, 100)
                    }
                    downloadStatus.text = buildString {
                        append("downloading ").append(mb(bytes))
                        if (total > 0) append(" / ").append(mb(total))
                        append(" (${if (total > 0) bytes * 100 / total else 0}%)")
                    }
                }

                !d.isNull("error") -> {
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = 0
                    downloadStatus.text = "download failed: ${d.optString("error")}"
                }

                d.optBoolean("done") -> {
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = 100
                    downloadStatus.text = "downloaded ${mb(bytes)} → ${d.optString("dest")}"
                }

                else -> {
                    downloadProgress.isIndeterminate = false
                    downloadProgress.progress = 0
                    downloadStatus.text = "no download yet"
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun nullable(json: JSONObject, key: String): String =
        if (json.isNull(key)) "—" else json.opt(key).toString()

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
}
