package com.andrerinas.openheadunit.aap.cluster

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.widget.FrameLayout
import com.andrerinas.openheadunit.aap.ClusterStreamDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Shows the dedicated cluster video stream (AAP channel `ID_VID_CLUSTER`) on the instrument-cluster
 * display, inside [ClusterMapArea] on a black background.
 *
 * The window is an application overlay rather than an activity, so it needs no task on the cluster
 * display and nothing on the main screen changes. It needs the SYSTEM_ALERT_WINDOW grant. Overlays
 * created later (another app's gauges, for instance) stack above it.
 *
 * The cluster display may not exist yet when projection starts - on BYD it appears only once the
 * cluster projection is switched on - so [start] waits for it and shows the map when it arrives.
 */
internal object ClusterDisplayOutput {

    private const val TAG = "ClusterDisplayOutput"

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var displayManager: DisplayManager? = null
    private var presentation: Presentation? = null
    private var onSurfaceReady: (() -> Unit)? = null

    private val decoder = ClusterStreamDecoder(TAG) {
        appContext?.let { Settings(it).clusterVideoResolution } ?: "800x480"
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = showIfPossible()
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) dismiss()
        }
    }

    /**
     * Starts showing the cluster map, now or as soon as the cluster display appears.
     * [onSurfaceReady] runs each time a new surface is up; it should ask the phone for a keyframe.
     */
    fun start(context: Context, onSurfaceReady: () -> Unit) = main.post {
        if (appContext != null) return@post
        appContext = context.applicationContext
        this.onSurfaceReady = onSurfaceReady
        val dm = context.getSystemService(DisplayManager::class.java)
        displayManager = dm
        dm.registerDisplayListener(displayListener, main)
        showIfPossible()
    }

    fun stop() = main.post {
        displayManager?.unregisterDisplayListener(displayListener)
        dismiss()
        displayManager = null
        appContext = null
        onSurfaceReady = null
    }

    /**
     * Shows or hides the map without ending the session. Hidden, the decoder has no surface and
     * drops the stream; shown again, a fresh surface asks the phone for a keyframe.
     */
    fun setVisible(visible: Boolean) = main.post {
        this.visible = visible
        if (visible) showIfPossible() else dismiss()
    }

    @Volatile private var visible = true

    /** Called on the reader thread for each reassembled access unit. */
    fun onUnit(data: ByteArray, offset: Int, length: Int) = decoder.onUnit(data, offset, length)

    private fun showIfPossible() {
        if (presentation != null || !visible) return
        val ctx = appContext ?: return
        val display = displayManager?.let { ClusterDisplayLocator.find(it) } ?: run {
            AppLog.i("$TAG: no cluster display yet, waiting for one")
            return
        }
        try {
            presentation = buildPresentation(ctx, display).also { it.show() }
        } catch (e: Exception) {
            // Most likely the overlay grant is missing: BadTokenException / permission denied.
            AppLog.e("$TAG: cannot show on display ${display.displayId} (${display.name}): ${e.message}")
            presentation = null
        }
    }

    private fun buildPresentation(ctx: Context, display: Display): Presentation {
        val size = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
        val area = ClusterMapArea.resolve(Settings(ctx).clusterDisplayRect, size.x, size.y)
        AppLog.i("$TAG: showing on display ${display.displayId} (${display.name}, ${size.x}x${size.y}), map at $area")

        // The same geometry ServiceDiscovery announced, so the crop matches the phone's margins.
        val geometry = ClusterVideoGeometry.forArea(area.width(), area.height())
        val mapView = ClusterMapView(ctx, geometry, ::onMapSurface).apply {
            layoutParams = FrameLayout.LayoutParams(area.width(), area.height()).apply {
                leftMargin = area.left
                topMargin = area.top
            }
        }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(mapView)
        }
        return Presentation(ctx, display).apply {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setCancelable(false)
            setContentView(root)
        }
    }

    private fun onMapSurface(surface: Surface?) {
        decoder.setSurface(surface)
        if (surface != null) onSurfaceReady?.invoke()
    }

    private fun dismiss() {
        try {
            presentation?.dismiss()
        } catch (_: Exception) {}
        presentation = null
        decoder.release()
    }
}
