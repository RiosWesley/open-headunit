package com.andrerinas.openheadunit.aap.cluster

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import com.andrerinas.openheadunit.utils.Settings

/**
 * Where the cluster map is drawn on the cluster display, in display pixels.
 *
 * The setting is "x,y,width,height". Blank or invalid means a centred box at 5:3 (the 800x480 stream)
 * covering 80% of the display height, which leaves the sides free for the native gauges or another
 * app's overlay. A box running off the display is clamped to it.
 */
internal object ClusterMapArea {

    /** Assumed when the cluster display is not up yet (BYD DiLink 3, 8.8" cluster). */
    private const val FALLBACK_WIDTH = 1280
    private const val FALLBACK_HEIGHT = 480

    /** The map area on the current cluster display, or on the fallback size if there is none yet. */
    fun current(context: Context): Rect {
        val dm = context.getSystemService(DisplayManager::class.java)
        val size = dm?.let { ClusterDisplayLocator.find(it) }?.let { d ->
            Point().also {
                @Suppress("DEPRECATION")
                d.getRealSize(it)
            }
        } ?: Point(FALLBACK_WIDTH, FALLBACK_HEIGHT)
        return resolve(Settings(context).clusterDisplayRect, size.x, size.y)
    }

    fun resolve(setting: String, displayWidth: Int, displayHeight: Int): Rect {
        val parsed = parse(setting)
        return if (parsed != null) clamp(parsed, displayWidth, displayHeight)
        else centred(displayWidth, displayHeight)
    }

    private fun parse(setting: String): Rect? {
        val n = setting.split(",").map { it.trim().toIntOrNull() ?: return null }
        if (n.size != 4 || n[2] <= 0 || n[3] <= 0 || n[0] < 0 || n[1] < 0) return null
        return Rect(n[0], n[1], n[0] + n[2], n[1] + n[3])
    }

    private fun clamp(r: Rect, w: Int, h: Int) =
        Rect(r.left.coerceAtMost(w), r.top.coerceAtMost(h), r.right.coerceAtMost(w), r.bottom.coerceAtMost(h))

    private fun centred(w: Int, h: Int): Rect {
        val boxH = h * 8 / 10
        val boxW = (boxH * 5 / 3).coerceAtMost(w)
        val left = (w - boxW) / 2
        val top = (h - boxH) / 2
        return Rect(left, top, left + boxW, top + boxH)
    }
}
