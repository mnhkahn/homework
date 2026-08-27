package com.homeworkbuddy

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.provider.MediaStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class HomeworkDeviceAdminReceiver : DeviceAdminReceiver()

enum class KioskMode { STUDY, NORMAL, PAUSED }

data class LaunchableApp(val packageName: String, val label: String)

/** A foreground app as seen by the study-time tracker. */
data class ForegroundApp(val packageName: String, val launchable: Boolean, val allowed: Boolean)

class KioskPolicy(private val context: Context) {
    private val prefs = context.getSharedPreferences("kiosk_policy", Context.MODE_PRIVATE)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, HomeworkDeviceAdminReceiver::class.java)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)
    val startMinutes: Int get() = prefs.getInt("start_minutes", 17 * 60)
    val endMinutes: Int get() = prefs.getInt("end_minutes", 21 * 60 + 30)
    val pausedUntil: Long get() = prefs.getLong("paused_until", 0L)
    val studyPackages: Set<String> get() = prefs.getStringSet("study_packages", prefs.getStringSet("approved_packages", emptySet()))?.toSet().orEmpty()
    private val temporaryPackages: Set<String> get() = prefs.getStringSet("temporary_packages", emptySet())?.toSet().orEmpty()
    val isExternalForegroundAllowed: Boolean get() = prefs.getBoolean("external_foreground_allowed", false)

    fun mode(now: LocalDateTime = LocalDateTime.now()): KioskMode {
        if (System.currentTimeMillis() < pausedUntil) return KioskMode.PAUSED
        val minute = now.hour * 60 + now.minute
        return if (minute in startMinutes until endMinutes) KioskMode.STUDY else KioskMode.NORMAL
    }

    fun saveSchedule(start: Int, end: Int) {
        require(start in 0 until 24 * 60 && end in 0 until 24 * 60 && start < end)
        prefs.edit().putInt("start_minutes", start).putInt("end_minutes", end).apply()
        scheduleNextTransitions()
    }

    fun setStudyAllowed(packageName: String, allowed: Boolean) {
        val updated = studyPackages.toMutableSet().apply { if (allowed) add(packageName) else remove(packageName) }
        prefs.edit().putStringSet("study_packages", updated).remove("approved_packages").apply()
        if (isDeviceOwner && mode() == KioskMode.STUDY) applyAllowlist(KioskMode.STUDY)
    }

    fun launchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                LaunchableApp(packageName, info.loadLabel(context.packageManager).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Usage access powers the study-time block screen; the parent grants it once in system settings. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The current foreground app. Our own package is included so returning to
     * 作业小伙伴 counts as an app switch; system components (launcher, dialogs)
     * stay excluded because they are not launchable.
     */
    fun foregroundApp(): ForegroundApp? {
        if (!hasUsageAccess()) return null
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - FOREGROUND_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND is the only foreground event on API < 29
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foreground = event.packageName
            }
        }
        val launchablePackages = launchablePackageNames()
        // A foreground event is emitted when an activity is opened, not every
        // second while it remains on screen. Fall back to UsageStats so a
        // child lingering in the same non-allowed app is still measured.
        val candidate = foreground?.takeIf {
            it == context.packageName || it in launchablePackages
        } ?: context.getSystemService(UsageStatsManager::class.java)
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - USAGE_FALLBACK_WINDOW_MS, now)
            .asSequence()
            .filter { it.packageName == context.packageName || it.packageName in launchablePackages }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
            ?: return null
        val launchable = candidate == context.packageName || candidate in launchablePackages
        val allowed = candidate == context.packageName || candidate in studyPackages || candidate in temporaryPackages
        return ForegroundApp(candidate, launchable = launchable, allowed = allowed)
    }

    /** The settings screen changes the allowlist rarely; cache the expensive package query. */
    private fun launchablePackageNames(): Set<String> {
        val now = System.currentTimeMillis()
        launchableCache?.takeIf { now - it.first < LAUNCHABLE_CACHE_MS }?.let { return it.second }
        val names = launchableApps().mapTo(HashSet()) { it.packageName }
        launchableCache = now to names
        return names
    }

    /** Shows the full-screen study-time notice over a blocked app. */
    fun openStudyBlock(blockedPackage: String) {
        if (mode() != KioskMode.STUDY) return
        val launchIntent = Intent(context, StudyBlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(StudyBlockActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        launchFromBackground(launchIntent, REQUEST_STUDY_BLOCK)
    }

    fun applyForCurrentTime(activity: Activity? = null, navigate: Boolean = false) {
        if (!isDeviceOwner) return
        val current = mode()
        if (current != KioskMode.STUDY) {
            StudySessionService.stop(context)
            releaseLockTask(activity)
            if (navigate) openSystemHome()
            return
        }
        configureAsHome()
        applyAllowlist(current)
        StudySessionService.start(context)
        activity?.let { startLockTaskSafely(it) }
        if (navigate) openMode(current)
    }

    fun enterStudy(activity: Activity? = null) {
        if (!isDeviceOwner) return
        prefs.edit().remove("paused_until").apply()
        configureAsHome()
        applyAllowlist(KioskMode.STUDY)
        StudySessionService.start(context)
        if (activity is MainActivity) startLockTaskSafely(activity) else openMode(KioskMode.STUDY)
    }

    fun exitStudyMode(activity: Activity? = null) {
        if (!isDeviceOwner) return
        prefs.edit().remove("paused_until").apply()
        StudySessionService.stop(context)
        releaseLockTask(activity)
        openSystemHome()
    }

    fun openStudyLauncher() {
        if (!isDeviceOwner || mode() != KioskMode.STUDY) return
        configureAsHome()
        applyAllowlist(KioskMode.STUDY)
        context.startActivity(Intent(context, ChildLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    /**
     * The system camera is a separate app.  During Lock Task it must be
     * explicitly allowlisted before ACTION_IMAGE_CAPTURE is launched, or MIUI
     * opens a black frame and immediately returns to this activity.
     */
    fun allowCameraForCapture(): Boolean {
        if (!isDeviceOwner || mode() != KioskMode.STUDY) return false
        val packageName = context.packageManager.resolveActivity(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName ?: return false
        prefs.edit().putBoolean("external_foreground_allowed", true).apply()
        allowTemporarily(packageName)
        return true
    }

    fun revokeCameraCaptureAccess() {
        if (!isDeviceOwner) return
        val packageName = context.packageManager.resolveActivity(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName ?: return
        val updated = temporaryPackages - packageName
        prefs.edit().putStringSet("temporary_packages", updated).putBoolean("external_foreground_allowed", false).apply()
        if (mode() == KioskMode.STUDY) applyAllowlist(KioskMode.STUDY)
    }

    /** Reassert the selected app's allowlist entry immediately before launch. */
    fun prepareStudyAppLaunch(packageName: String) {
        if (isDeviceOwner && mode() == KioskMode.STUDY && packageName in studyPackages) {
            prefs.edit().putBoolean("external_foreground_allowed", true).apply()
            applyAllowlist(KioskMode.STUDY)
        }
    }

    fun markManagedActivityForeground() {
        if (isExternalForegroundAllowed) {
            prefs.edit().putBoolean("external_foreground_allowed", false).apply()
        }
    }

    fun restoreManagedTask() {
        if (!isDeviceOwner || mode() != KioskMode.STUDY) return
        configureAsHome()
        applyAllowlist(KioskMode.STUDY)
        val launchIntent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        launchFromBackground(launchIntent, REQUEST_RESTORE_STUDY)
    }

    /**
     * HyperOS ignores startActivity() and AppTask.moveToFront() from a
     * background service. A device-owner foreground service may launch through
     * an explicitly BAL-enabled PendingIntent.
     */
    private fun launchFromBackground(launchIntent: Intent, requestCode: Int) {
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle(),
        )
        runCatching {
            pending.send(context, 0, null, null, null, null, options.toBundle())
        }.onFailure {
            context.startActivity(launchIntent)
        }
    }

    fun pause(minutes: Int, activity: Activity? = null) {
        prefs.edit().putLong("paused_until", System.currentTimeMillis() + minutes * 60_000L).apply()
        StudySessionService.stop(context)
        releaseLockTask(activity)
        scheduleNextTransitions()
    }

    fun resume(activity: Activity? = null) {
        prefs.edit().remove("paused_until").apply()
        applyForCurrentTime(activity, navigate = true)
    }

    fun scheduleNextTransitions() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        scheduleAlarm(alarm, REQUEST_STUDY, nextOccurrence(startMinutes), ACTION_STUDY)
        scheduleAlarm(alarm, REQUEST_NORMAL, nextOccurrence(endMinutes), ACTION_NORMAL)
        if (pausedUntil > System.currentTimeMillis()) scheduleAlarm(alarm, REQUEST_RESUME, pausedUntil, ACTION_REEVALUATE)
    }

    private fun scheduleAlarm(alarm: AlarmManager, requestCode: Int, at: Long, action: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, KioskScheduleReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .recoverCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
    }

    private fun nextOccurrence(minutes: Int): Long {
        val now = LocalDateTime.now()
        var target = LocalDate.now().atTime(LocalTime.of(minutes / 60, minutes % 60))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun applyAllowlist(mode: KioskMode) {
        val packages = when (mode) {
            KioskMode.STUDY -> setOf(context.packageName) + studyPackages + temporaryPackages
            KioskMode.NORMAL -> emptySet()
            KioskMode.PAUSED -> emptySet()
        }
        runCatching { dpm.setLockTaskPackages(admin, packages.toTypedArray()) }
        if (mode == KioskMode.STUDY) {
            runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS) }
            runCatching { dpm.setStatusBarDisabled(admin, true) }
        } else {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS) }
            runCatching { dpm.setStatusBarDisabled(admin, false) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        }
    }

    private fun allowTemporarily(packageName: String) {
        prefs.edit().putStringSet("temporary_packages", temporaryPackages + packageName).apply()
        applyAllowlist(KioskMode.STUDY)
    }

    private fun configureAsHome() {
        setStudyLauncherEnabled(true)
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        runCatching { dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, ChildLauncherActivity::class.java)) }
    }

    private fun startLockTaskSafely(activity: Activity) {
        if (!dpm.isLockTaskPermitted(context.packageName)) return
        val state = context.getSystemService(ActivityManager::class.java).lockTaskModeState
        if (state == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { activity.startLockTask() }
    }

    private fun releaseLockTask(activity: Activity?) {
        if (!isDeviceOwner) return
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS) }
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        prefs.edit().remove("temporary_packages").remove("external_foreground_allowed").apply()
        // Lock-task features are policy state on MIUI too. Explicitly restore the
        // normal system navigation controls when study time ends; merely removing
        // the package allowlist can leave the bottom navigation area hidden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val normalFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            runCatching { dpm.setLockTaskFeatures(admin, normalFeatures) }
        }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, context.packageName) }
        if (activity != null) runCatching { activity.stopLockTask() }
        setStudyLauncherEnabled(false)
    }

    private fun setStudyLauncherEnabled(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(ComponentName(context, ChildLauncherActivity::class.java), state, PackageManager.DONT_KILL_APP)
    }

    private fun openMode(mode: KioskMode) {
        if (mode == KioskMode.STUDY) context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        else openSystemHome()
    }

    private fun openSystemHome() {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val ACTION_STUDY = "com.homeworkbuddy.action.ENTER_STUDY"
        const val ACTION_NORMAL = "com.homeworkbuddy.action.ENTER_NORMAL"
        const val ACTION_REEVALUATE = "com.homeworkbuddy.action.REEVALUATE"
        /** Sent after an alarm transition so a foreground Activity can stop Lock Task. */
        const val ACTION_MODE_CHANGED = "com.homeworkbuddy.action.MODE_CHANGED"
        private const val REQUEST_STUDY = 1700
        private const val REQUEST_NORMAL = 2130
        private const val REQUEST_RESUME = 1515
        private const val REQUEST_RESTORE_STUDY = 7111
        private const val REQUEST_STUDY_BLOCK = 7112
        private const val FOREGROUND_WINDOW_MS = 3_000L
        private const val USAGE_FALLBACK_WINDOW_MS = 24 * 60 * 60 * 1_000L
        private const val LAUNCHABLE_CACHE_MS = 60_000L
        private var launchableCache: Pair<Long, Set<String>>? = null
    }
}

class KioskScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homework:kiosk-transition")
        lock.acquire(30_000)
        try {
            val policy = KioskPolicy(context)
            when (intent.action) {
                KioskPolicy.ACTION_STUDY -> policy.enterStudy()
                KioskPolicy.ACTION_NORMAL -> policy.exitStudyMode()
                else -> policy.applyForCurrentTime(navigate = true)
            }
            policy.scheduleNextTransitions()
            context.sendBroadcast(Intent(KioskPolicy.ACTION_MODE_CHANGED).setPackage(context.packageName))
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}

class KioskSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KioskPolicy(context).apply {
            scheduleNextTransitions()
            if (isDeviceOwner) applyForCurrentTime(navigate = true)
        }
        HomeworkReminderScheduler.rescheduleFromPrefs(context)
        // A bound device should be reachable again after a reboot or an APK
        // update, not only after somebody opens the homework screen.
        if (XiaoliDeviceStore.config(context) != null) {
            runCatching { XiaoliConnectionService.connect(context) }
        }
    }
}

/** Opens the homework home after the child unlocks the tablet during study time. */
class KioskUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val policy = KioskPolicy(context)
        if (policy.isDeviceOwner && policy.mode() == KioskMode.STUDY) {
            policy.applyForCurrentTime(navigate = true)
        }
    }
}
