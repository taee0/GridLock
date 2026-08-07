package com.tv.coverscreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * The activity the service throws onto the cover display.
 *
 * showOverlay() builds an ActivityOptions with launchDisplayId set to the
 * cover display and starts this, rather than adding a window. That is the
 * path the service falls back to when the window manager refuses the
 * overlay, which happens on builds that will not take an accessibility
 * overlay on a secondary display.
 *
 * Everything about the manifest entry matters: singleInstance and an empty
 * task affinity keep it out of the task the user came from, noHistory and
 * excludeFromRecents keep it from ever appearing in the switcher it is drawing,
 * and showWhenLocked lets it come up with the phone shut.
 */
// Plain Activity, not AppCompatActivity: the theme it runs under is
// android:Theme.Translucent.NoTitleBar.Fullscreen, and AppCompat throws if it
// is handed a theme that is not one of its own.
class ConfigActivity : Activity() {

    private lateinit var cards: RecyclerView
    private lateinit var adapter: CardAdapter
    private lateinit var empty: TextView
    private lateinit var shizuku: TextView

    /** Repaint the pill the moment the binder or the permission changes. */
    private val watch: () -> Unit = { runOnUiThread { paint() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            windowAnimations = 0
        }
        setContentView(R.layout.config)

        empty = findViewById(R.id.none)
        cards = findViewById(R.id.cards)
        shizuku = findViewById(R.id.shizuku)
        shizuku.setOnClickListener { shizukuTapped() }
        // Named, not a trailing lambda: CardAdapter takes an onIconTap after
        // onTap now, and a trailing lambda would quietly bind to that one.
        adapter = CardAdapter(
            onTap = { card ->
                RecentsEngine.live?.open(card)
                finish()
            },
        )

        // Same geometry as the deck: one card centred, neighbours peeking, and
        // the cutout kept clear on whichever edge it landed on this rotation.
        val safe = RecentsEngine.live?.safeInsets() ?: android.graphics.Rect()
        val width = resources.getDimensionPixelSize(R.dimen.card_w)
        val usable = resources.displayMetrics.widthPixels - safe.left - safe.right
        val side = ((usable - width) / 2).coerceAtLeast(0)
        cards.setPadding(safe.left + side, safe.top, safe.right + side, safe.bottom)
        cards.clipToPadding = false
        cards.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        cards.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(cards)

        findViewById<View>(R.id.clear).setOnClickListener {
            RecentsEngine.live?.forgetEverything()
            finish()
        }

        findViewById<View>(R.id.root).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        Privileged.watch(watch)
        paint()
        val items = RecentsEngine.live?.cards().orEmpty()
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        cards.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        Privileged.unwatch(watch)
        // noHistory would do this anyway, but leaving it up on a panel that has
        // gone dark is the thing that made the old build feel stuck.
        if (!isFinishing) finish()
    }

    /**
     * One line, one job. Shizuku is optional here: the deck works without it, so
     * this only speaks up when there is a button worth pressing.
     */
    private fun paint() {
        val installed = runCatching {
            packageManager.getLaunchIntentForPackage(SHIZUKU_APP) != null
        }.getOrDefault(false)
        val label = when (Privileged.access()) {
            // Connected. Nothing to say, the cards are already the real ones.
            Privileged.Access.READY -> null
            Privileged.Access.DENIED -> getString(R.string.shizuku_grant)
            Privileged.Access.LEGACY -> getString(R.string.shizuku_old)
            // Never nag anyone who has not got it.
            Privileged.Access.OFFLINE ->
                if (installed) getString(R.string.shizuku_start) else null
        }
        shizuku.text = label.orEmpty()
        shizuku.visibility = if (label == null) View.GONE else View.VISIBLE
    }

    private fun shizukuTapped() {
        when (Privileged.access()) {
            Privileged.Access.DENIED -> Privileged.request()
            Privileged.Access.READY -> Unit
            else -> {
                // Not running. The pairing and the start button live in the
                // Shizuku app itself, so hand them straight over to it.
                val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_APP)
                    ?: return
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
            }
        }
    }

    companion object {
        private const val SHIZUKU_APP = "moe.shizuku.privileged.api"
    }
}
