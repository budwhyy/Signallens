package com.budwhyy.signallens

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Polls the signal strength of the Wi-Fi network the phone is connected to.
 *
 * Note: Android refreshes this value itself every second or few seconds, so polling faster
 * than that just returns the same number. Walking slowly gives the most accurate map.
 */
class WifiMonitor(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    /** Latest RSSI in dBm, or null when not connected to Wi-Fi. */
    @Volatile var rssi: Int? = null
        private set
    /** Network name; null if unknown (needs location permission + location turned on). */
    @Volatile var ssid: String? = null
        private set
    @Volatile var frequencyMhz: Int? = null
        private set
    @Volatile var linkMbps: Int? = null
        private set

    private val poll = object : Runnable {
        override fun run() {
            read()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun stop() = handler.removeCallbacks(poll)

    @Suppress("DEPRECATION") // connectionInfo still returns RSSI on current Android versions.
    private fun read() {
        val info = try { wifi.connectionInfo } catch (e: SecurityException) { null }
        if (info == null || !wifi.isWifiEnabled || info.bssid == null || info.rssi <= -127) {
            rssi = null; ssid = null; frequencyMhz = null; linkMbps = null
            return
        }
        rssi = info.rssi
        frequencyMhz = info.frequency.takeIf { it > 0 }
        linkMbps = info.linkSpeed.takeIf { it > 0 }
        ssid = info.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    fun bandLabel(): String? = frequencyMhz?.let {
        when {
            it < 3000 -> "2.4 GHz"
            it < 5925 -> "5 GHz"
            else -> "6 GHz"
        }
    }

    companion object {
        private const val POLL_MS = 400L
    }
}
