package com.tv.coverscreen

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class Card(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val shot: Bitmap?,
    /**
     * Kept open. One UI lets you tap the icon above a card and pin it, and a
     * pinned card is the one thing Close all leaves behind.
     */
    val pinned: Boolean = false,
    /**
     * The task this card stands for, when Shizuku is handing over the real
     * recents list. -1 means we only know the package, so close and resume fall
     * back to the package level path.
     */
    val taskId: Int = -1,
)
