package com.chezgoulet.phonon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chezgoulet.phonon.PhononService

/**
 * Mutable state holder that reflects PhononService state.
 *
 * The CompanionApp connects to PhononService via a binding and updates
 * this object's fields. Compose recomposes whenever any observed field
 * changes.
 */
class PhononServiceState {
    /** Connection status text from PhononService. */
    var connectionStatus by mutableStateOf("connecting")
        private set

    /** Coordinator host:port. */
    var coordinatorHost by mutableStateOf("discovering…")
        private set
    var coordinatorPort by mutableStateOf(8080)
        private set

    /** Device identity. */
    var deviceId by mutableStateOf("")
        private set

    /** Currently loaded model name (or null). */
    var loadedModel by mutableStateOf<String?>(null)
        private set

    /** Engine in use (always litert-lm with LiteRT-LM SDK). */
    var engine by mutableStateOf("")
        private set

    /** Battery level 0..100, -1 if unknown. */
    var batteryLevel by mutableStateOf(-1)
        private set

    /** Battery temperature in Celsius, -1 if unknown. */
    var batteryTemp by mutableStateOf(-1f)
        private set

    /** Whether the device is charging. */
    var isCharging by mutableStateOf(false)
        private set

    /** Whether inference is currently in progress. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Inference token count from the last request. */
    var lastTokens by mutableStateOf(0)
        private set

    /** True when paired with a coordinator. */
    var isPaired by mutableStateOf(false)
        private set

    /** Uptime in seconds since service start. */
    var uptimeSeconds by mutableStateOf(0L)
        private set

    /** Recent log entries (newest first). */
    var logs by mutableStateOf(listOf<LogEntry>())
        private set

    /** Pairing code or QR data. */
    var pairingCode by mutableStateOf("")
        private set

    /** Synchronize all state from a service instance. */
    fun syncFrom(service: PhononService) {
        connectionStatus = service.connectionStatus
        loadedModel = service.loadedModel
        deviceId = (service.application as com.chezgoulet.phonon.PhononApplication).deviceId
        engine = service.loadedModel?.let { "litert-lm" } ?: ""
        batteryLevel = service.batteryLevel.toInt()
        batteryTemp = service.batteryTempC.toFloat()
        isCharging = service.isCharging
        isProcessing = service.isProcessing
        coordinatorHost = service.coordinatorHost
        coordinatorPort = service.coordinatorPort
    }

    /** Add a log entry (newest first, capped at 100). */
    fun addLog(entry: LogEntry) {
        logs = (listOf(entry) + logs).take(100)
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)
