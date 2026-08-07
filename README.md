# Cover Screen

**A real recents switcher, an app launcher, and auto-rotate for the Galaxy Z Flip 6 cover panel.**

> [!IMPORTANT]
> **This is for the Samsung Galaxy Z Flip 6 only, running One UI 8.5.**
>
> That is one device on one firmware version, and it is the only combination this has ever been built on or tested against. Every other One UI release, every other Flip generation, and every other phone is **untested**. It might work, it might misbehave, it might do nothing at all. Samsung moves cover screen internals between releases, so do not assume a newer or older One UI build behaves the same way.

Samsung ships a cover screen that can run apps but gives you almost no way to move between them. There is no app switcher, no launcher grid, and the panel is locked to portrait. This app adds all three, on a stock unrooted phone, without root and without `SYSTEM_ALERT_WINDOW` for the switcher.

```
Pull up from the nav bar and hold  ->  recents
Tap the nav bar                    ->  back / home / recents, unchanged
Turn the closed phone sideways     ->  the cover panel follows
```

---

## Table of contents

- [What this actually does](#what-this-actually-does)
- [Feature list](#feature-list)
- [Requirements](#requirements)
- [Install](#install)
- [Setup, step by step](#setup-step-by-step)
- [Using it](#using-it)
- [Every setting](#every-setting)
- [Permissions, and why each one exists](#permissions-and-why-each-one-exists)
- [Privacy](#privacy)
- [Shizuku: what changes](#shizuku-what-changes)
- [How it works](#how-it-works)
- [Project layout](#project-layout)
- [Building from source](#building-from-source)
- [Testing over adb](#testing-over-adb)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Provenance and licensing](#provenance-and-licensing)

---

## What this actually does

Three independent features share one process. You can use any one of them and ignore the rest.

### 1. Recents switcher

Put your thumb on the native nav bar on the cover panel and pull up. Hold briefly and the recents switcher opens. Let go immediately and it is an ordinary nav bar swipe, so home and back still work exactly as before.

The app ships with `NATIVE_RECENTS = true`, which throws Samsung's own `com.android.quickstep.RecentsActivity` onto the cover panel. The project also contains a complete custom card deck (`Switcher.kt`, `TiltStack.kt`, `Spring.kt`, `CardAdapter.kt`) with finger-tracked drag, spring physics, 3D tilt and swipe-to-dismiss. It is fully wired but asleep. Set `NATIVE_RECENTS = false` in `RecentsEngine.kt` to wake it.

### 2. Cover launcher

A full app launcher for the cover panel, available two ways: as a **Samsung cover screen widget** (`display="sub_screen"`) that appears in the cover widget carousel, and as a **full activity** you can open directly. Grid or list, search, favourites, recents, hidden apps, work profile support.

### 3. Cover auto-rotate

Samsung locks the cover panel to portrait. This unlocks it. Turn the closed phone sideways and the panel follows.

---

## Feature list

### Recents switcher

- Pull-up-and-hold gesture off the native nav bar
- Quick swipe passes straight through to the real nav bar, so home and back are untouched
- Sideways swipes are released entirely and never captured
- Taps are forwarded to the real nav button underneath by walking the accessibility node tree, so your button order is respected
- Pressing the real recents button also opens the switcher
- Haptic tick when the hold registers
- Catcher sits 12dp up off the bottom edge so a phone case lip does not eat the gesture
- Cutout-aware: reads the panel's own `DisplayCutout` every rotation, since a Flip's camera bump moves edges when the panel turns

### Custom card deck (present, disabled by default)

- Progress-driven drag locked to your finger, not a threshold that pops a window in
- Damped spring settle using the finger's real exit velocity (`Spring.kt`, semi-implicit Euler, substepped, clamped frame delta)
- 3D tilt stack with depth-sorted cards, perspective camera distance, and centre-facing rotation (`TiltStack.kt`)
- Flick a card up to dismiss
- Tap a card to return to that app
- Close all, One UI style: pinned apps survive
- Keep open (pin) per app
- Rubber-band resistance past the top of the pull

### App launcher

- **Grid view** with configurable columns (2-6) and rows (2-8)
- **List view** with A-Z letter headers and jump-to-letter navigation
- **Recent view**, **Favourites view**
- **Ranked fuzzy search**: exact match > prefix > word start > substring > initials > package name > subsequence, so `fb` finds Facebook and `gmp` finds Google Maps
- Debounced at 120ms so typing does not outrun the search
- **Favourites** with a dedicated picker: search, tap to add, drag to reorder, staged in memory and only written on Save, with the cap actually enforced
- **Hidden apps** via multi-select
- **Custom app order** with move up / move down
- **Sort** by A-Z, recent, most used, or custom
- **Notification badges** per app, with a total count, updated by targeted rebind rather than a full refresh
- **Long press** an app for favourite / hide / app info
- **Work profile support** throughout: dual-installed apps are two separate entries with their own badged icons, their own favourites, and correct cross-profile launching via `LauncherApps.startMainActivity`. Paused work profiles dim at 45% opacity like the system launcher does
- Icons cached in an `LruCache` sized to 1/8 of the app heap, keyed per profile and per pixel size
- Everything expensive runs off the main thread; list updates go through `DiffUtil`

### Cover screen widget

- Registers with Samsung's `sub_screen` descriptor so it appears in the cover widget carousel
- Two independent widget hosts (standard and overlay) with separate `RemoteViewsService` factories
- Toolbar: home, grid/list toggle, recents, favourites, sort cycle, settings
- Tap a letter header to jump to that section, tap it again to clear
- Star any row to toggle favourite directly from the widget
- Hideable toolbar, with the subtitle taking over as the way back when it is hidden
- Configurable background colour and opacity

### Auto-rotate

- Accelerometer bucketed into four quadrants with a 220ms hold, so a flick past landscape does nothing
- Only runs while the phone is shut and the cover panel is the lit one, watched via `DisplayManager.DisplayListener`
- **Strict mode** for builds that ignore a sensor request from a non-activity window: names the exact direction instead of asking nicely
- `Kick`, an invisible one-shot activity, forces the window policy to recompute on builds that need it. It self-destructs if it ever lands on the inner display

### Recents data

- Three-tier source, best first: Shizuku task list, then `UsageStatsManager`, then the app's own records. Each tier degrades silently to the next
- Screenshots cached 8MB in memory and as lossy WEBP on disk, long edge capped at 480px
- Survives a reboot

---

## Requirements

| | |
|---|---|
| **Device** | **Samsung Galaxy Z Flip 6 only.** The one device this was built on and tested against |
| **Firmware** | **One UI 8.5 only.** Untested on every other One UI version |
| **Android** | The manifest allows 12 or newer (`minSdk 31`) and targets SDK 37, but see above - installable is not the same as verified |
| **Root** | **Not required** |
| **Shizuku** | **Optional.** Every feature works without it. See [Shizuku: what changes](#shizuku-what-changes) |
| **Architecture** | arm64-v8a only |

This is a personal project built for one phone on one firmware version. It is not on the Play Store, and there is no promise it behaves on hardware or software I have never held. If you are not on a Flip 6 running One UI 8.5, treat everything below as a description of what it does on **my** phone, not a guarantee of what it will do on yours.

Everything below is read out of the build files, the manifest and the code, not guessed. Where a thing is a genuine blocker, it says so.

### Hard blockers

Without these the app either will not install, or installs and does nothing.

| Requirement | Where it comes from | What happens without it |
|---|---|---|
| **arm64-v8a CPU** | `app/build.gradle`: `ndk { abiFilters += listOf("arm64-v8a") }` | The APK will not install. There is deliberately no 32-bit or x86 variant |
| **Android 12 / API 31+** | `minSdk = 31` | The package installer refuses it |
| **A real second display** | `Cover.panel()` returns the smallest valid display whose id is not `Display.DEFAULT_DISPLAY` | Returns `null`. The overlay never attaches and nothing responds. A normal one-screen phone can never satisfy this |
| **Accessibility service enabled** | `RecentsEngine` *is* the `AccessibilityService`. `res/xml/recents_service.xml` requests `canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot`, `flagRetrieveInteractiveWindows` | No gesture, no switcher, no auto-rotate. This one toggle gates the entire app |
| **Draw over other apps** | `SYSTEM_ALERT_WINDOW`. Both the switcher and the auto-rotate holder are real `TYPE_APPLICATION_OVERLAY` windows | `addView` fails silently |
| **Launching activities on a secondary display** | Apps start via `LauncherApps.startMainActivity(...)` with `ActivityOptions.setLaunchDisplayId` | Taps either open the app on the inner screen or do nothing |

On some One UI builds you may also need `adb shell settings put global force_resizable_activities 1` before arbitrary apps will open on the cover panel. That is an environment tweak, not something the code sets.

### Samsung-specific wiring

These degrade rather than crash, but they are why this is realistically a Samsung app.

| Feature | How it is wired | On non-Samsung |
|---|---|---|
| **Cover widget carousel slot** | `res/xml/samsung_widget_cover_info.xml` is a `<samsung-appwidget-provider display="sub_screen" />`, attached in the manifest via `com.samsung.android.appwidget.provider`, plus the `com.samsung.android.sdk.subscreen.widget.support_visibility_callback` property | The descriptor is ignored. The widget still exists as an ordinary home-screen widget but never appears in the cover carousel |
| **Recents hand-off** | `RecentsEngine` hardcodes `LAUNCHER_PKG = "com.sec.android.app.launcher"` and `RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity"` | The hold gesture has nothing to hand off to |
| **Switcher blocklist** | `Cover.ignored()` hardcodes `com.samsung.android.app.cocktailbarservice`, `com.samsung.android.coverscreen`, `com.sec.android.app.launcher` | Harmless, those packages simply are not installed |
| **Widget cell size** | `app_launcher_widget_info.xml` declares `minWidth="748px"` / `minHeight="720px"` in literal pixels, which is exactly the Z Flip 6 cover panel | Mis-sized on any other panel |

### Device profile detection is a string match

`NativeLayout.kt` chooses a layout profile like this and nothing more:

```kotlin
val m = (Build.MODEL + " " + Build.DEVICE + " " + Build.PRODUCT).lowercase()
when {
    m.contains("razr") && m.contains("40") -> Device.RAZR4
    m.contains("razr")                     -> Device.RAZR36
    else                                   -> Device.FLIP
}
```

Read that `else` carefully. **Every device that is not a Razr is treated as a Flip.** There is no allowlist and no unsupported-device branch.

The Razr branches are inherited from the original library's method table, not a supported target. Every one of them calls a `native` method - `nI1`, `nJ1`, `nK1`, `nL1` and their `2` variants - that lives inside `libsparx.so`, and that library is not in this repository. So `available` is `false`, the whole branch is skipped, and execution falls straight through to `handBuilt()`. **A Razr and a Flip currently produce identical window parameters.** The detection runs and then nothing consumes the result.

Even with the library supplied, the rest of the app is hard-wired to Samsung: the recents hand-off targets `com.sec.android.app.launcher`, the cover widget descriptor is a `<samsung-appwidget-provider>`, and the widget size is the Flip 6 panel in literal pixels. **This is not a Razr app.**

### The two native libraries are optional

`libspark.so` and `libsparx.so` are **not in this repository**, and the app runs fine without them. This looks alarming at first glance, so here is exactly how it works.

Three places try to load them:

| Loader | Library |
|---|---|
| `NativeRecentsFactory.kt` | `System.loadLibrary("spark")` |
| `CoverScreenAppLauncherApp.java` | `System.loadLibrary("sparx")` |
| `NativeLayoutParamsFactory.java` | `System.loadLibrary("sparx")` |

Every one is wrapped in a `try`/`catch` that records the error and sets `loaded = false`. Nothing throws upward.

Every value those libraries would have returned is hardcoded in `Native.kt`, decoded out of the binary's `.data` section:

```kotlin
// calls the native getter if it bound, returns the decoded constant if it did not
val windowType:  Int get() = int({ NativeRecentsFactory.gL1() }, L1)  // 2032 = TYPE_ACCESSIBILITY_OVERLAY
val windowFlags: Int get() = int({ NativeRecentsFactory.gL2() }, L2)  // 0x40728
val slopPx:    Float get() = float({ NativeRecentsFactory.gT1() }, T1) // 80px
```

`NativeLayout` does the same with window `LayoutParams`, falling back down a per-device chain and finally to `handBuilt()`.

So binding `libspark.so` **changes nothing behaviourally**. The only thing it would add is the ability for `nS1` to move those constants at runtime, which nothing here uses. If you ever do supply the library, `NativeRecentsFactory` must stay in package `apps.ijp.coverrecents` - `JNI_OnLoad` does a `FindClass` on that literal path and registration fails otherwise.

### Sensors and hardware

| Feature | Hardware | Manifest declaration |
|---|---|---|
| Auto-rotate | Accelerometer | `<uses-feature android.hardware.sensor.accelerometer required="false" />` |
| Haptic feedback | Vibrator | `VIBRATE` |
| Cover panel | Secondary display | `<uses-feature android.software.activities_on_secondary_displays required="false" />` |

Both features are declared `required="false"` so the APK stays installable, but auto-rotate is dead without an accelerometer.

### Version-gated code paths

Only two places in the entire codebase branch on OS version:

| File | Gate | Effect |
|---|---|---|
| `OverlayCoverScreen.kt:94` | `SDK_INT >= 34` | Android 14+ passes an explicit foreground-service type when starting the overlay |
| `LauncherSettingsActivity.kt:392` | `SDK_INT >= Q` | Uses the newer night-mode API |

Everything else assumes API 31 behaviour is simply available.

### Optional, per feature

| Feature | Needs |
|---|---|
| Notification badges in the launcher | Notification listener access (`LauncherNotificationListener`) |
| "Recent" and "Frequent" app sorting | Usage access. `PACKAGE_USAGE_STATS` is an appop, so declaring it does not grant it - Shizuku can set it directly, Settings is the fallback |
| Real task removal, system task snapshots, real resume | Shizuku 13.1.5+, running and granted |
| Foreground service notification | `POST_NOTIFICATIONS` on Android 13+ |

### Build machine

| | |
|---|---|
| **Android Gradle Plugin** | 9.3.1 |
| **Gradle** | 9.5 |
| **compileSdk / targetSdk** | 37 - SDK Platform 37 must be installed |
| **Java source/target** | 11 |
| **JDK for Gradle** | Auto-provisioned by the `foojay-resolver-convention` toolchain plugin, so you do not have to install one by hand |
| **Network at build time** | **Required.** `hiddenapibypass` uses the open version range `[4.3,)`, so Gradle resolves the newest published build on every run. Builds are therefore not byte-reproducible |
| **Configuration cache** | Enabled (`org.gradle.configuration-cache=true`) |
| **R8 / minification** | **Disabled in release** (`optimization { enable = false }`). `app/src/main/keepRules/rules.keep` exists but nothing currently shrinks the app |
| **Native packaging** | `jniLibs { useLegacyPackaging = false }` - if you ever add the `.so` files they ship uncompressed and page-aligned so the loader maps them straight out of the APK |

---

## Install

There is no Play Store or F-Droid listing. Install the APK directly.

1. Download the APK from the [Releases](../../releases) page, or [build it yourself](#building-from-source)
2. Open it on your phone and allow installs from your browser or file manager when prompted
3. Open **Cover Screen** from your app drawer

The setup screen only exists on the **inner display**. Open the phone to configure it.

> **The app never appears in your home app picker.** `LauncherHomeActivity` deliberately declares no `HOME` and no `LAUNCHER` category. This is not a home screen replacement and it cannot hijack your launcher.

---

## Setup, step by step

Work through these in order. Steps 1 and 2 are the only mandatory ones for the switcher. Everything after that is per-feature.

### Step 1 - Turn on the accessibility service (required for the switcher and auto-rotate)

1. Open **Cover Screen** on the inner display
2. Tap **Open accessibility settings**
3. Go to **Installed apps** > **Cover Screen**
4. Turn it on and accept the prompt
5. Come back to the app. The top line should now read *"Service is on. Cover panel found at 748x720."*

If it says *"Service is on, but the system is only reporting one display"*, fold the phone once and reopen it. The cover panel is not enumerated until it has been used.

**Why an accessibility service?** It is the only public API that can (a) see which app is in front, (b) take a screenshot without a `MediaProjection` prompt every single time, and (c) place a window on a secondary display without the overlay permission. There is no non-accessibility route to any of those.

### Step 2 - Check the panel was found

The **Displays** box on the setup screen prints exactly what the system reports:

```
id 0  1080x2640  nav 0px    cutout l0 t0 r0 b0     Built-in Screen
id 1  748x720    nav 72px   cutout l0 t0 r0 b0     Cover Screen  <- cover
```

The line tagged `<- cover` is the panel the app will use. It is chosen as *the smallest valid non-default display* - no hardcoded display IDs and no device table, so it works on hardware that was never specifically supported.

### Step 3 - Test it

Tap **Show the switcher now**. The switcher should appear on the cover panel. Then close the phone and try the real gesture.

### Step 4 - Auto-rotate (optional)

Auto-rotate needs the overlay permission, and there is genuinely no way around it. A window can only influence display rotation if it is a real `TYPE_APPLICATION_OVERLAY` covering the full screen.

1. Tap **Allow display over other apps**
2. Grant it, come back
3. Tap **Turn on cover auto rotate**
4. Close the phone and turn it sideways

If nothing happens at all, tap **Rotate not responding, force it**. Strict mode stops asking the system politely and names the direction itself. It is blunter but it works on builds that refuse a sensor request from an overlay.

### Step 5 - The cover screen widget (optional, Samsung-specific)

This is the fiddliest part, and it is entirely Samsung's doing. Samsung keeps a private allowlist of which apps may draw widgets on the cover screen.

1. Install **Galaxy Store**
2. Install **Good Lock**
3. In Good Lock, open the **Life Up** tab
4. Install **MultiStar**
5. Open MultiStar > **I love Galaxy Foldable**
6. Turn on **Launcher Widget**
7. On the cover screen, swipe to the widget carousel, tap **+**, and add **Cover Screen**

**If the widget does not appear in the list**, your package is blocked in Samsung's allowlist. You can check and fix it over adb:

```sh
# read the current list
adb shell settings get secure multistar_cover_widget_backup_list

# a trailing ,0 means blocked. ,1 means allowed.
# rewrite the entry for this package as ,1 and force-stop the launcher
adb shell am force-stop com.sec.android.app.launcher
```

The launcher must be force-stopped for the change to take.

### Step 6 - Launcher permissions (optional, per-feature)

Open **Launcher settings** from the main screen, scroll to **Permissions**. Each row shows Granted or Not granted and takes you straight to the right system page.

| Permission | What it unlocks | Needed? |
|---|---|---|
| **Notification access** | Notification dots on app icons and the badge count | Only for badges |
| **Display over other apps** | Auto-rotate, and Samsung's cover output gate | Only for auto-rotate |
| **Ignore battery optimisation** | Stops the system killing the service in the background | Recommended |
| **Usage access** | Accurate Recent and Most used sorting across the whole device | Only for those sorts |

**Usage access has a shortcut.** Normally Android makes you hunt for the app in a long system list. If Shizuku is connected, tapping that row sets the app op directly and you are done. The code never assumes it worked - it verifies, and silently falls back to opening Settings if it did not.

### Step 7 - Shizuku (fully optional)

See [Shizuku: what changes](#shizuku-what-changes) for what you get. If you want it:

1. Install [Shizuku](https://shizuku.rikka.app)
2. Start it via wireless debugging or adb, following Shizuku's own guide
3. Back in Cover Screen, tap the Shizuku button and grant the permission

The button tells you which state you are in: **Get Shizuku**, **Start Shizuku**, **Grant Shizuku**, **Update Shizuku**, or **Shizuku connected**.

> Shizuku stops at every reboot unless your phone is rooted. That is Shizuku's design, not a bug here. The app watches the binder and repaints the moment it comes back.

---

## Using it

### The nav bar gesture

This is the part people get wrong first, so it is worth stating plainly.

| What you do | What happens |
|---|---|
| Touch the nav bar, swipe up, let go straight away | **Home.** Normal system gesture, untouched |
| Touch the nav bar, swipe up, **hold** ~half a second | **Recents opens** |
| Swipe sideways | Nothing. The gesture is released immediately |
| Tap the nav bar | **Back / home / recents**, forwarded to the real button underneath |

There is deliberately **no velocity trigger**. An earlier build opened recents on a fast upward swipe, which was a mistake: a fast upward swipe *is* the home gesture, so it stole home constantly. The only thing that separates recents from home is the hold.

The dwell is 500ms, from `T7` in `Native.kt`. If that feels long, lower it:

```kotlin
// Native.kt
private const val T7 = 500f   // try 300f-350f
```

### In the switcher

- **Tap a card** - go back to that app
- **Flick a card up** - close it
- **Close all** - closes everything except pinned apps
- **Keep open** - pins an app so Close all skips it

### In the launcher

- **Tap** an app to launch it, on the cover panel by default
- **Long press** for favourite / hide / app info
- **Tap the star** to favourite directly
- **Type** in the search box for ranked fuzzy search
- **Tabs** switch between All, Favourites and Recent
- **The mode button** toggles grid and list

### In the widget

The toolbar across the top: home, grid/list, recents, favourites, sort, settings. Tapping **sort** cycles A-Z > Recent > Most used. Tapping the **title** hides the toolbar; the subtitle then reads *"Tap to show toolbar"* and brings it back.

---

## Every setting

All of these live in **Launcher settings**. Defaults in bold.

### Look

| Setting | Range | Default |
|---|---|---|
| Theme | Follow system / Light / Dark | **Follow system** |
| Grid columns | 2-6 | **3** |
| Grid rows | 2-8 | **4** |
| Icon size | 28-80 dp | **48** |
| Label size | 7-18 sp | **11** |
| Show labels | on / off | **on** |
| Widget opacity | 0-255 | **255** |
| Background colour | Black / Grey / Navy / White | **Black** |
| Background image | any image, persisted URI | **None** |

### Behaviour

| Setting | Default | Notes |
|---|---|---|
| Launch apps on the cover screen | **on** | Off sends launches to the inner display |
| Auto rotate | **off** | Needs the overlay permission |
| Haptic feedback | **on** | |
| Sound effects | **off** | |
| Gestures | **on** | Swipe-to-dismiss on the overlay |
| Auto hide overlay | **off** | |
| Auto hide after | 1000-30000 ms | **5000** |
| Overlay position | Top / Center / Bottom | **Center** |
| Start on boot | **on** | |
| Allow from lock screen | **on** | |

### Layout

| Setting | Range | Default |
|---|---|---|
| Show header | on / off | **on** |
| Recent apps shown | 0-20 | **6** |
| Maximum favourites | 1-40 | **12** |

### Apps

| Setting | Options | Default |
|---|---|---|
| Sort by | A to Z / Recent / Most used / Custom order | **A to Z** |
| Default view | Grid / List / Recent / Favourites | **Grid** |
| Add to favourites | opens the picker | - |
| Hide apps | multi-select | none |
| Custom app order | move up / move down | none |
| Reset all settings | confirmation dialog | - |

Settings are stored in a single `SharedPreferences` file, `launcher_settings`. Every value is range-clamped on write, so a bad value cannot get in.

---

## Permissions, and why each one exists

This is an app that reads your screen and lists your apps. You should be suspicious of it. Here is every permission in `AndroidManifest.xml` and exactly what it is for.

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | See the foreground app, screenshot it for the cards, place the touch catcher on the cover panel, and forward nav bar taps. The core of the switcher |
| `SYSTEM_ALERT_WINDOW` | **Auto-rotate only.** A window can only influence rotation if it is a real full-screen `TYPE_APPLICATION_OVERLAY`. The switcher does not use this |
| `QUERY_ALL_PACKAGES` | A launcher has to enumerate every launchable app. There is no narrower way to list an app drawer |
| `PACKAGE_USAGE_STATS` | Accurate Recent and Most used sorting. Optional - the launcher works without it |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Notification dots. Optional |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the overlay window attached while the phone is closed. Declared subtype: *"Keeps the cover screen overlay window attached while the phone is closed."* |
| `VIBRATE` | The haptic tick when the hold registers |
| `EXPAND_STATUS_BAR` | Status bar interaction from the cover panel |
| `POST_NOTIFICATIONS` | The single silent `IMPORTANCE_MIN` notification the foreground service is legally required to show |
| `moe.shizuku.manager.permission.API_V23` | Optional Shizuku integration. Marked optional; absent Shizuku changes nothing |

### What it deliberately does not do

- **No `INTERNET` permission.** The app cannot talk to a network. Not for telemetry, not for crash reporting, not for anything. Check the manifest yourself
- **No `HOME` or `LAUNCHER` category** on the launcher activity, so it can never take over your home screen or show up in the home app picker
- **No analytics SDK, no crash reporter, no ad library.** The full dependency list is nine lines in `app/build.gradle.kts`

---

## Privacy

**Screenshots never leave the device.** They are captured through `AccessibilityService.takeScreenshot`, downscaled to a 480px long edge, held in an 8MB in-memory `LruCache`, and written as lossy WEBP into the app's own private storage. They are never uploaded, and the app has no network permission to upload them with even if it wanted to.

**Nothing is collected.** No analytics, no telemetry, no identifiers, no crash reports.

**What is stored on device**, and nothing else:

| Data | Where |
|---|---|
| Launcher preferences | `SharedPreferences: launcher_settings` |
| Favourites | SQLite `csal_db`, table `favorite_apps` |
| Recents order and pins | `SharedPreferences: recents` |
| Launch counts and timestamps | `SharedPreferences: launcher_usage` |
| Auto-rotate state | `SharedPreferences: rotate` |
| Letter navigation state | `SharedPreferences: launcher_nav` |
| Screenshots | app-private files, WEBP |

Uninstalling removes all of it.

**Packages the switcher always ignores**, so they never end up on a card: this app itself, your default home app, `com.android.systemui`, `com.samsung.android.app.cocktailbarservice`, `com.samsung.android.coverscreen`, `com.sec.android.app.launcher`.

**A note on `allowBackup`.** The manifest currently sets `android:allowBackup="true"` with the template backup rules, which means preferences and the favourites database can be included in a cloud backup. If you would rather they never leave the phone, set it to `false` in `AndroidManifest.xml`.

---

## Shizuku: what changes

Shizuku runs a small server as the adb shell user and proxies binder calls through it, so the app gets what `adb shell` gets. **Everything works without it.** Every entry point in `Privileged.kt` returns `null` or `false` when Shizuku is missing, not started, or not granted, and the caller keeps its old behaviour.

| | Without Shizuku | With Shizuku |
|---|---|---|
| Recents list | Built from foreground events this app saw | The real system task list, the same one the system switcher reads |
| Card images | Screenshots this app took | The system's own task snapshots, already captured and scaled |
| Tapping a card | Restarts the app | Resumes the exact task, exactly where you left it |
| Swiping a card away | Hides the card | Actually removes the task, like the system switcher |
| Usage access | Hunt for the app in a system list | Granted in one tap |
| Launching on the cover panel | Samsung may bounce it to the inner screen | Relayed onto the cover panel reliably |

That last row is worth explaining, because it is the single most useful thing Shizuku buys you here.

Samsung refuses to **place** a brand new activity on the cover display unless the caller is its own cover launcher. That refusal is what produces *"open phone to continue"*. But it does not refuse to **move a task that already exists**. So the app never asks for the placement: it starts the app on the inner display, where nothing objects, then resumes the resulting task onto the cover. The inner panel is off while the phone is folded, so you never see the first step.

The framework is reached by reflection rather than hidden-API stubs, so a signature change on one OEM build degrades that single call instead of failing the whole build. Op codes are looked up by name, never hardcoded, and a failed lookup refuses to guess.

---

## How it works

### Finding the cover panel

No hardcoded display IDs and no device table. `Cover.panel()` asks `DisplayManager` for every valid display, drops the default one, and takes whichever has the smallest area. On a Flip that is always the cover panel.

`DisplayUtils.isCover()` is a second, looser check used by the launcher: not the default display, and either ID 1, or a name containing "cover", or a configuration narrower than 400dp and taller than it is wide.

### Watching the foreground app

`TYPE_WINDOW_STATE_CHANGED` events give the package in front. It goes to the head of a 20-deep list in `SharedPreferences`. A screenshot is taken no more often than every 1200ms, 550ms after the app settles.

### The touch catcher

An invisible `View` is added to the cover panel as a `TYPE_ACCESSIBILITY_OVERLAY`, sized to exactly the nav bar height reported by that panel, lifted 12dp off the bottom edge. Nav bar height is `max(navigationBars, systemGestures, tappableElement)` insets, so it is right in both gesture and three-button mode.

While idle the window carries `FLAG_NOT_TOUCHABLE`, so it costs nothing. It only becomes touchable when a gesture is in progress.

### Nav bar taps still work

When a touch turns out to be a tap rather than a hold, the service walks `windowsOnAllDisplays`, finds the SystemUI window on the cover panel, hit-tests the node under the finger, and fires `ACTION_CLICK` on it. Your real buttons, in your real order.

### Auto-rotate

A full-screen `TYPE_APPLICATION_OVERLAY` with `screenOrientation` set is added to the cover panel. It must genuinely cover the panel - `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` is required, because a window the system letterboxes off the camera bump stops counting as full screen and loses its say over rotation.

The accelerometer is bucketed: 0-45 and 315-360 upright, 46-134, 135-225, 226-314. A change must hold for 220ms. Then `Kick` is flashed onto the panel for 450ms to force the window policy to recompute.

### libspark.so

Window attributes, dwell times and travel distances are read from a native library, `libspark.so`, through `Native.kt`.

**The library is not included in this repository, and the app does not need it.** Every getter has a decoded fallback constant, `NativeRecentsFactory` catches `UnsatisfiedLinkError` at class-init, and `Native.kt` returns the fallback whenever the library is absent. The app builds, installs and runs identically without it. The setup screen tells you which state you are in: *"libspark bound"* or *"libspark not bound"*.

The fallbacks are exact values, not guesses. Each getter in that library is three instructions - `adrp x8`, `ldr` from `.data`, `ret` - so the constants are the literal bytes the native call would have returned.

| | Value | Meaning |
|---|---|---|
| `L1` | 2032 | `TYPE_ACCESSIBILITY_OVERLAY` |
| `L2` | 0x40728 | `NOT_FOCUSABLE / NOT_TOUCH_MODAL / LAYOUT_IN_SCREEN / LAYOUT_NO_LIMITS / FULLSCREEN / WATCH_OUTSIDE_TOUCH` |
| `L3` | -3 | `PixelFormat.TRANSLUCENT` |
| `L4` | 51 | `Gravity.TOP or Gravity.LEFT` |
| `L6` | 748 | Cover panel window height |
| `T1` | 80px | Travel before a pull counts as a pull |
| `T3` | 120px | Travel past which release commits |
| `T7` | **500ms** | **The hold dwell** |
| `T8` | 50px | Sideways travel that cancels |

If you delete `Native.kt`'s fallbacks and the library is missing, nothing works. If you keep them, the library is a pure optimisation.

---

## Project layout

```
app/src/main/java/
  com/tv/coverscreen/
    RecentsEngine.kt      the accessibility service. gesture, catcher, screenshots (1062 lines)
    Switcher.kt           the custom card deck (disabled by default)
    Privileged.kt         every Shizuku-routed capability
    Rotate.kt             cover auto-rotate
    Kick.kt               invisible one-shot orientation-commit activity
    Cover.kt              panel discovery, nav bar height, cutout insets
    Native.kt             libspark.so values with decoded fallbacks
    NativeLayout.kt       per-device, per-rotation LayoutParams chain
    Snapshots.kt          screenshot cache, memory + WEBP on disk
    IconCache.kt          per-profile icon LruCache
    Recents.kt            the 20-deep recents order and pins
    AppUtils.kt           profile-aware enumeration and launching
    TiltStack.kt          3D tilt card layout
    Spring.kt             damped spring on the frame clock
    OverlayCoverScreen.kt foreground service owning the overlay window
    MainActivity.kt       the setup screen (inner display only)

  apps/ijp/coverrecents/
    NativeRecentsFactory.kt   JNI shim. package path is fixed by the binary

  apps/ijp/coverscreen/launcher/
    ui/LauncherHomeActivity.kt      the launcher grid and list
    ui/LauncherSettingsActivity.kt  every setting
    ui/AddToFavoritesCSActivity.kt  favourites picker with drag-to-reorder
    ui/RequestUnlockActivity.kt     keyguard dismissal before a launch
    ui/MirageActivity.kt            external screen permission helper
    data/AppsRepository.kt          app list, sorting, ranked fuzzy search
    data/Settings.kt                every preference, range-clamped
    data/AppDatabase.kt             favourites SQLite
    glance_widget/                  the cover screen widget, 10 files
    LauncherNotificationListener.kt notification badges
```

---

## Building from source

```sh
git clone https://github.com/YOUR-USERNAME/cover-screen.git
cd cover-screen
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

| | |
|---|---|
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5 (wrapper included, checksum-verified) |
| Java | 11 |
| minSdk / targetSdk | 31 / 37 |
| ABI | arm64-v8a only |
| Kotlin style | official |

Dependencies, in full: `androidx.activity-ktx`, `appcompat`, `core-ktx`, `recyclerview`, `material`, `shizuku-api`, `shizuku-provider`, `hiddenapibypass`. Tests: `junit`, `espresso-core`, `androidx.junit`. That is the entire list.

`hiddenapibypass` is pinned as `[4.3,)` on purpose - it has to track new Android releases to keep working.

R8 keep rules live in `app/src/main/keepRules/rules.keep`. They matter: the native library binds 38 methods by name with `RegisterNatives`, against two exact class paths. If R8 renames or drops any of them the whole registration fails and every getter throws. Do not move `apps/ijp/coverrecents/NativeRecentsFactory.kt` or `CoverScreenAppLauncherApp.java` - those package paths are hardcoded inside the binary.

---

## Testing over adb

Turn the service on without touching the UI:

```sh
adb shell settings put secure enabled_accessibility_services \
  com.tv.coverscreen/com.tv.coverscreen.RecentsEngine
adb shell settings put secure accessibility_enabled 1
```

See what displays the system reports:

```sh
adb shell dumpsys display | grep -i "mDisplayId\|uniqueId"
adb shell dumpsys window displays | grep -i "Display\|rotation"
```

Show and hide the switcher by hand:

```sh
adb shell am broadcast -a com.tv.coverscreen.SHOW -p com.tv.coverscreen
adb shell am broadcast -a com.tv.coverscreen.HIDE -p com.tv.coverscreen
```

Dump the accessibility node tree to logcat:

```sh
adb shell am broadcast -a apps.ijp.coverrecents.PRINT_HIERARCHY -p com.tv.coverscreen
```

Overlay service controls:

```sh
adb shell am start-foreground-service -a com.tv.coverscreen.OVERLAY_SHOW -p com.tv.coverscreen
adb shell am startservice -a com.tv.coverscreen.OVERLAY_HIDE -p com.tv.coverscreen
adb shell am startservice -a com.tv.coverscreen.OVERLAY_DUMP -p com.tv.coverscreen
```

Watch it work:

```sh
adb logcat -s RecentsEngine:V Privileged:V NativeLayoutParams:V OverlayCoverScreen:V
```

---

## Troubleshooting

**"Service is on, but the system is only reporting one display"**
Fold the phone once and reopen it. The cover panel is not enumerated until it has been used.

**The gesture does nothing**
Check the catcher is over the right place. `Cover.dump()` on the setup screen prints the nav bar height it measured. If it reads `nav 0px` the panel is not reporting insets and the catcher falls back to a 24dp minimum.

**Recents opens when I meant to go home**
You are holding too long. There is no velocity trigger, only the 500ms dwell. If your natural swipe is slow, lower `T7` in `Native.kt`.

**Home is fine but recents never opens**
The opposite problem - you are letting go before 500ms. Hold a little longer, or lower `T7`.

**A phone case eats the gesture**
`STRIP_LIFT_DP` in `RecentsEngine.kt` is how far up off the bottom edge the catcher sits. It ships at 12dp. Raise it for a thicker case.

**Apps open on the inner screen instead of the cover**
This is Samsung refusing the placement. Install Shizuku - the relay path in `WidgetLaunchActivity` works around it. Also check **Launch apps on the cover screen** is on.

**Auto-rotate does nothing**
Confirm the overlay permission is granted, then turn on strict mode. If it still does nothing, your build is refusing the orientation request entirely.

**The widget is missing from the cover carousel**
See [Step 5](#step-5---the-cover-screen-widget-optional-samsung-specific). It is almost always Samsung's allowlist.

**Recent tab does not match what I have been using**
That is the local-only tier. Grant usage access, or connect Shizuku, and it will read the real device-wide list. The setting screen shows which source answered.

**Everything breaks after a reboot**
Shizuku stops at every reboot unless you are rooted. Restart it and re-grant.

---

## Known limitations

- **Galaxy Z Flip 6 on One UI 8.5 only.** One phone, one firmware version. Every other One UI release, every other Flip generation and every other device is untested
- Razr code paths in `NativeLayout.kt` are **unreachable**, not merely untested - they call into a native library that is not included, so they can never execute. This is not a Razr app
- The cover screen widget depends on Good Lock and MultiStar, which are Samsung-only and region-restricted
- Samsung's cover widget allowlist can silently block the app after an update
- `onUpgrade` in `AppDatabase` drops the favourites table. Database version is still 1, so this has never fired, but a future schema change needs a real migration
- The custom card deck is disabled by default and gets less testing than the native path
- `UsageStatsManager` returns nothing while the device is locked, so the Recent tab falls through to a lower tier on the lock screen
- arm64 only

---

## Provenance and licensing

**Read this before you publish or redistribute.**

Parts of this project were reconstructed by reverse-engineering an existing closed-source cover screen app. Source comments refer to a decompiled original, recovered JNI descriptors, and constants read out of `libspark.so`'s `.data` section at specific offsets.

What that means in practice:

- **No third-party binary is included in this repository.** `libspark.so` and `libsparx.so` are not present, and the app runs without them using decoded fallback constants
- **The Kotlin and Java source here was written for this project.** It reimplements observed behaviour using public Android APIs - `Cover.kt` exists specifically to replace what the original hid inside its native library
- **Redistributing the original `.so` files would be a copyright problem.** Do not commit them. The shipped `.gitignore` blocks `*.so`, `jniLibs/` and `app/libs/` specifically so this cannot happen by accident
- Reverse engineering for interoperability is treated differently in different countries. If you plan to publish this publicly, that is worth understanding first

### License

Released under the **MIT License** - see [LICENSE](LICENSE).

The grant covers **the source code in this repository only**. It does not, and legally cannot, cover:

- `libspark.so` and `libsparx.so`, which are third-party binaries not distributed here
- Samsung, Google or other third-party trademarks, package names, SDKs or system interfaces referenced by this code

Constants in `Native.kt` and `NativeLayout.kt` were derived by inspecting a third-party binary and are published as factual interoperability data. If you redistribute this project, satisfy yourself that doing so is lawful where you are.

---

## Contributing

Issues and pull requests are welcome. Useful things to include in a bug report:

- Phone model and One UI / Android version
- The **Displays** box contents from the setup screen
- Whether Shizuku is connected, and as shell or root
- Relevant `adb logcat -s RecentsEngine:V` output

---

## Credits

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps, for the privileged API
- [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) by LSPosed, for lifting non-SDK restrictions
