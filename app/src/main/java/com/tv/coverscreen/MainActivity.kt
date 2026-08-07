package com.tv.coverscreen

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import apps.ijp.coverscreen.launcher.ui.LauncherHomeActivity
import apps.ijp.coverscreen.launcher.ui.LauncherSettingsActivity

/**
 * Setup screen. Lives on the main display only. Turns the service on, grants the
 * overlay permission auto rotate needs, toggles rotate, shows what the system
 * reports about the panels, and can pop the switcher for a test.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var state: TextView
    private lateinit var displays: TextView
    private lateinit var enable: Button
    private lateinit var test: Button
    private lateinit var grant: Button
    private lateinit var spin: Button
    private lateinit var strict: Button
    private lateinit var openLauncher: Button
    private lateinit var launcherSettings: Button
    private lateinit var shizuku: Button
    private lateinit var shizukuState: TextView

    // Update UI
    private lateinit var versionLabel: TextView
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateChangelog: TextView
    private lateinit var updateInstall: Button
    private lateinit var updateProgress: TextView
    private var pendingDownloadUrl: String? = null

    /** Repaint the moment the binder lands or the permission is answered. */
    private val shizukuWatch: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        state = findViewById(R.id.state)
        displays = findViewById(R.id.displays)
        enable = findViewById(R.id.enable)
        test = findViewById(R.id.test)
        grant = findViewById(R.id.grant)
        spin = findViewById(R.id.spin)
        strict = findViewById(R.id.strict)
        openLauncher = findViewById(R.id.open_launcher)
        launcherSettings = findViewById(R.id.launcher_settings)
        shizuku = findViewById(R.id.shizuku)
        shizukuState = findViewById(R.id.shizuku_state)

        versionLabel = findViewById(R.id.version_label)
        updateBanner = findViewById(R.id.update_banner)
        updateTitle = findViewById(R.id.update_title)
        updateChangelog = findViewById(R.id.update_changelog)
        updateInstall = findViewById(R.id.update_install)
        updateProgress = findViewById(R.id.update_progress)

        versionLabel.text = "v${BuildConfig.VERSION_NAME}"

        updateInstall.setOnClickListener {
            val url = pendingDownloadUrl ?: return@setOnClickListener
            updateInstall.visibility = View.GONE
            updateProgress.visibility = View.VISIBLE
            UpdateInstaller.downloadAndInstall(
                context = this,
                url = url,
                onProgress = { downloading ->
                    runOnUiThread {
                        updateProgress.visibility = if (downloading) View.VISIBLE else View.GONE
                    }
                },
                onComplete = { success ->
                    runOnUiThread {
                        updateProgress.visibility = View.GONE
                        if (!success) {
                            updateInstall.visibility = View.VISIBLE
                            updateTitle.text = "Install failed — tap to retry"
                        }
                    }
                },
            )
        }

        checkForUpdates()

        enable.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        test.setOnClickListener {
            sendBroadcast(Intent(RecentsEngine.ACTION_SHOW).setPackage(packageName))
        }

        grant.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }

        spin.setOnClickListener {
            val r = RecentsEngine.live?.rotate ?: return@setOnClickListener
            r.on = !r.on
            refresh()
        }

        strict.setOnClickListener {
            val r = RecentsEngine.live?.rotate ?: return@setOnClickListener
            r.strict = !r.strict
            refresh()
        }

        shizuku.setOnClickListener {
            when (Privileged.access()) {
                // The permission dialog belongs to the Shizuku app, not to us.
                Privileged.Access.DENIED -> Privileged.request()
                Privileged.Access.READY -> refresh()
                // Not running, too old, or not installed. Pairing and the start
                // button live in the Shizuku app, so hand them over to it, or to
                // its download page when they have not got it yet.
                else -> {
                    val app = packageManager.getLaunchIntentForPackage(SHIZUKU_APP)
                    val intent = app ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SITE))
                    runCatching { startActivity(intent) }
                }
            }
        }

        // the cover launcher grid, reachable from the real app and not only
        // from the widget
        openLauncher.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(this, LauncherHomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        launcherSettings.setOnClickListener {
            runCatching {
                startActivity(Intent(this, LauncherSettingsActivity::class.java))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Privileged.watch(shizukuWatch)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        Privileged.unwatch(shizukuWatch)
    }

    private fun refresh() {
        val running = RecentsEngine.live != null
        val panel = Cover.panel(this)
        val rotate = RecentsEngine.live?.rotate
        val drawable = Settings.canDrawOverlays(this)

        state.text = when {
            !running -> getString(R.string.state_off)
            panel == null -> getString(R.string.state_no_panel)
            else -> getString(R.string.state_on, Cover.size(this, panel))
        }

        displays.text = Cover.dump(this)
        enable.setText(if (running) R.string.enable_again else R.string.enable)
        test.isEnabled = running && panel != null

        grant.setText(if (drawable) R.string.overlay_ok else R.string.overlay)
        spin.setText(if (rotate?.on == true) R.string.rotate_off else R.string.rotate_on)
        spin.isEnabled = running && drawable
        strict.setText(if (rotate?.strict == true) R.string.strict_off else R.string.strict)
        strict.isEnabled = running && drawable && rotate?.on == true

        val access = Privileged.access()
        val installed = runCatching {
            packageManager.getLaunchIntentForPackage(SHIZUKU_APP) != null
        }.getOrDefault(false)
        shizuku.setText(
            when {
                access == Privileged.Access.READY -> R.string.shizuku_ok
                access == Privileged.Access.DENIED -> R.string.shizuku_grant
                access == Privileged.Access.LEGACY -> R.string.shizuku_old
                installed -> R.string.shizuku_start
                else -> R.string.shizuku_get
            }
        )
        // Connected is the one state with nothing left to press.
        shizuku.isEnabled = access != Privileged.Access.READY
        shizukuState.text = getString(R.string.shizuku_state, Privileged.describe())
    }

    private fun checkForUpdates() {
        UpdateChecker.check { release, isNewer ->
            runOnUiThread {
                if (release == null) return@runOnUiThread
                updateBanner.visibility = View.VISIBLE
                if (isNewer) {
                    pendingDownloadUrl = release.downloadUrl
                    updateTitle.text = "New update available — v${release.version}"
                    updateChangelog.text = release.changelog.ifBlank { "No changelog provided." }
                    updateInstall.visibility = View.VISIBLE
                } else {
                    updateTitle.text = "You\u2019re on the latest version (v${release.version})"
                    updateChangelog.text = ""
                    updateInstall.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val SHIZUKU_APP = "moe.shizuku.privileged.api"
        private const val SHIZUKU_SITE = "https://shizuku.rikka.app"
    }
}
