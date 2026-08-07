package com.tv.coverscreen

import android.hardware.display.DisplayManager

/**
 * Panels coming and going means re-binding the overlay. Panels changing state
 * means the phone was opened or shut, which is what auto rotate cares about.
 */
class DisplayWatch(
    private val onPanels: () -> Unit,
    private val onState: (Int) -> Unit = {},
) : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = onPanels()
    override fun onDisplayRemoved(displayId: Int) = onPanels()
    override fun onDisplayChanged(displayId: Int) = onState(displayId)
}
