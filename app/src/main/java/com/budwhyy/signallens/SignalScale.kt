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

    /** 0..4, matching the [LABELS] index. */
    fun level(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -72 -> 2
        rssi >= -80 -> 1
        else -> 0
    }

    fun label(rssi: Int): String = LABELS[level(rssi)]

    /** What the signal is good for, in everyday terms. */
    fun meaning(rssi: Int): String = when (level(rssi)) {
        4 -> "Great for video calls and 4K streaming"
        3 -> "Good for streaming and video calls"
        2 -> "Fine for browsing, video may stutter"
        1 -> "Slow, and may drop out"
        else -> "Wi-Fi will barely work here"
    }

    /** Representative colour of each level, for the colour key. */
    fun levelColor(level: Int): Int = color(intArrayOf(-90, -76, -68, -60, -45)[level])

    /** Weakest to strongest. */
    val LABELS = listOf("Dead zone", "Weak", "Fair", "Good", "Excellent")

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)),
        )
    }
}
