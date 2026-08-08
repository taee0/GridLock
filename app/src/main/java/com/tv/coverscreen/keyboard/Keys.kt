package com.tv.coverscreen.keyboard

import com.tv.coverscreen.R

/**
 * The one key table.
 *
 * Both keyboards in this app read this: the widget keypad in
 * AppLauncherWidgetReceiver, which binds a PendingIntent per key, and
 * [KeyboardOverlay], which binds an OnClickListener per key. The pairing of
 * character to view id lives here so those two can never disagree about what
 * a key is, the same way search_keypad.xml keeps them from disagreeing about
 * where a key is.
 */
object Keys {

    /** letter -> view id, in physical row order. */
    val LETTERS: List<Pair<Char, Int>> = listOf(
        'q' to R.id.key_q, 'w' to R.id.key_w, 'e' to R.id.key_e, 'r' to R.id.key_r,
        't' to R.id.key_t, 'y' to R.id.key_y, 'u' to R.id.key_u, 'i' to R.id.key_i,
        'o' to R.id.key_o, 'p' to R.id.key_p,
        'a' to R.id.key_a, 's' to R.id.key_s, 'd' to R.id.key_d, 'f' to R.id.key_f,
        'g' to R.id.key_g, 'h' to R.id.key_h, 'j' to R.id.key_j, 'k' to R.id.key_k,
        'l' to R.id.key_l,
        'z' to R.id.key_z, 'x' to R.id.key_x, 'c' to R.id.key_c, 'v' to R.id.key_v,
        'b' to R.id.key_b, 'n' to R.id.key_n, 'm' to R.id.key_m
    )

    /**
     * Second character on each key, reached by long press or by the 123 layer.
     *
     * The widget keypad ignores this entirely -- it only ever searches app
     * names, where digits and punctuation are noise. It exists for the overlay,
     * which is typing into other people's text fields and does need them.
     * Deliberately no quote characters: they survive a Kotlin char literal
     * fine but they are the first thing to break the shell fallback in
     * [TypeBridge], and a keyboard that mangles one key is worse than one that
     * omits it.
     */
    val ALT: Map<Char, Char> = mapOf(
        'q' to '1', 'w' to '2', 'e' to '3', 'r' to '4', 't' to '5',
        'y' to '6', 'u' to '7', 'i' to '8', 'o' to '9', 'p' to '0',
        'a' to '@', 's' to '#', 'd' to '%', 'f' to '_', 'g' to '&',
        'h' to '-', 'j' to '+', 'k' to '(', 'l' to ')',
        'z' to '*', 'x' to '/', 'c' to ',', 'v' to ':', 'b' to ';',
        'n' to '!', 'm' to '?'
    )
}
