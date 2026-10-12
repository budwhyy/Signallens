package com.budwhyy.signallens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    fun setMarkers(list: List<ScreenMarker>) {
        markers = list
        postInvalidateOnAnimation()
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
            if (m.radius > 18f * density) {
                text.textSize = (m.radius * 0.55f).coerceIn(10f * density, 22f * density)
                canvas.drawText("${m.rssi}", m.x, m.y + text.textSize / 3f, text)
            }
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
