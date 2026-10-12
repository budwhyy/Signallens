package com.budwhyy.signallens

import android.graphics.Color

/** Maps Wi-Fi signal strength (RSSI, dBm) to a colour and a plain-language label. */
object SignalScale {

    // dBm stop -> colour. Between stops the colour is blended.
    private val stops = listOf(
        -45 to Color.rgb(0x30, 0xA4, 0x6C), // green: excellent
        -60 to Color.rgb(0x9B, 0xCB, 0x3A), // lime: good
        -70 to Color.rgb(0xF5, 0xA5, 0x24), // amber: fair
        -80 to Color.rgb(0xE5, 0x48, 0x4D), // red: weak
        -90 to Color.rgb(0x7A, 0x1B, 0x2B), // dark red: dead zone
    )

    fun color(rssi: Int): Int {
        if (rssi >= stops.first().first) return stops.first().second
        if (rssi <= stops.last().first) return stops.last().second
        for (i in 0 until stops.size - 1) {
            val (hiDbm, hiColor) = stops[i]
            val (loDbm, loColor) = stops[i + 1]
            if (rssi <= hiDbm && rssi >= loDbm) {
                val t = (hiDbm - rssi).toFloat() / (hiDbm - loDbm)
                return blend(hiColor, loColor, t)
            }
        }
        return stops.last().second
    }

    fun label(rssi: Int): String = when {
        rssi >= -55 -> "Excellent"
        rssi >= -65 -> "Good"
        rssi >= -72 -> "Fair"
        rssi >= -80 -> "Weak"
        else -> "Dead zone"
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)),
        )
    }
}
