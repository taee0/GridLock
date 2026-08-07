package apps.ijp.coverscreen.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as Sys
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import apps.ijp.coverscreen.launcher.LauncherNotificationListener
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings
import apps.ijp.coverscreen.launcher.glance_widget.WidgetHost
import com.tv.coverscreen.R

/**
 * Every launcher option plus the permission rows.
 *
 * Rows are rebuilt after any change so a value can never sit stale on screen,
 * and each row is inflated into a card section rather than a flat list.
 */
class LauncherSettingsActivity : Activity() {

    private lateinit var s: Settings
    private lateinit var rows: LinearLayout
    private lateinit var inf: LayoutInflater
    private var body: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        s = Settings(this)
        setTheme(
            when (s.theme) {
                Settings.THEME_LIGHT -> R.style.Theme_Launcher_Light
                else -> R.style.Theme_Launcher_Dark
            }
        )
        super.onCreate(savedInstanceState)
        inf = LayoutInflater.from(this)
        setContentView(R.layout.launcher_settings)
        rows = findViewById(R.id.rows)
        setTitle(R.string.settings_title)
        build()
    }

    override fun onResume() {
        super.onResume()
        // permission rows can change while we are in the system settings app
        build()
    }

    private fun build() {
        rows.removeAllViews()

        section(R.string.sec_look)
        choice(R.string.opt_theme, themeName()) {
            pick(
                R.string.opt_theme,
                arrayOf(
                    getString(R.string.theme_system),
                    getString(R.string.theme_light),
                    getString(R.string.theme_dark)
                ),
                s.theme
            ) {
                s.theme = it
                recreate()
            }
        }
        slider(R.string.opt_columns, s.columns, 2, 6) { s.columns = it }
        slider(R.string.opt_rows, s.rows, 2, 8) { s.rows = it }
        slider(R.string.opt_icon, s.iconDp, 28, 80) { s.iconDp = it }
        slider(R.string.opt_label, s.labelSp, 7, 18) { s.labelSp = it }
        toggle(R.string.opt_labels, s.showLabels) { s.showLabels = it }
        slider(R.string.opt_alpha, s.widgetAlpha, 0, 255) { s.widgetAlpha = it }
        // Background colour picker row removed: the background is always
        // black now, and Settings.backgroundColor's default already is
        // 0xFF000000, so nothing else has to change for that. The property
        // itself, and colorName()/COLOR_NAMES/COLORS below, are left in place
        // rather than deleted -- the ability to set a background colour still
        // exists in code, there is just no row here that calls it.
        choice(R.string.opt_bg_image, s.backgroundImage ?: getString(R.string.none_set)) {
            if (s.backgroundImage != null) clearImage() else pickImage()
        }

        section(R.string.sec_behaviour)
        toggle(R.string.opt_cover, s.launchOnCover) { s.launchOnCover = it }
        toggle(R.string.opt_rotate, s.autoRotate) { s.autoRotate = it }
        toggle(R.string.opt_haptics, s.haptics) { s.haptics = it }
        toggle(R.string.opt_sounds, s.sounds) { s.sounds = it }
        toggle(R.string.opt_gestures, s.gestures) { s.gestures = it }
        toggle(R.string.opt_autohide, s.autoHide) { s.autoHide = it }
        slider(R.string.opt_autohide_delay, s.autoHideDelay, 1000, 30000) { s.autoHideDelay = it }
        choice(R.string.opt_overlay_pos, posName()) {
            pick(
                R.string.opt_overlay_pos,
                arrayOf(
                    getString(R.string.pos_top),
                    getString(R.string.pos_center),
                    getString(R.string.pos_bottom)
                ),
                s.overlayPosition
            ) { s.overlayPosition = it }
        }
        toggle(R.string.opt_autolaunch, s.autoStart) { s.autoStart = it }
        toggle(R.string.opt_lockscreen, s.lockScreen) { s.lockScreen = it }

        section(R.string.sec_layout)
        toggle(R.string.opt_header, s.showHeader) { s.showHeader = it }
        slider(R.string.opt_recent_count, s.recentCount, 0, 20) { s.recentCount = it }
        slider(R.string.opt_fav_max, s.favoriteMax, 1, 40) { s.favoriteMax = it }

        section(R.string.sec_apps)
        choice(R.string.opt_sort, sortName()) {
            pick(
                R.string.opt_sort,
                arrayOf(
                    getString(R.string.sort_alpha),
                    getString(R.string.sort_recent),
                    getString(R.string.sort_frequent),
                    getString(R.string.sort_custom)
                ),
                s.sort
            ) { s.sort = it }
        }
        choice(R.string.opt_view, viewName()) {
            pick(
                R.string.opt_view,
                arrayOf(
                    getString(R.string.view_grid),
                    getString(R.string.view_keyboard),
                    getString(R.string.view_recent),
                    getString(R.string.view_favorites)
                ),
                s.view
            ) { s.view = it }
        }
        choice(R.string.fav_add, favSummary()) {
            startActivity(Intent(this, AddToFavoritesCSActivity::class.java))
        }
        choice(R.string.opt_hide_apps, getString(R.string.apps_count, s.hidden.size)) { hideDialog() }
        choice(R.string.opt_custom_order, orderSummary()) { orderDialog() }

        section(R.string.sec_permissions)
        choice(R.string.opt_notifications, granted(LauncherNotificationListener.isEnabled(this))) {
            LauncherNotificationListener.requestAccess(this)
        }
        choice(R.string.opt_overlay, granted(Sys.canDrawOverlays(this))) {
            open(
                Intent(
                    Sys.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }
        choice(R.string.opt_battery, granted(batteryExempt())) {
            open(Intent(Sys.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        choice(R.string.opt_usage, granted(usageGranted())) {
            // Shizuku can set this app op outright, which saves hunting for
            // this app in a system list. Never assume it took: verify, and
            // fall back to Settings whenever it did not.
            val self = packageName
            if (!usageGranted() &&
                com.tv.coverscreen.Privileged.grantUsage(self) &&
                usageGranted()
            ) {
                android.widget.Toast
                    .makeText(this, R.string.usage_granted, android.widget.Toast.LENGTH_SHORT)
                    .show()
                recreate()
            } else {
                open(Intent(Sys.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        section(R.string.sec_apps)
        choice(R.string.opt_reset, "") {
            AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    s.reset()
                    WidgetHost.refreshAll(this)
                    recreate()
                }
                .show()
        }
    }

    // ---- backup / restore ----------------------------------------------

    private fun backupSummary(): String =
        if (com.tv.coverscreen.SettingsBackup.hasBackup(this))
            getString(R.string.backup_present)
        else
            getString(R.string.backup_none)

    private fun runBackup() {
        when (com.tv.coverscreen.SettingsBackup.backup(this)) {
            com.tv.coverscreen.SettingsBackup.Outcome.OK ->
                toast(R.string.backup_ok)
            com.tv.coverscreen.SettingsBackup.Outcome.SKIPPED_CORRUPT ->
                toast(R.string.backup_skipped_corrupt)
            com.tv.coverscreen.SettingsBackup.Outcome.NO_SHIZUKU ->
                toast(R.string.backup_needs_shizuku)
            else ->
                toast(R.string.backup_failed)
        }
        changed()
    }

    private fun runRestore() {
        AlertDialog.Builder(this)
            .setMessage(R.string.restore_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                when (com.tv.coverscreen.SettingsBackup.restore(this)) {
                    com.tv.coverscreen.SettingsBackup.Outcome.OK ->
                        toast(R.string.restore_ok)
                    com.tv.coverscreen.SettingsBackup.Outcome.NO_BACKUP_FOUND ->
                        toast(R.string.restore_none)
                    com.tv.coverscreen.SettingsBackup.Outcome.NO_SHIZUKU ->
                        toast(R.string.backup_needs_shizuku)
                    else ->
                        toast(R.string.restore_failed)
                }
            }
            .show()
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- row builders -------------------------------------------------

    private fun section(res: Int) {
        val head = inf.inflate(R.layout.setting_header, rows, false)
        head.findViewById<TextView>(R.id.title).setText(res)
        rows.addView(head)
        val card = inf.inflate(R.layout.setting_section, rows, false)
        rows.addView(card)
        body = card.findViewById(R.id.body)
    }

    private fun host(): LinearLayout = body ?: rows

    private fun toggle(res: Int, value: Boolean, set: (Boolean) -> Unit) {
        val parent = host()
        val v = inf.inflate(R.layout.setting_switch, parent, false)
        v.findViewById<TextView>(R.id.title).setText(res)
        val sw = v.findViewById<Switch>(R.id.toggle)
        sw.isChecked = value
        // the switch itself is not clickable, the whole row is, which is what
        // makes these rows actually respond to a tap
        v.setOnClickListener {
            val next = !sw.isChecked
            sw.isChecked = next
            set(next)
            changed()
        }
        parent.addView(v)
    }

    private fun slider(res: Int, value: Int, min: Int, max: Int, set: (Int) -> Unit) {
        val parent = host()
        val v = inf.inflate(R.layout.setting_slider, parent, false)
        v.findViewById<TextView>(R.id.title).setText(res)
        val label = v.findViewById<TextView>(R.id.value)
        label.text = value.toString()
        val bar = v.findViewById<SeekBar>(R.id.bar)
        bar.max = max - min
        bar.progress = value - min
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                label.text = (p + min).toString()
            }

            override fun onStartTrackingTouch(b: SeekBar) {}

            override fun onStopTrackingTouch(b: SeekBar) {
                set(b.progress + min)
                // no rebuild here, it would yank the bar out from under the finger
                WidgetHost.refreshAll(this@LauncherSettingsActivity)
            }
        })
        parent.addView(v)
    }

    private fun choice(res: Int, value: String, click: () -> Unit) {
        val parent = host()
        val v = inf.inflate(R.layout.setting_choice, parent, false)
        v.findViewById<TextView>(R.id.title).setText(res)
        val sub = v.findViewById<TextView>(R.id.value)
        sub.text = value
        sub.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        v.setOnClickListener { click() }
        parent.addView(v)
    }

    private fun changed() {
        WidgetHost.refreshAll(this)
        build()
    }

    private fun pick(title: Int, items: Array<String>, selected: Int, set: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items, selected) { d, which ->
                set(which)
                d.dismiss()
                changed()
            }
            .show()
    }

    // ---- app pickers ---------------------------------------------------

    /** hide or unhide anything, not just the already hidden list */
    private fun hideDialog() {
        val repo = AppsRepository(this)
        val all = repo.all().sortedBy { it.name.lowercase() }
        if (all.isEmpty()) return
        val names = all.map {
            if (it.work) getString(R.string.work_app, it.name) else it.name
        }.toTypedArray()
        val checked = BooleanArray(all.size) { s.isHidden(all[it].key) }
        AlertDialog.Builder(this)
            .setTitle(R.string.opt_hide_apps)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) s.hide(all[which].key)
                else s.unhide(all[which].key)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                repo.invalidate()
                changed()
            }
            .show()
    }

    /** move a single app up or down, which is enough to author a custom order */
    private fun orderDialog() {
        val repo = AppsRepository(this)
        val order = s.customOrder.toMutableList()
        if (order.isEmpty()) {
            order.addAll(repo.visible().sortedBy { it.name.lowercase() }.map { it.key })
        }
        val byPkg = repo.all().associateBy { it.key }
        val labels = order.map { byPkg[it]?.name ?: it }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.opt_custom_order)
            .setItems(labels) { _, which -> moveDialog(order, which, labels[which]) }
            .show()
    }

    private fun moveDialog(order: MutableList<String>, index: Int, label: String) {
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(
                arrayOf(getString(R.string.move_up), getString(R.string.move_down))
            ) { _, which ->
                val to = if (which == 0) index - 1 else index + 1
                if (to in order.indices) {
                    val item = order.removeAt(index)
                    order.add(to, item)
                    s.customOrder = order
                    s.sort = Settings.SORT_CUSTOM
                    changed()
                }
            }
            .show()
    }

    private fun favSummary(): String {
        val n = AppsRepository(this).favorites().size
        return getString(R.string.apps_count, n)
    }

    private fun orderSummary(): String {
        val n = s.customOrder.size
        return if (n == 0) getString(R.string.none_set) else getString(R.string.apps_count, n)
    }

    // ---- background image ----------------------------------------------

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(i, REQ_IMAGE)
        } catch (t: Throwable) {
            // no document provider on the cover, nothing sane to fall back to
        }
    }

    private fun clearImage() {
        s.backgroundImage = null
        changed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (t: Throwable) {
            // a non persistable provider still works for this session
        }
        s.backgroundImage = uri.toString()
        changed()
    }

    // ---- permission probes ----------------------------------------------

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun usageGranted(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun granted(value: Boolean) =
        getString(if (value) R.string.granted else R.string.not_granted)

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            // some cover screens block the settings deep link, ignore
        }
    }

    // ---- labels -----------------------------------------------------------

    private fun posName() = when (s.overlayPosition) {
        Settings.POS_TOP -> getString(R.string.pos_top)
        Settings.POS_BOTTOM -> getString(R.string.pos_bottom)
        else -> getString(R.string.pos_center)
    }

    private fun themeName() = when (s.theme) {
        Settings.THEME_LIGHT -> getString(R.string.theme_light)
        Settings.THEME_DARK -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_system)
    }

    private fun sortName() = when (s.sort) {
        Settings.SORT_RECENT -> getString(R.string.sort_recent)
        Settings.SORT_FREQUENT -> getString(R.string.sort_frequent)
        Settings.SORT_CUSTOM -> getString(R.string.sort_custom)
        else -> getString(R.string.sort_alpha)
    }

    private fun viewName() = when (s.view) {
        Settings.VIEW_KEYBOARD -> getString(R.string.view_keyboard)
        Settings.VIEW_RECENT -> getString(R.string.view_recent)
        Settings.VIEW_FAVORITES -> getString(R.string.view_favorites)
        else -> getString(R.string.view_grid)
    }

    private fun colorName(c: Int) = when (c) {
        0xFF1C1C1E.toInt() -> "Grey"
        0xFF0B1B33.toInt() -> "Navy"
        0xFFFFFFFF.toInt() -> "White"
        else -> "Black"
    }

    private companion object {
        const val REQ_IMAGE = 4101
        val COLOR_NAMES = arrayOf("Black", "Grey", "Navy", "White")
        val COLORS = listOf(
            0xFF000000.toInt(),
            0xFF1C1C1E.toInt(),
            0xFF0B1B33.toInt(),
            0xFFFFFFFF.toInt()
        )
    }
}
