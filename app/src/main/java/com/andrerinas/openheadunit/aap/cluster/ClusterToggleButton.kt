package com.andrerinas.openheadunit.aap.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.abs

/**
 * A small floating button on the main screen that flips [ClusterMapToggle] during an Android Auto
 * session. Drag to move it; a tap without movement toggles. Green while the map is on the cluster.
 */
internal object ClusterToggleButton {

    private const val TAG = "ClusterToggleButton"
    private const val SIZE_DP = 56
    private const val COLOR_ON = 0xFF2E7D32.toInt()
    private const val COLOR_OFF = 0xFF616161.toInt()
    /** Movement below this many pixels is a tap, not a drag. */
    private const val TAP_SLOP = 12

    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var windowManager: WindowManager? = null

    /** Where the button was left, kept for the next session of this process. */
    private var lastX = 24
    private var lastY = 420

    fun show(context: Context) = main.post {
        if (view != null) return@post
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(WindowManager::class.java)
        val sizePx = (SIZE_DP * ctx.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }
        val button = TextView(ctx).apply {
            text = "MAPA"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            elevation = 8f
            contentDescription = "Mapa no painel"
        }
        paint(button, ClusterMapToggle.isOn(ctx))
        button.setOnTouchListener(DragOrTap(params, wm) {
            paint(button, ClusterMapToggle.toggle(ctx))
        })
        try {
            wm.addView(button, params)
            view = button
            windowManager = wm
        } catch (e: Exception) {
            AppLog.e("$TAG: cannot add the button (overlay permission?): ${e.message}")
        }
    }

    fun hide() = main.post {
        view?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {}
        }
        view = null
        windowManager = null
    }

    private fun paint(button: TextView, on: Boolean) {
        (button.background as GradientDrawable).setColor(if (on) COLOR_ON else COLOR_OFF)
    }

    private class DragOrTap(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager,
        private val onTap: () -> Unit,
    ) : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) {
                        dragging = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        lastX = params.x
                        lastY = params.y
                    } else {
                        onTap()
                    }
                }
            }
            return true
        }
    }
}
