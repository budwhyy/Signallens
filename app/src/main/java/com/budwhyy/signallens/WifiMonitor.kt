package com.budwhyy.signallens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Reports the signal strength of one Wi-Fi network: the one the phone is connected to (default),
 * or any nearby network chosen with [follow].
 *
 * The connected network's RSSI is refreshed by Android every second or so. Other networks are only
 * seen through Wi-Fi scans, which Android limits to 4 per 2 minutes per app unless
 * "Wi-Fi scan throttling" is turned off in Developer options.
 */
class WifiMonitor(context: Context) {

    /** Strongest access point of one network in the latest scan. [key] is the SSID, or BSSID if hidden. */
    data class Network(
        val key: String,
        val ssid: String?,
        val bssid: String,
        val rssi: Int,
        val frequencyMhz: Int,
        val seenAtMs: Long, // SystemClock.elapsedRealtime() when this reading was taken
    )

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val location = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    /** Network being followed: null for the connected one, otherwise a [Network.key]. */
    @Volatile var targetKey: String? = null
        private set
    @Volatile private var targetSsid: String? = null

    /** Latest RSSI in dBm, or null when not connected / not seen in the latest scan. */
    @Volatile var rssi: Int? = null
        private set
    /** Network name; null if unknown (needs location permission + location turned on). */
    @Volatile var ssid: String? = null
        private set
    @Volatile var bssid: String? = null
        private set
    /** Name of the network the phone is connected to, whichever network is being followed. */
    @Volatile var connectedSsid: String? = null
        private set
    @Volatile var frequencyMhz: Int? = null
        private set
    @Volatile var linkMbps: Int? = null
        private set
    /** Changes whenever a new reading arrives, so callers can avoid re-using an old scan value. */
    @Volatile var readingStamp = 0L
        private set
    /** When the current reading was taken (elapsedRealtime ms). */
    @Volatile private var readingAtMs = 0L

    /** Every network in range, strongest first. */
    @Volatile var nearby: List<Network> = emptyList()
        private set
    /** True when Android refused the last scan request because of scan throttling. */
    @Volatile var scanThrottled = false
        private set

    val locationEnabled: Boolean get() = LocationManagerCompat.isLocationEnabled(location)
    val readingAgeMs: Long get() = SystemClock.elapsedRealtime() - readingAtMs

    private var lastScanRequestMs = 0L
    private var receiverRegistered = false
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = readScan()
    }

    private val poll = object : Runnable {
        override fun run() {
            read()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                app, scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        readScan() // show whatever the system scanned recently straight away
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        if (receiverRegistered) {
            app.unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
    }

    /** Follow [network], or the connected network when null. */
    fun follow(network: Network?) {
        targetKey = network?.key
        targetSsid = network?.ssid
        read()
    }

    private fun read() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRequestMs >= SCAN_INTERVAL_MS) {
            lastScanRequestMs = now
            @Suppress("DEPRECATION") // still the only way for apps to request a scan
            scanThrottled = try { !wifi.startScan() } catch (e: SecurityException) { false }
        }
        if (targetKey == null) readConnected() else readTarget()
        connectedSsid = if (targetKey == null) ssid else connectionSsid()
    }

    @Suppress("DEPRECATION")
    private fun connectionSsid(): String? {
        val info = try { wifi.connectionInfo } catch (e: SecurityException) { null } ?: return null
        if (info.bssid == null) return null
        return info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    @Suppress("DEPRECATION") // connectionInfo still returns RSSI on current Android versions.
    private fun readConnected() {
        val info = try { wifi.connectionInfo } catch (e: SecurityException) { null }
        if (info == null || !wifi.isWifiEnabled || info.bssid == null || info.rssi <= -127) {
            rssi = null; ssid = null; bssid = null; frequencyMhz = null; linkMbps = null
            return
        }
        rssi = info.rssi
        bssid = info.bssid
        frequencyMhz = info.frequency.takeIf { it > 0 }
        linkMbps = info.linkSpeed.takeIf { it > 0 }
        ssid = info.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        readingAtMs = SystemClock.elapsedRealtime()
        readingStamp++
    }

    private fun readTarget() {
        val n = nearby.firstOrNull { it.key == targetKey }
        ssid = targetSsid
        linkMbps = null
        if (n == null) {
            rssi = null; bssid = null; frequencyMhz = null
            return
        }
        rssi = n.rssi
        bssid = n.bssid
        frequencyMhz = n.frequencyMhz.takeIf { it > 0 }
        if (n.seenAtMs != readingAtMs) {
            readingAtMs = n.seenAtMs
            readingStamp++
        }
    }

    private fun readScan() {
        val results = try { wifi.scanResults } catch (e: SecurityException) { null } ?: emptyList()
        val now = SystemClock.elapsedRealtime()
        nearby = results.mapNotNull { r ->
            val apBssid = r.BSSID ?: return@mapNotNull null
            @Suppress("DEPRECATION") // wifiSsid needs API 33; SSID is fine for display
            val name = r.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
            Network(name ?: apBssid, name, apBssid, r.level, r.frequency, r.timestamp / 1000)
        }
            .filter { now - it.seenAtMs < MAX_SCAN_AGE_MS }
            .groupBy { it.key }
            .map { (_, aps) -> aps.maxBy { it.rssi } } // mesh/multi-AP networks: use the strongest
            .sortedByDescending { it.rssi }
        if (targetKey != null) readTarget()
    }

    fun bandLabel(): String? = frequencyMhz?.let { band(it) }

    companion object {
        private const val POLL_MS = 400L
        private const val SCAN_INTERVAL_MS = 4_000L
        private const val MAX_SCAN_AGE_MS = 120_000L

        fun band(frequencyMhz: Int): String = when {
            frequencyMhz < 3000 -> "2.4 GHz"
            frequencyMhz < 5925 -> "5 GHz"
            else -> "6 GHz"
        }
    }
}
