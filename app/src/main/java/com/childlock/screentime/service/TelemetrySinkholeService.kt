package com.childlock.screentime.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.security.AuditLedger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TelemetrySinkholeService : VpnService(), Runnable {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var executor: ExecutorService? = null
    private var isRunning = false
    private lateinit var prefsManager: PreferencesManager

    companion object {
        const val ACTION_START_SINKHOLE = "com.childlock.action.START_SINKHOLE"
        const val ACTION_STOP_SINKHOLE = "com.childlock.action.STOP_SINKHOLE"
        private const val TAG = "TelemetrySinkhole"

        // Authoritative blacklist of system telemetry, tracking beacons, and analytics domains
        private val BLOCKED_TRACKER_DOMAINS = arrayOf(
            // Google Telemetry & Ads
            "google-analytics.com",
            "analytics.google.com",
            "app-measurement.com",
            "googletagmanager.com",
            "doubleclick.net",
            "googleadservices.com",
            "crashlytics.com",
            "firebase-settings.crashlytics.com",
            "play.googleapis.com/log",
            // Samsung Telemetry & Ads
            "samsungadhub.com",
            "samsunganalytics.com",
            "metrics.samsung.com",
            "telemetry.samsung.com",
            "samsungosp.com",
            "samsungcloud.com/telemetry",
            // Third-party surveillance & tracking beacons
            "adjust.com",
            "appsflyer.com",
            "branch.io",
            "kochava.com",
            "flurry.com",
            "mixpanel.com",
            "scorecardresearch.com",
            "quantserve.com",
            "pixel.facebook.com",
            "graph.facebook.com",
            "telemetry",
            "analytics"
        )
    }

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SINKHOLE -> {
                stopSinkhole()
                stopSelf()
            }
            else -> {
                startSinkhole()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSinkhole() {
        if (isRunning) return
        isRunning = true

        try {
            val builder = Builder()
                .setSession("Sovereign Anti-Telemetry Sinkhole")
                .addAddress("10.111.0.1", 32)
                .addDnsServer("10.111.0.1")
                .addRoute("10.111.0.1", 32) // Intercept loopback DNS queries

            vpnInterface = builder.establish()
            executor = Executors.newSingleThreadExecutor()
            executor?.execute(this)

            prefsManager.auditLedger.logEvent(
                AuditLedger.EVENT_LOCK_STARTED,
                "Anti-Telemetry & System Tracker Sinkhole engaged"
            )
            Log.i(TAG, "Anti-Telemetry sinkhole active. All system trackers blocked.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sinkhole VPN interface: ${e.message}")
            isRunning = false
        }
    }

    override fun run() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        val packet = ByteBuffer.allocate(32767)

        val socket = DatagramSocket()

        while (isRunning) {
            try {
                packet.clear()
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    packet.limit(length)
                    // Inspect IP packet
                    val isUdp = packet.get(9).toInt() == 17
                    if (isUdp) {
                        val destPort = ((packet.get(22).toInt() and 0xFF) shl 8) or (packet.get(23).toInt() and 0xFF)
                        if (destPort == 53) {
                            // Extract DNS query domain
                            val domain = extractDomainFromDnsPacket(packet.array(), 28, length - 28)
                            if (domain != null && isTrackerDomain(domain)) {
                                // SINKHOLE: Drop tracker and respond with 0.0.0.0
                                prefsManager.auditLedger.logEvent(
                                    AuditLedger.EVENT_APP_BLOCKED,
                                    "Telemetry Dropped: $domain"
                                )
                                Log.w(TAG, "BLOCKED TRACKER DOMAIN: $domain")
                                val sinkholeResponse = buildSinkholeDnsResponse(packet.array(), length)
                                if (sinkholeResponse != null) {
                                    outputStream.write(sinkholeResponse)
                                }
                                continue
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) break
            }
        }

        try {
            socket.close()
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun isTrackerDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        return BLOCKED_TRACKER_DOMAINS.any { lower.contains(it) }
    }

    private fun extractDomainFromDnsPacket(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 12) return null
        var pos = offset + 12 // Skip DNS header
        val domainBuilder = StringBuilder()

        while (pos < offset + length) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            pos++
            if (pos + len > data.size) break
            if (domainBuilder.isNotEmpty()) domainBuilder.append(".")
            for (i in 0 until len) {
                domainBuilder.append(data[pos + i].toInt().toChar())
            }
            pos += len
        }

        return if (domainBuilder.isEmpty()) null else domainBuilder.toString()
    }

    /**
     * Synthesizes an instantaneous DNS response pointing to 0.0.0.0 (Blackhole).
     */
    private fun buildSinkholeDnsResponse(ipPacket: ByteArray, length: Int): ByteArray? {
        if (length < 28) return null
        val response = ipPacket.copyOf(length + 16)

        // Swap IP src and dst
        for (i in 0 until 4) {
            val tmp = response[12 + i]
            response[12 + i] = response[16 + i]
            response[16 + i] = tmp
        }

        // Swap UDP src and dst port
        val tmpPort0 = response[20]
        val tmpPort1 = response[21]
        response[20] = response[22]
        response[21] = response[23]
        response[22] = tmpPort0
        response[23] = tmpPort1

        // DNS flags: Standard query response, No error
        val dnsOffset = 28
        if (length >= dnsOffset + 12) {
            response[dnsOffset + 2] = 0x81.toByte() // Response flag
            response[dnsOffset + 3] = 0x80.toByte()
            response[dnsOffset + 6] = 0x00 // Answer count = 1
            response[dnsOffset + 7] = 0x01.toByte()

            // Append A-record pointing to 0.0.0.0
            val ansPos = length
            response[ansPos] = 0xC0.toByte() // Pointer to domain name
            response[ansPos + 1] = 0x0C.toByte()
            response[ansPos + 2] = 0x00 // Type A
            response[ansPos + 3] = 0x01
            response[ansPos + 4] = 0x00 // Class IN
            response[ansPos + 5] = 0x01
            response[ansPos + 6] = 0x00 // TTL = 60s
            response[ansPos + 7] = 0x00
            response[ansPos + 8] = 0x00
            response[ansPos + 9] = 0x3C
            response[ansPos + 10] = 0x00 // Data len = 4
            response[ansPos + 11] = 0x04
            response[ansPos + 12] = 0x00 // 0.0.0.0 (Sinkhole)
            response[ansPos + 13] = 0x00
            response[ansPos + 14] = 0x00
            response[ansPos + 15] = 0x00

            // Update IP length & UDP length
            val totalLen = length + 16
            response[2] = ((totalLen shr 8) and 0xFF).toByte()
            response[3] = (totalLen and 0xFF).toByte()

            val udpLen = totalLen - 20
            response[24] = ((udpLen shr 8) and 0xFF).toByte()
            response[25] = (udpLen and 0xFF).toByte()
            response[26] = 0 // Checksum zero
            response[27] = 0
            return response
        }
        return null
    }

    private fun stopSinkhole() {
        isRunning = false
        executor?.shutdownNow()
        executor = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // Ignored
        }
        vpnInterface = null
        Log.i(TAG, "Anti-Telemetry sinkhole stopped.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSinkhole()
    }
}
