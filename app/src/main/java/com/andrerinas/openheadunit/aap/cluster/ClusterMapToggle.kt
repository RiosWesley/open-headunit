package com.andrerinas.openheadunit.aap.cluster

import android.content.Context
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The user's "map on the cluster" switch for an Android Auto session. On, the cluster shows the
 * projection with the map and the ADAS pane hidden; off, the native cluster comes back. The choice
 * is remembered, so the next session starts the way the last one was left.
 */
internal object ClusterMapToggle {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Panel commands take seconds; a quick double tap must not interleave on and off. */
    private val panelLock = Mutex()

    @Volatile private var sessionActive = false

    fun isOn(context: Context) = Settings(context).clusterMapEnabled

    fun onSessionStart(context: Context) {
        sessionActive = true
        apply(context.applicationContext, isOn(context))
        ClusterToggleButton.show(context)
    }

    fun onSessionEnd(context: Context) {
        if (!sessionActive) return
        sessionActive = false
        ClusterToggleButton.hide()
        // Leave the car's own cluster behind, whichever way the switch was.
        if (isOn(context)) scope.launch { panelLock.withLock { ClusterPanelControl.hide(context.applicationContext) } }
    }

    /** Flips the switch; returns the new state. */
    fun toggle(context: Context): Boolean {
        val on = !isOn(context)
        Settings(context).clusterMapEnabled = on
        apply(context.applicationContext, on)
        return on
    }

    private fun apply(context: Context, on: Boolean) {
        ClusterDisplayOutput.setVisible(on)
        scope.launch {
            panelLock.withLock {
                if (on) ClusterPanelControl.show(context) else ClusterPanelControl.hide(context)
            }
        }
    }
}
