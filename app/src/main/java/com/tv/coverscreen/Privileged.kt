package com.tv.coverscreen

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Everything the app cannot do on its own, routed through Shizuku.
 *
 * Shizuku runs a small server as the adb shell user (uid 2000) and proxies our
 * binder calls through it, so we get what "adb shell" gets: REMOVE_TASKS,
 * FORCE_STOP_PACKAGES, REAL_GET_TASKS, REORDER_TASKS, READ_FRAME_BUFFER. Enough
 * for real task removal, the system's own task snapshots, the real recents list,
 * and real task resume.
 *
 * Nothing here is load bearing. Every entry point answers null or false when
 * Shizuku is missing, not started, or not granted, and the caller keeps its old
 * behavior. The app must build, install and run identically on a phone that has
 * never heard of Shizuku.
 *
 * Framework internals are reached by reflection instead of hidden api stubs, so a
 * signature change on one OEM build degrades that single call instead of failing
 * the compile. Non-SDK restrictions are lifted once per process by HiddenApiBypass,
 * which is also called reflectively.
 */
object Privileged {

    private const val TAG = "Privileged"

    /** Request code handed to Shizuku.requestPermission. */
    const val PERM = 4711

    /** Skip tasks the system considers unavailable. */
    private const val IGNORE_UNAVAILABLE = 0x0002

    /** Calls run as shell, so appops has to see shell's name. */
    private const val SHELL = "com.android.shell"

    private const val ATM = "android.app.IActivityTaskManager"
    private const val APPOPS = "com.android.internal.app.IAppOpsService"
    private const val AM = "android.app.IActivityManager"

    private val INT: Class<*> = Int::class.javaPrimitiveType!!
    private val BOOL: Class<*> = Boolean::class.javaPrimitiveType!!
    private val STR: Class<*> = String::class.java
    private val BUNDLE: Class<*> = Bundle::class.java

    /** OFFLINE covers both not installed and installed but not started. */
    enum class Access { OFFLINE, LEGACY, DENIED, READY }

    data class Task(
        val taskId: Int,
        val pkg: String,
        val activity: String?,
        val lastActive: Long,
    )

    @Volatile private var exempted = false
    @Volatile private var atm: Any? = null
    @Volatile private var am: Any? = null

    private val watchers = ArrayList<() -> Unit>()
    private var hooked = false

    // ---------------------------------------------------------------- state

    /** True when the Shizuku server is up. Never throws. */
    fun alive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun access(): Access {
        if (!alive()) return Access.OFFLINE
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return Access.LEGACY
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Access.READY else Access.DENIED
    }

    fun ready(): Boolean = access() == Access.READY

    /** 0 for root, 2000 for adb, -1 when unknown. */
    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** One line for the settings screen. */
    fun describe(): String = when (access()) {
        Access.READY -> if (uid() == 0) "Connected as root" else "Connected as shell"
        Access.DENIED -> "Installed, permission not granted"
        Access.LEGACY -> "Shizuku too old, needs v11+"
        Access.OFFLINE -> "Not running"
    }

    fun request() {
        if (!alive()) return
        runCatching {
            if (!Shizuku.isPreV11()) Shizuku.requestPermission(PERM)
        }.onFailure { Log.w(TAG, "request: " + it) }
    }

    /**
     * Called whenever the binder arrives, dies, or permission changes. Callbacks
     * land on the main thread. Cached handles are dropped first so the next call
     * rebuilds them.
     */
    fun watch(cb: () -> Unit) {
        watchers.add(cb)
        if (hooked) return
        hooked = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(
                Shizuku.OnBinderReceivedListener { drop(); fire() }
            )
            Shizuku.addBinderDeadListener(
                Shizuku.OnBinderDeadListener { drop(); fire() }
            )
            Shizuku.addRequestPermissionResultListener(
                Shizuku.OnRequestPermissionResultListener { _, _ -> drop(); fire() }
            )
        }.onFailure { Log.w(TAG, "watch: " + it) }
    }

    fun unwatch(cb: () -> Unit) {
        watchers.remove(cb)
    }

    private fun fire() {
        for (w in ArrayList(watchers)) runCatching { w() }
    }

    /** Forget cached service handles. Cheap: they are rebuilt on demand. */
    fun drop() {
        atm = null
        am = null
    }

    // --------------------------------------------------------- capabilities

    /**
     * The real recents list, most recent first, the same source quickstep reads.
     * Null when unavailable.
     */
    fun tasks(max: Int = 32): List<Task>? {
        val service = atm() ?: return null
        val slice = call(
            service, "getRecentTasks", arrayOf(INT, INT, INT),
            max, IGNORE_UNAVAILABLE, user()
        ).getOrNull() ?: return null
        val raw = runCatching {
            slice.javaClass.getMethod("getList").invoke(slice) as? List<*>
        }.getOrNull() ?: return null

        val out = ArrayList<Task>(raw.size)
        for (info in raw) {
            if (info == null) continue
            val id = field(info, "taskId") as? Int ?: continue
            if (id < 0) continue
            val top = field(info, "topActivity") as? ComponentName
            val base = field(info, "baseActivity") as? ComponentName
            val intent = field(info, "baseIntent") as? Intent
            val pkg = top?.packageName
                ?: base?.packageName
                ?: intent?.component?.packageName
                ?: continue
            val last = field(info, "lastActiveTime") as? Long ?: 0L
            out.add(Task(id, pkg, (top ?: base)?.className, last))
        }
        return out
    }

    /**
     * The system's own thumbnail for a task: already captured, already scaled, no
     * capture interval to wait on, no accessibility screenshot budget to burn.
     */
    fun snapshot(taskId: Int, fresh: Boolean = false): Bitmap? {
        val service = atm() ?: return null

        // A task that is still on screen has nothing filed away yet: the system
        // only stores a snapshot as a task leaves. Asking the cache about the app
        // you are looking at gets you the placeholder, so force a capture for
        // those and read the cache for everything already in the background.
        val snap = if (fresh) {
            call(service, "takeTaskSnapshot", arrayOf(INT, BOOL), taskId, true).getOrNull()
                ?: call(service, "takeTaskSnapshot", arrayOf(INT), taskId).getOrNull()
                ?: call(service, "getTaskSnapshot", arrayOf(INT, BOOL), taskId, false).getOrNull()
        } else {
            call(service, "getTaskSnapshot", arrayOf(INT, BOOL), taskId, false).getOrNull()
                ?: call(service, "takeTaskSnapshot", arrayOf(INT, BOOL), taskId, true).getOrNull()
                ?: call(service, "takeTaskSnapshot", arrayOf(INT), taskId).getOrNull()
        } ?: return null

        // AOSP: "whether or not the snapshot is a real snapshot or an app-theme
        // generated snapshot due to the task having a secure window or having
        // previews disabled". An app-theme snapshot is a flat themed background
        // with the launcher icon drawn on it, which is exactly the icon card.
        // Refuse it so the caller falls back to taking the picture itself.
        val real = runCatching {
            snap.javaClass.getMethod("isRealSnapshot").invoke(snap) as? Boolean
        }.getOrNull() ?: true
        if (!real) {
            Log.d(TAG, "task " + taskId + ": app-theme placeholder, refused")
            return null
        }
        val buffer = runCatching {
            snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer
        }.getOrNull() ?: return null
        val space = runCatching {
            snap.javaClass.getMethod("getColorSpace").invoke(snap) as? ColorSpace
        }.getOrNull()
        return try {
            Bitmap.wrapHardwareBuffer(buffer, space)?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot " + taskId + ": " + t)
            null
        } finally {
            runCatching { buffer.close() }
        }
    }

    /**
     * Really remove a task from recents, the way a swipe in the system switcher
     * does. The process is not killed, it is released like any backgrounded app.
     */
    fun close(taskId: Int): Boolean {
        if (taskId < 0) return false
        val service = atm() ?: return false
        return call(service, "removeTask", arrayOf(INT), taskId).getOrNull() as? Boolean ?: false
    }

    /** Really stop an app: same as Force stop in Settings. Blunt. Use sparingly. */
    fun stop(pkg: String): Boolean {
        val service = am() ?: return false
        return call(service, "forceStopPackage", arrayOf(STR, INT), pkg, user()).isSuccess
    }

    /**
     * Resume the actual task instead of firing the launcher intent, so the app
     * comes back exactly where it was left. displayId targets the cover screen.
     */
    fun resume(taskId: Int, displayId: Int): Boolean {
        if (taskId < 0) {
            Log.d(TAG, "resume: invalid taskId " + taskId)
            return false
        }
        val service = atm() ?: run {
            Log.d(TAG, "resume: atm() unavailable (access=" + access() + ")")
            return false
        }
        val opts = runCatching {
            val o = ActivityOptions.makeBasic()
            if (displayId >= 0) o.launchDisplayId = displayId
            o.toBundle()
        }.getOrNull()

        val startedResult = call(
            service, "startActivityFromRecents", arrayOf(INT, BUNDLE), taskId, opts
        )
        val started = startedResult.getOrNull() as? Int
        Log.d(TAG, "resume: startActivityFromRecents taskId=" + taskId + " displayId=" + displayId + " resultCode=" + started + " failure=" + startedResult.exceptionOrNull())
        if (started != null && started >= 0) return true

        // Older or vendor builds: plain reorder. The IApplicationThread slot takes
        // null, and shell's name keeps appops from rejecting us.
        val thread = runCatching { Class.forName("android.app.IApplicationThread") }.getOrNull()
            ?: run {
                Log.d(TAG, "resume: IApplicationThread class not found, cannot fall back")
                return false
            }
        val fallback = call(
            service, "moveTaskToFront", arrayOf(thread, STR, INT, INT, BUNDLE),
            null, SHELL, taskId, 0, opts
        )
        Log.d(TAG, "resume: moveTaskToFront taskId=" + taskId + " displayId=" + displayId + " success=" + fallback.isSuccess + " failure=" + fallback.exceptionOrNull())
        return fallback.isSuccess
    }

    /**
     * Turn usage access on for [pkg].
     *
     * Usage access is an app op, not a runtime permission, so pm grant cannot
     * touch it and the user is normally sent to a Settings list to find this
     * app by hand. Shell can set it outright: this is the binder form of
     * `cmd appops set <pkg> GET_USAGE_STATS allow`, which is all the app op
     * managers built on Shizuku are really doing.
     *
     * The op code is looked up, never hardcoded. Codes are renumbered between
     * releases and writing the wrong one would silently flip some unrelated
     * permission, so a failed lookup answers false and the caller falls back
     * to opening Settings.
     */
    fun grantUsage(pkg: String): Boolean {
        val service = stub(APPOPS, "appops") ?: return false
        val code = usageOp()
        if (code == null) {
            Log.w(TAG, "no op code for get_usage_stats, refusing to guess")
            return false
        }
        val ok = call(
            service, "setMode", arrayOf(INT, INT, STR, INT),
            code, Process.myUid(), pkg, AppOpsManager.MODE_ALLOWED,
        ).isSuccess
        Log.i(TAG, "grantUsage op=" + code + " uid=" + Process.myUid() + " ok=" + ok)
        return ok
    }

    /** strOpToOp is hidden, so the exemption has to be in place first. */
    private fun usageOp(): Int? {
        exempt()
        return runCatching {
            AppOpsManager::class.java
                .getMethod("strOpToOp", String::class.java)
                .invoke(null, AppOpsManager.OPSTR_GET_USAGE_STATS) as Int
        }.onFailure { Log.w(TAG, "strOpToOp: " + it) }.getOrNull()
    }

    // -------------------------------------------------------------- plumbing

    private fun user(): Int = Process.myUid() / 100000

    private fun atm(): Any? {
        atm?.let { return it }
        val made = stub(ATM, "activity_task")
        atm = made
        return made
    }

    private fun am(): Any? {
        am?.let { return it }
        val made = stub(AM, "activity")
        am = made
        return made
    }

    /** Wrap a system service binder so its transactions run as shell. */
    private fun stub(iface: String, service: String): Any? {
        if (!ready()) return null
        val raw = binder(service) ?: return null
        return runCatching {
            Class.forName(iface + "\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(raw))
        }.onFailure { Log.w(TAG, "stub " + service + ": " + it) }.getOrNull()
    }

    private fun binder(service: String): IBinder? {
        exempt()
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, service) as? IBinder
        }.onFailure { Log.w(TAG, "binder " + service + ": " + it) }.getOrNull()
    }

    /** Lift non-SDK interface restrictions once, for everything. */
    private fun exempt() {
        if (exempted) return
        exempted = true
        runCatching {
            Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
                .getMethod("addHiddenApiExemptions", Array<String>::class.java)
                .invoke(null, arrayOf(""))
        }.onFailure { Log.w(TAG, "exempt: " + it) }
    }

    private fun call(
        target: Any,
        name: String,
        sig: Array<Class<*>>,
        vararg args: Any?,
    ): Result<Any?> = runCatching {
        val m = target.javaClass.getMethod(name, *sig)
        m.isAccessible = true
        m.invoke(target, *args)
    }.onFailure {
        Log.w(TAG, name + ": " + it)
        if (stale(it)) drop()
    }

    private fun stale(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is DeadObjectException) return true
            e = e.cause
        }
        return false
    }

    private fun field(owner: Any, name: String): Any? {
        var c: Class<*>? = owner.javaClass
        while (c != null) {
            val here: Class<*> = c
            val got = runCatching {
                val f = here.getDeclaredField(name)
                f.isAccessible = true
                f.get(owner)
            }
            if (got.isSuccess) return got.getOrNull()
            c = here.superclass
        }
        return null
    }
}
