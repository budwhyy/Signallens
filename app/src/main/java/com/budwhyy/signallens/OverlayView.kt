package com.budwhyy.signallens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** A signal sample projected onto the screen for the current frame. */
data class ScreenMarker(val x: Float, val y: Float, val radius: Float, val depth: Float, val rssi: Int)

/**
 * Transparent layer on top of the camera that draws the signal bubbles and a centre reticle.
 * Markers are computed on the GL thread each frame and handed over here.
 */
class OverlayView(context: Context) : View(context) {

    @Volatile private var markers: List<ScreenMarker> = emptyList()
    @Volatile var liveRssi: Int? = null

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    fun setMarkers(list: List<ScreenMarker>) {
        markers = list
        postInvalidateOnAnimation()
    }

    /** Phone-style signal bars (4 bars, [level] of them lit) centred in a bubble. */
    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, radius: Float, level: Int) {
        val w = radius * 1.0f
        val h = radius * 0.75f
        val gap = w * 0.08f
        val barW = (w - gap * 3) / 4
        val left = cx - w / 2
        val bottom = cy + h / 2
        for (i in 0 until 4) {
            bar.color = if (i < level) Color.WHITE else Color.argb(90, 255, 255, 255)
            val x = left + i * (barW + gap)
            val top = bottom - h * (i + 1) / 4f
            canvas.drawRoundRect(x, top, x + barW, bottom, barW * 0.25f, barW * 0.25f, bar)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Far markers first so near ones sit on top.
        for (m in markers) {
            val c = SignalScale.color(m.rssi)
            fill.color = c
            fill.alpha = 150
            stroke.color = c
            canvas.drawCircle(m.x, m.y, m.radius, fill)
            canvas.drawCircle(m.x, m.y, m.radius, stroke)
            if (m.radius > 14f * density) drawBars(canvas, m.x, m.y, m.radius, SignalScale.level(m.rssi))
        }

        // Centre reticle coloured by the live signal at the phone's current position.
        val cx = width / 2f
        val cy = height / 2f
        val r = 22f * density
        val live = liveRssi
        reticle.color = if (live != null) SignalScale.color(live) else Color.GRAY
        canvas.drawCircle(cx, cy, r, reticle)
        canvas.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, reticle)
        canvas.drawLine(cx, cy - r * 0.4f, cx, cy + r * 0.4f, reticle)
    }
}
