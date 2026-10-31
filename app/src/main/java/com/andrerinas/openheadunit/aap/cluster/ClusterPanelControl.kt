package com.andrerinas.openheadunit.aap.cluster

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.adb.AdbManager

/**
 * Switches the BYD DiLink cluster between its native scene and the Android projection display,
 * and hides the native ADAS pane that would otherwise cover the map.
 *
 * Both need shell privileges (the AutoContainer binder and the clusterdebug CAN receiver accept
 * uid 2000), so they go through Self-ADB. The sequences are the ones DashCast and Electro use on
 * DiLink 3: `16 → 35` opens the projection without changing the panel geometry (no `30`), `18 → 0`
 * closes it. The ADAS hide is Electro's recovered masking pass; a single hide frame is not enough
 * because the cluster re-shows the pane.
 */
internal object ClusterPanelControl {

    private const val TAG = "ClusterPanelControl"

    private fun container(code: Int) = "service call AutoContainer 2 i32 1000 i32 $code s16 '' >/dev/null"

    private fun frames(count: Int, frame: String) =
        "i=0; while [ \$i -lt $count ]; do am broadcast -a com.byd.cluster.spi -p com.byd.clusterdebug " +
            "-f 0x20 --es wholeFrame $frame >/dev/null 2>&1; i=\$((i+1)); done"

    private const val ADAS_HIDE = "23,50,00,08,01,00"
    private const val ADAS_RESTORE = "23,50,00,08,01,01"

    private val adasMaskingPass = listOf(
        container(13),
        "cmd deviceidle whitelist +com.byd.clusterdebug >/dev/null 2>&1",
        "am startservice -n com.byd.clusterdebug/.ClusterDebugService >/dev/null 2>&1",
        "sleep 1",
        frames(3, ADAS_HIDE),
        frames(60, "26,F0,00,28,01,06"),
        frames(50, "43,20,00,00,01,01"),
        frames(50, "43,20,00,21,01,01"),
        frames(40, "26,F0,00,28,01,06"),
        frames(60, ADAS_HIDE),
    ).joinToString("; ")

    /** Opens the projection on the cluster and starts hiding the ADAS pane. */
    suspend fun show(context: Context): Boolean {
        val open = listOf(container(16), "sleep 3", container(35)).joinToString("; ")
        // The masking pass takes longer than Self-ADB's read window, so it runs in a background
        // subshell with no terminal attached; a subshell needs no second level of quoting.
        val mask = "( $adasMaskingPass ) </dev/null >/dev/null 2>&1 &"
        return run(context, "show", "$open; $mask")
    }

    /** Restores the ADAS pane and returns the cluster to its native scene. */
    suspend fun hide(context: Context): Boolean {
        val script = listOf(
            frames(3, ADAS_RESTORE),
            container(12),
            container(18),
            "sleep 2",
            container(0),
        ).joinToString("; ")
        return run(context, "hide", script)
    }

    private suspend fun run(context: Context, name: String, script: String): Boolean {
        val (code, out) = AdbManager.exec(context, script)
        if (code != 0) AppLog.w("$TAG: $name failed: $out") else AppLog.i("$TAG: $name sent")
        return code == 0
    }
}
