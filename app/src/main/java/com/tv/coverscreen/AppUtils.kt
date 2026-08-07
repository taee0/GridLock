package com.tv.coverscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

data class LaunchableApp(
    val pkg: String,
    val activity: String,
    val label: String,
    val user: UserHandle,
    val userSerial: Long
)

/**
 * Launchable app enumeration and launching, per the integration guide.
 *
 * Everything in here is profile aware. An app installed in both the personal
 * and the work profile is two different launchable things: two components, two
 * icons, two entries in recents, and two favourites if you want them. The only
 * thing that tells them apart is the UserHandle, so the UserHandle, or rather
 * its serial number, travels with every app from enumeration all the way to
 * launch.
 *
 * Identity is the string [keyFor] builds. Personal apps keep the bare package
 * name, so every favourite, hidden entry and custom order row written before
 * this existed still resolves and nothing moves. Work apps get "pkg@serial".
 * That is why there is no database migration.
 */
object AppUtils {

    @Volatile
    private var mySerialCache: Long? = null

    private fun users(context: Context): UserManager =
        context.getSystemService(Context.USER_SERVICE) as UserManager

    private fun launcherApps(context: Context): LauncherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    /** Serial of the profile this process runs in. */
    fun mySerial(context: Context): Long {
        mySerialCache?.let { return it }
        val v = serialOf(context, Process.myUserHandle())
        mySerialCache = v
        return v
    }

    fun serialOf(context: Context, user: UserHandle): Long =
        try {
            users(context).getSerialNumberForUser(user)
        } catch (t: Throwable) {
            0L
        }

    fun userFor(context: Context, serial: Long): UserHandle? =
        try {
            users(context).getUserForSerialNumber(serial)
        } catch (t: Throwable) {
            null
        }

    /** Composite identity. Bare package for personal, "pkg@serial" for work. */
    fun keyFor(context: Context, pkg: String, serial: Long): String =
        if (serial == mySerial(context)) pkg else pkg + "@" + serial

    fun pkgOfKey(key: String): String {
        val at = key.indexOf('@')
        return if (at < 0) key else key.substring(0, at)
    }

    fun serialOfKey(context: Context, key: String): Long {
        val at = key.indexOf('@')
        if (at < 0) return mySerial(context)
        return key.substring(at + 1).toLongOrNull() ?: mySerial(context)
    }

    /** True when the profile is a work profile that is currently paused. */
    fun quiet(context: Context, serial: Long): Boolean {
        val user = userFor(context, serial) ?: return false
        if (serial == mySerial(context)) return false
        return try {
            users(context).isQuietModeEnabled(user)
        } catch (t: Throwable) {
            false
        }
    }

    fun isWork(context: Context, serial: Long): Boolean = serial != mySerial(context)

    fun launchable(context: Context): List<LaunchableApp> {
        val la = launcherApps(context)
        val out = ArrayList<LaunchableApp>()
        for (user in la.profiles) {
            val serial = serialOf(context, user)
            val list = try {
                la.getActivityList(null, user)
            } catch (t: Throwable) {
                // A locked or removed profile throws rather than returning empty.
                continue
            }
            for (a in list) {
                out.add(
                    LaunchableApp(
                        a.componentName.packageName,
                        a.componentName.className,
                        a.label.toString(),
                        user,
                        serial
                    )
                )
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    fun fallbackLaunchable(context: Context): List<ResolveInfo> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
    }

    /**
     * Icon for a composite key, badged with the system briefcase when the app
     * lives in a work profile.
     *
     * getApplicationIcon() only ever looks in the caller's own profile, so it
     * returns the personal copy for a dual installed app and throws outright
     * for a work only one. LauncherActivityInfo.getIcon() is per profile, and
     * getUserBadgedIcon() is what draws the briefcase the system uses
     * everywhere else.
     */
    fun icon(context: Context, key: String): Drawable? {
        val pkg = pkgOfKey(key)
        val serial = serialOfKey(context, key)
        val user = userFor(context, serial)
        if (user != null) {
            try {
                val dpi = context.resources.displayMetrics.densityDpi
                val info = launcherApps(context).getActivityList(pkg, user).firstOrNull()
                if (info != null) {
                    val raw = info.getIcon(dpi)
                    if (raw != null) {
                        return context.packageManager.getUserBadgedIcon(raw, user)
                    }
                }
            } catch (t: Throwable) {
                // fall through to the personal profile lookup below
            }
        }
        return try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun label(context: Context, pkg: String): String =
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }

    /** launch on a specific display, which is what makes it land on the cover */
    fun launchOnDisplay(context: Context, app: LaunchableApp, displayId: Int) {
        launchOnDisplay(context, app.pkg, app.activity, app.userSerial, displayId)
    }

    /**
     * The only launch path. It resolves the profile first and goes through
     * LauncherApps.startMainActivity, which is the one call that can cross a
     * profile boundary.
     *
     * There used to be a String overload here that called
     * getLaunchIntentForPackage(). That resolves in the caller's profile only,
     * so every tap on a work app silently opened the personal copy instead.
     * It is gone on purpose; do not add it back.
     */
    fun launchOnDisplay(
        context: Context,
        pkg: String,
        activity: String?,
        serial: Long,
        displayId: Int
    ) {
        val la = launcherApps(context)
        val user = userFor(context, serial) ?: Process.myUserHandle()
        val opts = android.app.ActivityOptions.makeBasic().apply {
            if (displayId >= 0) launchDisplayId = displayId
        }
        val component = if (!activity.isNullOrEmpty()) {
            ComponentName(pkg, activity)
        } else {
            la.getActivityList(pkg, user).firstOrNull()?.componentName ?: return
        }
        la.startMainActivity(component, user, null, opts.toBundle())
    }

    /** Convenience for callers that only hold a composite key. */
    fun launchKeyOnDisplay(context: Context, key: String, activity: String?, displayId: Int) {
        launchOnDisplay(context, pkgOfKey(key), activity, serialOfKey(context, key), displayId)
    }

    fun me(): UserHandle = Process.myUserHandle()
}
