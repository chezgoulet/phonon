package com.chezgoulet.phonon.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Announces this phone on the local network via mDNS on _phonon._tcp.
 *
 * Uses Android's built-in NSD API — no Play Services required.
 * TXT records carry device_id and device_model so the coordinator
 * can identify phones before pairing.
 */
class MDNSAnnouncer(
    private val context: Context,
    private val deviceId: String,
    private val deviceModel: String
) {
    private val tag = "MDNSAnnouncer"
    private var nsdManager: NsdManager? = null
    private var registered = false

    private val serviceType = "_phonon._tcp"
    private val serviceName = "Phonon Worker - ${deviceId.takeLast(8)}"

    /**
     * Start mDNS announcement on a background thread.
     */
    fun start() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                Log.w(tag, "NSD not available on this device")
                return
            }

            val serviceInfo = NsdServiceInfo().apply {
                serviceType = this@MDNSAnnouncer.serviceType
                serviceName = this@MDNSAnnouncer.serviceName
                port = 0 // No port needed — coordinator uses REST discovery

                // TXT records for coordinator discovery
                setAttribute("device_id", deviceId)
                setAttribute("device_model", deviceModel)
            }

            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo?) {
                        registered = true
                        Log.i(tag, "mDNS registered: ${info?.serviceName} ($deviceModel)")
                    }

                    override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                        Log.w(tag, "mDNS registration failed: errorCode=$errorCode")
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo?) {
                        registered = false
                        Log.i(tag, "mDNS unregistered")
                    }

                    override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                        Log.w(tag, "mDNS unregistration failed: errorCode=$errorCode")
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.e(tag, "mDNS permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "mDNS start failed: ${e.message}")
        }
    }

    /**
     * Stop mDNS announcement.
     */
    fun stop() {
        if (registered && nsdManager != null) {
            try {
                nsdManager?.unregisterService(
                    object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(info: NsdServiceInfo?) {}
                        override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceUnregistered(info: NsdServiceInfo?) {
                            Log.i(tag, "mDNS unregistered")
                        }
                        override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                            Log.w(tag, "mDNS unregistration failed: $errorCode")
                        }
                    }
                )
            } catch (_: Exception) {}
            registered = false
        }
    }
}
