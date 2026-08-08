package com.tv.coverscreen.keyboard

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.tv.coverscreen.Privileged
import rikka.shizuku.Shizuku

/**
 * Puts characters into other apps' text fields.
 *
 * Three tiers, best first, degrading silently -- the same shape the recents
 * source and the snapshot source already use in this project:
 *
 *   1. ACTION_SET_TEXT on the focused editable node, with the caret read back
 *      out of the node and restored afterwards so typing inserts at the cursor
 *      instead of overwriting the field. Costs nothing, needs no Shizuku, and
 *      works in every ordinary View-based app.
 *   2. ACTION_SET_SELECTION as a separate follow-up when a field accepts the
 *      text but refuses the caret in the same pass, which some EditText
 *      subclasses do.
 *   3. `input text` / `input keyevent` as shell through Shizuku, for fields
 *      that report no editable node at all. WebViews and canvas-drawn UIs are
 *      the honest reason this tier exists: accessibility can see them but
 *      cannot set their text.
 *
 * Tier 3 is the only part that wants Shizuku, and it is a fallback, not the
 * mechanism. With Shizuku absent the keyboard still types everywhere tier 1
 * reaches, which is nearly everything.
 */
object TypeBridge {

    private const val TAG = "TypeBridge"

    /** Depth-capped so a pathological hierarchy cannot stall the main thread. */
    private const val MAX_NODES = 600

    // ------------------------------------------------------------ public api

    /**
     * The editable node holding input focus on [displayId], or null.
     *
     * Walks every window the service can see on that display rather than
     * trusting rootInActiveWindow, because on a folded Flip the "active"
     * window is regularly on the inner panel while the field the user is
     * looking at is out on the cover one.
     */
    fun editable(svc: AccessibilityService, displayId: Int): AccessibilityNodeInfo? {
        val byDisplay = runCatching { svc.windowsOnAllDisplays }.getOrNull()
        val windows = byDisplay?.get(displayId)
        if (windows != null) {
            for (win in windows) {
                val root = runCatching { win.root }.getOrNull() ?: continue
                hit(root)?.let { return it }
            }
        }
        return runCatching { svc.rootInActiveWindow }.getOrNull()?.let { hit(it) }
    }

    /**
     * The real contents of [node], with a placeholder never counted as content.
     *
     * This exists because of a genuine defect. AccessibilityNodeInfo.getText()
     * returns the field's *hint* when the field is empty - TextView puts it
     * there deliberately, so a screen reader can announce what the box is for -
     * and nothing about the returned string says it is a placeholder. Reading
     * it as content is catastrophic here rather than merely wrong, because
     * ACTION_SET_TEXT replaces the whole field: typing one letter into an empty
     * Google Messages box read back "Text message", appended to it, and wrote
     * "Text messagea" into a box the user had left blank. Backspacing then ate
     * the placeholder one character at a time, and the moment the field was
     * truly empty the placeholder came back, so it could never be cleared.
     *
     * isShowingHintText is the framework's own answer and is checked first. The
     * equality fallback is for Compose and other hosts that populate hintText
     * but never set the flag; it is gated on the caret being at or before the
     * start, because a user who has genuinely typed the placeholder text
     * verbatim will not also have the cursor sitting at position zero.
     */
    fun body(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString() ?: ""
        if (raw.isEmpty()) return ""
        if (runCatching { node.isShowingHintText }.getOrDefault(false)) return ""
        val hint = runCatching { node.hintText?.toString() }.getOrNull()
        if (!hint.isNullOrEmpty() && raw == hint && node.textSelectionEnd <= 0) return ""
        return raw
    }

    /** The field's own placeholder, for displaying as a prompt. Never typed. */
    fun hint(node: AccessibilityNodeInfo): String? =
        runCatching { node.hintText?.toString() }.getOrNull()
            ?: runCatching { if (node.isShowingHintText) node.text?.toString() else null }
                .getOrNull()

    /** True when there is somewhere on [displayId] for a keystroke to land. */
    fun hasField(svc: AccessibilityService, displayId: Int): Boolean =
        editable(svc, displayId) != null

    /** Insert [text] at the caret. Falls through to shell when there is no node. */
    fun commit(svc: AccessibilityService, displayId: Int, text: String): Boolean {
        if (text.isEmpty()) return true
        val node = editable(svc, displayId) ?: return shellText(text)

        // body(), never node.text: an empty field reports its hint here, and
        // ACTION_SET_TEXT replaces the whole field, so reading a placeholder as
        // content types the placeholder into the field.
        val current = body(node)
        val caret = caretOf(node, current)
        val next = StringBuilder(current).insert(caret, text).toString()

        if (!setText(node, next)) return shellText(text)
        setCaret(node, caret + text.length, next.length)
        return true
    }

    /** Delete one character before the caret. */
    fun backspace(svc: AccessibilityService, displayId: Int): Boolean {
        val node = editable(svc, displayId) ?: return shellKey(67)
        // body() again: without it, backspace deletes the placeholder one
        // character at a time and writes the remainder into an empty field.
        val current = body(node)
        if (current.isEmpty()) return true

        val caret = caretOf(node, current)
        if (caret <= 0) return true
        val next = StringBuilder(current).deleteCharAt(caret - 1).toString()

        if (!setText(node, next)) return shellKey(67)
        setCaret(node, caret - 1, next.length)
        return true
    }

    /**
     * The go/search/send key. ACTION_IME_ENTER is what the field itself
     * advertises, so it fires the app's own editor action rather than
     * inserting a newline into a single-line field.
     */
    fun enter(svc: AccessibilityService, displayId: Int): Boolean {
        val node = editable(svc, displayId) ?: return shellKey(66)
        val ok = runCatching {
            node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        }.getOrDefault(false)
        return if (ok) true else shellKey(66)
    }

    /** Where the caret sits inside [node]'s real contents. */
    fun caret(node: AccessibilityNodeInfo): Int = caretOf(node, body(node))

    /**
     * Move the caret [delta] characters, clamped to the field.
     *
     * Splicing at the caret was never the missing piece: commit() has always
     * read textSelectionEnd and inserted there, so typing has never appended
     * blindly. What was missing was any way to move that caret, because the
     * only thing that moved it was a tap on the field itself, and the field is
     * regularly behind this window or off the top of a cover-panel page that
     * cannot be scrolled.
     *
     * ACTION_SET_SELECTION is a separate action from ACTION_SET_TEXT, so a host
     * can accept text and still refuse a selection. When it does, DPAD_LEFT and
     * DPAD_RIGHT through the shell tier move the cursor instead.
     */
    fun nudge(svc: AccessibilityService, displayId: Int, delta: Int): Boolean {
        val key = if (delta < 0) 21 else 22
        val node = editable(svc, displayId) ?: return shellKey(key)
        val current = body(node)
        if (current.isEmpty()) return true
        val at = (caretOf(node, current) + delta).coerceIn(0, current.length)
        return if (setCaret(node, at, current.length)) true else shellKey(key)
    }

    /** Caret to the very start or the very end. */
    fun jump(svc: AccessibilityService, displayId: Int, toEnd: Boolean): Boolean {
        val key = if (toEnd) 123 else 122
        val node = editable(svc, displayId) ?: return shellKey(key)
        val current = body(node)
        if (current.isEmpty()) return true
        val at = if (toEnd) current.length else 0
        return if (setCaret(node, at, current.length)) true else shellKey(key)
    }

    /**
     * Caret to an absolute offset, for tapping straight into the preview strip.
     * There is no shell equivalent, because `input keyevent` can only step; a
     * host that refuses ACTION_SET_SELECTION reports false here and the arrows
     * stay the way in.
     */
    fun moveTo(svc: AccessibilityService, displayId: Int, at: Int): Boolean {
        val node = editable(svc, displayId) ?: return false
        val current = body(node)
        if (current.isEmpty()) return true
        return setCaret(node, at.coerceIn(0, current.length), current.length)
    }

    // ------------------------------------------------------------ internals

    private fun hit(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?.let { if (it.isEditable) return it }

        // Some hosts do not answer findFocus on a secondary display, so fall
        // back to a breadth-first sweep for the focused editable node.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        var loose: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst()
            seen++
            if (n.isEditable) {
                if (n.isFocused) return n
                if (loose == null) loose = n
            }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return loose
    }

    private fun caretOf(node: AccessibilityNodeInfo, current: String): Int {
        val end = node.textSelectionEnd
        return if (end in 0..current.length) end else current.length
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
            )
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.onFailure { Log.w(TAG, "setText refused", it) }.getOrDefault(false)
    }

    /**
     * Answers whether the host actually took the selection, so callers can fall
     * back. Plenty of EditText subclasses accept ACTION_SET_TEXT and quietly
     * drop ACTION_SET_SELECTION.
     */
    private fun setCaret(node: AccessibilityNodeInfo, at: Int, max: Int): Boolean {
        val pos = at.coerceIn(0, max)
        return runCatching {
            node.refresh()
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }.onFailure { Log.w(TAG, "setCaret refused", it) }.getOrDefault(false)
    }

    // -------------------------------------------------------- tier 3, shell

    /**
     * Same reflective newProcess call SettingsBackup uses. Answers false the
     * moment Shizuku is not ready, which is the whole contract in Privileged:
     * nothing here is load bearing.
     */
    private fun shell(cmd: String): Boolean {
        if (!Privileged.ready()) {
            Log.w(TAG, "no editable node and no shizuku, keystroke dropped")
            return false
        }
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            proc.waitFor() == 0
        }.onFailure { Log.w(TAG, "shell(" + cmd + ")", it) }.getOrDefault(false)
    }

    private fun shellKey(code: Int): Boolean = shell("input keyevent " + code)

    /**
     * `input text` splits on whitespace and reads % as an escape, so a space
     * has to go in as %s and a literal % as %%. Anything outside a known-safe
     * set is sent as a keyevent-free single quoted argument.
     */
    private fun shellText(text: String): Boolean {
        val escaped = text
            .replace("%", "%%")
            .replace(" ", "%s")
            .replace("'", "")
        if (escaped.isEmpty()) return false
        return shell("input text '" + escaped + "'")
    }
}
