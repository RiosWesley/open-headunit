package com.andrerinas.openheadunit.aap.cluster

import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Finds the instrument-cluster display among the head unit's displays.
 *
 * On BYD DiLink the cluster is a virtual display owned by `com.xdja.containerservice`
 * (`fission_bg_xdjaVirtualSurface`, or `fission_bg_XDJAScreenProjection` on DiLink 5), created once
 * the cluster projection is switched on. Elsewhere any non-default presentation display is taken.
 */
internal object ClusterDisplayLocator {

    private val KNOWN_NAMES = listOf("xdja", "fission", "dashboard", "cluster")

    fun find(dm: DisplayManager): Display? {
        val others = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        return others.firstOrNull { d -> KNOWN_NAMES.any { d.name.contains(it, ignoreCase = true) } }
            ?: dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }
}
