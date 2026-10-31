package com.andrerinas.openheadunit.aap

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Debug aid: renders the dedicated cluster video stream (AAP channel `ID_VID_CLUSTER`) in a small
 * floating window *on top of* the normal Android Auto projection, so both pictures are visible at
 * the same time.
 *
 * The main projection and the cluster are two independent H.264/HEVC streams, and there is a single
 * shared [com.andrerinas.openheadunit.decoder.video.VideoDecoder] for the main one. This overlay owns
 * its *own* [ClusterStreamDecoder] and its *own* [SurfaceView], so it decodes the cluster without
 * touching the main decoder at all - which is the whole point of the feature (the "swap the streams"
 * approach broke the connection; showing the cluster beside the main one does not).
 *
 * Fed one reassembled Annex-B access unit at a time from
 * [com.andrerinas.openheadunit.aap.ClusterVideoStreamer.frameListener], on the reader thread - the
 * same thread the main stream is decoded on, so no extra worker is needed for a debug view.
 */
internal object ClusterOverlay {

    /** Set on [attach]; owned by the projection activity for the life of the overlay. */
    @Volatile private var appContext: Context? = null

    private var surfaceView: SurfaceView? = null
    private var container: FrameLayout? = null

    private val decoder = ClusterStreamDecoder("ClusterOverlay") {
        appContext?.let { Settings(it).clusterVideoResolution } ?: "1280x720"
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            decoder.setSurface(holder.surface)
            AppLog.i("ClusterOverlay: surface created")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // Nothing to do: decoding to a MediaCodec-owned surface is self-sizing off the SPS.
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            decoder.release()
        }
    }

    /** Adds the overlay to [container], top-left, at ~40% of the screen width. Idempotent. */
    fun attach(ctx: Context, container: FrameLayout) {
        if (surfaceView != null) return
        appContext = ctx.applicationContext
        val metrics = ctx.resources.displayMetrics
        val w = (metrics.widthPixels * 0.4).toInt()
        val h = (w * 9 / 16).coerceAtMost((metrics.heightPixels * 0.4).toInt())
        val view = SurfaceView(ctx).apply {
            setZOrderMediaOverlay(true)
            setZOrderOnTop(false)
            val params = FrameLayout.LayoutParams(w, h, android.view.Gravity.TOP or android.view.Gravity.START).apply {
                setMargins(48, 48, 0, 0)
            }
            layoutParams = params
        }
        view.holder.addCallback(surfaceCallback)
        container.addView(view)
        this.container = container
        this.surfaceView = view
        AppLog.i("ClusterOverlay: attached (${w}x$h)")
    }

    fun detach() {
        surfaceView?.let {
            try {
                it.holder.removeCallback(surfaceCallback)
            } catch (_: Exception) {}
            container?.removeView(it)
        }
        surfaceView = null
        container = null
        appContext = null
        decoder.release()
    }

    /**
     * Feeds one reassembled cluster Annex-B access unit into the overlay's own decoder. Called from
     * the reader thread via [com.andrerinas.openheadunit.aap.ClusterVideoStreamer.frameListener].
     */
    fun onUnit(data: ByteArray, offset: Int, length: Int) = decoder.onUnit(data, offset, length)
}
