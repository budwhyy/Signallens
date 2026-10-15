package com.budwhyy.signallens

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    // ---- AR + rendering ----
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private val background = BackgroundRenderer()
    private var session: Session? = null
    private var installRequested = false
    private var permissionsAsked = false
    private var textureBoundTo: Session? = null
    @Volatile private var viewportW = 0
    @Volatile private var viewportH = 0
    @Volatile private var viewportChanged = false
    @Volatile private var displayRotation = Surface.ROTATION_0

    // ---- Signal data (samples list is only touched on the GL thread) ----
    private lateinit var wifi: WifiMonitor
    private class Sample(val anchor: Anchor, val rssi: Int, val timeMs: Long)
    private val samples = ArrayList<Sample>()
    private var lastDropPos: FloatArray? = null
    private var lastDropTime = 0L
    @Volatile private var lastDropStamp = -1L

    /** Thread-safe copy of the samples for stats and CSV export. */
    private data class Row(
        val timeMs: Long, val x: Float, val y: Float, val z: Float, val rssi: Int,
        val ssid: String?, val bssid: String?, val frequencyMhz: Int?,
    )
    private val rows = java.util.Collections.synchronizedList(ArrayList<Row>())

    @Volatile private var autoDrop = true
    @Volatile private var dropRequested = false
    @Volatile private var clearRequested = false
    @Volatile private var trackingMessage: String? = "Starting camera…"

    // ---- UI ----
    private lateinit var signalText: TextView
    private lateinit var meaningText: TextView
    private lateinit var networkText: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var autoButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private val uiTick = object : Runnable {
        override fun run() {
            refreshHud()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wifi = WifiMonitor(this)
        setContentView(buildLayout())
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panel() = GradientDrawable().apply {
        setColor(Color.argb(200, 17, 17, 19))
        cornerRadius = dp(14).toFloat()
    }

    private fun pill(color: Int, radiusDp: Int = 50) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** "● Excellent ● Good …" with each dot in its colour. */
    private fun legendText(): CharSequence = SpannableStringBuilder().apply {
        for (level in 4 downTo 0) {
            if (level < 4) append("   ")
            val dot = length
            append("●")
            setSpan(ForegroundColorSpan(SignalScale.levelColor(level)), dot, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val label = length
            append(" ").append(SignalScale.LABELS[level])
            setSpan(ForegroundColorSpan(Color.rgb(0xD0, 0xD0, 0xD4)), label, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Phone-style bars "▂▄▆█" with [level] of them lit in [color]. */
    private fun bars(level: Int, color: Int): CharSequence = SpannableStringBuilder().apply {
        "▂▄▆█".forEachIndexed { i, c ->
            val start = length
            append(c)
            val tint = if (i < level) color else Color.argb(80, 255, 255, 255)
            setSpan(ForegroundColorSpan(tint), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this)

        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        overlay = OverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // Top card: how good the signal is, in words, and which network.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        signalText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        meaningText = TextView(this).apply { setTextColor(Color.rgb(0xED, 0xED, 0xEF)); textSize = 15f }
        val networkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        networkText = TextView(this).apply {
            setTextColor(Color.rgb(0xB4, 0xB4, 0xB8)); textSize = 14f
            maxLines = 2
            setOnClickListener { showNetworkPicker() }
        }
        val changeButton = TextView(this).apply {
            text = "Change"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = pill(Color.rgb(0x3B, 0x82, 0xF6))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { showNetworkPicker() }
        }
        networkRow.addView(networkText, LinearLayout.LayoutParams(0, -2, 1f))
        networkRow.addView(changeButton)
        statsText = TextView(this).apply {
            setTextColor(Color.rgb(0xB4, 0xB4, 0xB8)); textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        statusText = TextView(this).apply {
            setTextColor(Color.rgb(0xF5, 0xA5, 0x24)); textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        top.addView(signalText)
        top.addView(meaningText)
        top.addView(networkRow)
        top.addView(statsText)
        top.addView(statusText)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(12), dp(36), dp(12), 0)
        })

        // Bottom: colour key, then the buttons.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val legend = TextView(this).apply {
            text = legendText()
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = pill(color, radiusDp = 12)
            minHeight = dp(48)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
        val grey = Color.rgb(0x3A, 0x3A, 0x40)
        autoButton = button("Pause", grey) {
            autoDrop = !autoDrop
            autoButton.text = if (autoDrop) "Pause" else "Resume"
            refreshHud()
        }
        val dropButton = button("Mark spot", Color.rgb(0x3B, 0x82, 0xF6)) { dropRequested = true }
        val clearButton = button("Start over", grey) { confirmStartOver() }
        val shareButton = button("Share", grey) { shareScan() }
        val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        listOf(autoButton, dropButton, clearButton, shareButton).forEach { buttons.addView(it, lp) }
        bottom.addView(legend)
        bottom.addView(buttons)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(28))
        })

        return root
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        displayRotation = currentRotation()

        if (!hasCamera()) {
            // Ask once; if refused, explain instead of re-prompting in a loop.
            if (!permissionsAsked) {
                permissionsAsked = true
                requestPermissions()
            } else {
                showFatal("Camera permission is needed. Enable it in Settings › Apps › SignalLens.")
            }
            return
        }

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return // we come back to onResume after the Play Store flow
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                    else -> Unit
                }
                session = Session(this).also { s ->
                    val config = Config(s).apply {
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                    }
                    s.configure(config)
                }
            } catch (e: Exception) {
                showFatal("AR isn't available on this device: ${e.javaClass.simpleName}")
                return
            }
        }

        try {
            session?.resume()
        } catch (e: Exception) {
            showFatal("Camera unavailable. Close other camera apps and reopen.")
            session = null
            return
        }
        surfaceView.onResume()
        wifi.start()
        ui.removeCallbacks(uiTick)
        ui.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(uiTick)
        wifi.stop()
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        displayRotation = currentRotation()
        viewportChanged = true // e.g. Z Fold opened/closed
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    // ------------------------------------------------------------------ permissions

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQ_PERMS,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // onResume runs again after the dialog closes: it starts AR if allowed, or explains if not.
    }

    private fun showFatal(message: String) {
        trackingMessage = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshHud()
    }

    // ------------------------------------------------------------------ GL renderer (GL thread)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureBoundTo = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportW = width
        viewportH = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        if (textureBoundTo !== s) {
            s.setCameraTextureName(background.textureId)
            textureBoundTo = s
        }
        if (viewportChanged && viewportW > 0) {
            s.setDisplayGeometry(displayRotation, viewportW, viewportH)
            viewportChanged = false
        }

        val frame = try { s.update() } catch (e: Exception) { return }
        background.draw(frame)

        if (clearRequested) {
            samples.forEach { it.anchor.detach() }
            samples.clear()
            rows.clear()
            lastDropPos = null
            clearRequested = false
        }

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            trackingMessage = when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on a light."
                TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at something with texture (not a blank wall)."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera busy. Close other camera apps."
                else -> "Move the phone slowly to start tracking…"
            }
            overlay.setMarkers(emptyList())
            return
        }
        trackingMessage = null

        val camPos = camera.pose.translation
        val rssi = wifi.rssi
        val now = System.currentTimeMillis()

        // Manual drop: 60 cm in front of the camera.
        if (dropRequested) {
            dropRequested = false
            if (rssi != null) {
                val ahead = camera.displayOrientedPose.compose(Pose.makeTranslation(0f, 0f, -0.6f)).translation
                addSample(s, ahead, rssi, now)
            }
        }

        // Auto drop: every ~40 cm you walk, leave a bubble just below chest height.
        // Other networks only update when a scan lands: one bubble per reading, not repeats of an old one.
        val freshReading = wifi.targetKey == null || wifi.readingStamp != lastDropStamp
        if (autoDrop && rssi != null && freshReading && now - lastDropTime > MIN_DROP_INTERVAL_MS) {
            val last = lastDropPos
            if (last == null || distance(last, camPos) >= AUTO_DROP_SPACING_M) {
                addSample(s, floatArrayOf(camPos[0], camPos[1] - 0.3f, camPos[2]), rssi, now)
                lastDropPos = camPos
            }
        }

        // Project every sample to screen space.
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val w = viewportW.toFloat()
        val h = viewportH.toFloat()
        val world = FloatArray(4)
        val clip = FloatArray(4)
        val out = ArrayList<ScreenMarker>(samples.size)
        for (sample in samples) {
            if (sample.anchor.trackingState != TrackingState.TRACKING) continue
            val p = sample.anchor.pose
            world[0] = p.tx(); world[1] = p.ty(); world[2] = p.tz(); world[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, world, 0)
            val depth = clip[3]
            if (depth < 0.1f) continue // behind the camera or too close
            val ndcX = clip[0] / depth
            val ndcY = clip[1] / depth
            if (abs(ndcX) > 1.3f || abs(ndcY) > 1.3f) continue
            val radius = (MARKER_RADIUS_M * proj[5] / depth * h / 2f).coerceIn(6f, 160f)
            out.add(ScreenMarker((ndcX + 1f) / 2f * w, (1f - ndcY) / 2f * h, radius, depth, sample.rssi))
        }
        out.sortByDescending { it.depth }
        overlay.setMarkers(out)
    }

    private fun addSample(s: Session, pos: FloatArray, rssi: Int, now: Long) {
        val anchor = try {
            s.createAnchor(Pose.makeTranslation(pos[0], pos[1], pos[2]))
        } catch (e: Exception) { return }
        samples.add(Sample(anchor, rssi, now))
        rows.add(Row(now, pos[0], pos[1], pos[2], rssi, wifi.ssid, wifi.bssid, wifi.frequencyMhz))
        lastDropTime = now
        lastDropStamp = wifi.readingStamp
        if (samples.size > MAX_SAMPLES) {
            samples.removeAt(0).anchor.detach()
        }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ------------------------------------------------------------------ HUD (main thread)

    private fun refreshHud() {
        val rssi = wifi.rssi
        overlay.liveRssi = rssi
        overlay.postInvalidateOnAnimation()

        val following = wifi.targetKey != null
        val name = wifi.ssid ?: if (following) wifi.targetKey else null
        if (rssi == null) {
            signalText.text = if (following) "Can't find this Wi-Fi here" else "Not connected to Wi-Fi"
            signalText.setTextColor(Color.rgb(0xB4, 0xB4, 0xB8))
            meaningText.text = if (following) "Move closer to it, or tap Change to pick another."
            else "Connect to Wi-Fi, or tap Change to check any nearby network."
        } else {
            val color = SignalScale.color(rssi)
            val level = SignalScale.level(rssi)
            signalText.text = SpannableStringBuilder()
                .append(bars(level, color))
                .append("  ")
                .append("${SignalScale.label(rssi)}  ·  $rssi dBm")
            signalText.setTextColor(color)
            signalText.maxLines = 1
            meaningText.text = SignalScale.meaning(rssi)
        }
        networkText.text = when {
            name == null && following -> "Checking: hidden network"
            name == null -> "Checking: your Wi-Fi"
            following -> "Checking: $name\nUpdated ${wifi.readingAgeMs / 1000} seconds ago"
            else -> "Checking: $name (your Wi-Fi)"
        }

        val levels = synchronized(rows) { rows.map { it.rssi } }
        statsText.text = when {
            levels.isEmpty() && autoDrop -> "Walk slowly around. A coloured bubble marks the signal at each spot."
            levels.isEmpty() -> "Paused. Tap Mark spot to add a bubble, or Resume."
            else -> {
                val spots = if (levels.size == 1) "1 spot checked" else "${levels.size} spots checked"
                "$spots · best: ${SignalScale.label(levels.max())} · worst: ${SignalScale.label(levels.min())}" +
                    if (autoDrop) "" else " · paused"
            }
        }

        val msg = trackingMessage ?: if (following && wifi.scanThrottled) {
            "This network only refreshes about every 30 seconds. Stand still at each spot for a moment."
        } else null
        statusText.text = msg ?: ""
        statusText.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun showNetworkPicker() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            Toast.makeText(this, "To see nearby Wi-Fi, Android needs Location permission.", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }
        val connected = wifi.connectedSsid
        // Hidden networks have no name to show, so leave them out; the connected one is listed first.
        val others = wifi.nearby.filter { it.ssid != null && it.ssid != connected }
        val grey = Color.rgb(0x9A, 0x9A, 0xA0)
        fun greyText(text: String) = SpannableStringBuilder(text).apply {
            setSpan(ForegroundColorSpan(grey), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun item(title: String, detail: CharSequence) = SpannableStringBuilder().apply {
            append(title)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            val start = length
            append(detail)
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun detail(rssi: Int) = SpannableStringBuilder()
            .append(bars(SignalScale.level(rssi), SignalScale.color(rssi)))
            .append("  ")
            .append(greyText("${SignalScale.label(rssi)} · $rssi dBm"))

        val first = if (connected != null) item(connected, greyText("Your Wi-Fi · updates live"))
        else item("Your Wi-Fi", greyText("Not connected right now"))
        val items = listOf<CharSequence>(first) + others.map { item(it.ssid!!, detail(it.rssi)) }
        val current = wifi.targetKey?.let { key -> others.indexOfFirst { it.key == key }.let { if (it < 0) -1 else it + 1 } } ?: 0
        val title = when {
            others.isNotEmpty() -> "Which Wi-Fi do you want to check?"
            !wifi.locationEnabled -> "Turn on Location to see other Wi-Fi nearby"
            else -> "Looking for nearby Wi-Fi… try again in a few seconds"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), current) { dialog, which ->
                val pick = if (which == 0) null else others[which - 1]
                if (pick?.key != wifi.targetKey) {
                    wifi.follow(pick)
                    clearRequested = true // bubbles from the old network would be misleading
                    lastDropStamp = -1L
                    refreshHud()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmStartOver() {
        val count = rows.size
        if (count == 0) {
            clearRequested = true
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Start over?")
            .setMessage("This removes the $count ${if (count == 1) "spot" else "spots"} you've checked so far.")
            .setPositiveButton("Start over") { _, _ -> clearRequested = true }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shares a plain-language summary, with the full data attached as a spreadsheet (CSV). */
    private fun shareScan() {
        val snapshot = synchronized(rows) { rows.toList() }
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "Nothing to share yet. Walk around first.", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = buildString {
            append("timestamp_ms,x_m,y_m,z_m,rssi_dbm,ssid,bssid,frequency_mhz\n")
            for (r in snapshot) {
                append(String.format(Locale.US, "%d,%.3f,%.3f,%.3f,%d,%s,%s,%s\n",
                    r.timeMs, r.x, r.y, r.z, r.rssi, (r.ssid ?: "").replace(",", " "),
                    r.bssid ?: "", r.frequencyMhz?.toString() ?: ""))
            }
        }
        val best = snapshot.maxOf { it.rssi }
        val worst = snapshot.minOf { it.rssi }
        val counts = snapshot.groupingBy { SignalScale.level(it.rssi) }.eachCount()
        val network = snapshot.first().ssid ?: "Wi-Fi"
        val summary = buildString {
            append("SignalLens Wi-Fi check: $network\n")
            append("${snapshot.size} spots checked\n")
            append("Best spot: ${SignalScale.label(best)} ($best dBm)\n")
            append("Worst spot: ${SignalScale.label(worst)} ($worst dBm)\n")
            append((4 downTo 0).joinToString(" · ") { "${SignalScale.LABELS[it]}: ${counts[it] ?: 0}" })
            append("\n\nFull data is attached as a spreadsheet (CSV).")
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "SignalLens Wi-Fi check: $network")
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        val uri = try {
            val dir = java.io.File(cacheDir, "scans").apply { mkdirs() }
            val safeName = network.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = java.io.File(dir, "signallens-$safeName.csv").apply { writeText(csv) }
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (e: Exception) { null }
        if (uri != null) {
            send.type = "text/csv"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri("", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.type = "text/plain" // attachment failed: share the summary alone
        }
        startActivity(Intent.createChooser(send, "Share Wi-Fi check"))
    }

    companion object {
        private const val REQ_PERMS = 42
        private const val MAX_SAMPLES = 400
        private const val AUTO_DROP_SPACING_M = 0.4f
        private const val MIN_DROP_INTERVAL_MS = 600L
        private const val MARKER_RADIUS_M = 0.07f
    }
}
