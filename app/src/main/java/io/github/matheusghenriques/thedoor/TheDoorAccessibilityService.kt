package io.github.matheusghenriques.thedoor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.matheusghenriques.thedoor.data.AppConfig
import io.github.matheusghenriques.thedoor.data.AppLimitRepository
import io.github.matheusghenriques.thedoor.data.AppPreferences
import io.github.matheusghenriques.thedoor.data.CurrentUsageProvider
import io.github.matheusghenriques.thedoor.data.OpenCountProvider
import io.github.matheusghenriques.thedoor.data.PackageConstants
import io.github.matheusghenriques.thedoor.data.ProtectionConfig
import io.github.matheusghenriques.thedoor.data.ProtectionOverlayManager
import io.github.matheusghenriques.thedoor.data.ProtectionShield
import io.github.matheusghenriques.thedoor.data.RedirectConfig
import io.github.matheusghenriques.thedoor.data.RedirectType
import io.github.matheusghenriques.thedoor.data.TimeSchedule
import io.github.matheusghenriques.thedoor.data.UsageTracker
import io.github.matheusghenriques.thedoor.data.WindowSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("AccessibilityPolicy")
class TheDoorAccessibilityService : AccessibilityService() {

    companion object {
        const val EXTRA_REDIRECT_PHRASE = "redirect_phrase"
        private const val NOTIFICATION_CHANNEL_ID = "limit_warnings"
        private const val NOTIFICATION_CHANNEL_NAME = "Limit Warnings"
        private const val NOTIFICATION_ID_BASE = 1000
        private val BLOCKED_ACCESSIBILITY =
            listOf("accessibility", "acessibilidade", "aplicativos instalados", "installed apps")
        private val BLOCKED_DEV_OPTIONS =
            listOf("developer options", "opções do desenvolvedor", "opções de desenvolvedor")
        private val BLOCKED_VPN =
            listOf("vpn", "more connection settings", "mais configurações de conexão")
        private val BLOCKED_PRIVATE_DNS = listOf("private dns", "dns privado")
        private val BLOCKED_THE_DOOR = listOf("the door engine", "the door")
        private val BLOCKED_RETHINK = listOf("rethink")
        private val BLOCKED_LANGUAGE = listOf("language", "idioma")
        private val BLOCKED_ADMIN = listOf("admin", "administrador")
        private val BLOCKED_ADD_APPS_SECURE_FOLDER =
            listOf("AddAppsActivity", "add apps", "adicionar aplicativos")
        private val BLOCKED_UNKNOWN_INSTALL =
            listOf("install unknown apps", "instalar apps desconhecidos")
        private val BLOCKED_AUTO_BLOCKER =
            listOf("turn off auto blocker", "desativar o bloqueador automático")
        private val CONTENT_BLOCKED_ACCESSIBILITY =
            listOf("aplicativos instalados", "installed apps")
        private const val WINDOW_SCAN_THROTTLE_MS = 150L
        private const val OVERLAY_POLL_MS = 1_000L
        private const val CONTENT_SCAN_MAX_NODES = 400

    }

    private val userBlockedApps = ConcurrentHashMap<String, Boolean>()
    private val appLimitsMs = ConcurrentHashMap<String, Long>()
    private val scheduledApps = ConcurrentHashMap<String, List<TimeSchedule>>()
    private val maxOpensMap = ConcurrentHashMap<String, Int>()
    private val openCounts = ConcurrentHashMap<String, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var tracker: UsageTracker
    private lateinit var repo: AppLimitRepository
    private lateinit var shield: ProtectionShield
    private lateinit var overlayManager: ProtectionOverlayManager

    private var lastActiveApp: String? = null
    private var trackedNotificationDate: String = ""
    private val notifiedThresholds = ConcurrentHashMap<String, Int>()
    private val appNameCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedRedirectConfig = RedirectConfig()

    @Volatile
    private var cachedConfigs: Map<String, AppConfig> = emptyMap()

    @Volatile
    private var setupComplete = false
    private var trackedOpensDate: String = ""

    private var lastTimeChangeCheck: Long = 0L
    private var lastWindowScanAt: Long = 0L
    private var redirectPending = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    tracker.onScreenOff()
                    // Hide the containment overlay so the keyguard stays usable; the
                    // incident state survives and is re-asserted after unlock.
                    overlayManager.hide()
                    stopOverlayPoll()
                    shield.onScreenOff()
                }

                Intent.ACTION_USER_PRESENT -> {
                    if (setupComplete) {
                        // Re-assert containment as soon as the keyguard lifts.
                        // Re-assertions must not record escalation: they restore a
                        // known incident, they are not new attack detections.
                        handler.post { assertProtectionOverlay(force = true, recordEscalation = false) }
                    }
                }
            }
        }
    }

    private val protectionConfig = AtomicReference(ProtectionConfig())
    private val appPrefs by lazy { AppPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        trackedNotificationDate = currentDateString()
        repo = AppLimitRepository(this)
        tracker = UsageTracker(this)
        shield = ProtectionShield({ SystemClock.uptimeMillis() }, packageName)
        overlayManager = ProtectionOverlayManager(this)

        scope.launch { watchConfigChanges() }
        scope.launch { watchRedirectConfig() }
        scope.launch {
            appPrefs.onboardingComplete.collect { complete ->
                setupComplete = complete
            }
        }
        scope.launch {
            appPrefs.protectionConfig.collect { config ->
                protectionConfig.set(config)
            }
        }

        handler.postDelayed(gradualReductionCheck, 6 * 60 * 60 * 1000L)
        handler.postDelayed(limitCheckRunnable, 5_000L)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Belt-and-suspenders: the XML config already declares these flags; re-apply
        // at runtime (AppLock pattern) in case the declarative load is degraded.
        try {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (e: Exception) {
            Log.d("DoorBug", "onServiceConnected flags failed: ${e::class.java.simpleName}")
        }
        // Covers the rebind-after-crash case with a protected window already open.
        if (setupComplete) {
            handler.post { assertProtectionOverlay(force = true, recordEscalation = false) }
        }
    }

    private suspend fun watchConfigChanges() {
        repo.allConfigs.collect { configs ->
            cachedConfigs = configs
            userBlockedApps.clear()
            userBlockedApps.putAll(configs.filter { it.value.blocked }.keys.associateWith { true })
            appLimitsMs.clear()
            recalcAppLimitsMs()
            scheduledApps.clear()
            scheduledApps.putAll(configs.mapValues { (_, cfg) -> cfg.schedules }
                .filter { it.value.isNotEmpty() })
            maxOpensMap.clear()
            maxOpensMap.putAll(configs.mapValues { it.value.maxOpensPerDay }
                .filter { it.value != null }.mapValues { it.value!! })
            val today = currentDateString()
            if (trackedOpensDate != today) {
                openCounts.clear()
                trackedOpensDate = today
            }
            OpenCountProvider.openCounts.value = openCounts.toMap()
        }
    }

    private fun recalcAppLimitsMs() {
        val todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        appLimitsMs.clear()
        cachedConfigs.forEach { (pkg, cfg) ->
            val limitMin =
                cfg.perDayLimits[todayDow] ?: cfg.perDayLimits.values.maxOrNull() ?: return@forEach
            appLimitsMs[pkg] = limitMin * 60 * 1000L
        }
    }

    private suspend fun watchRedirectConfig() {
        repo.redirectConfig.collect { config ->
            cachedRedirectConfig = config
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(gradualReductionCheck)
        handler.removeCallbacks(limitCheckRunnable)
        stopOverlayPoll()
        overlayManager.hide()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
    }

    private val gradualReductionCheck = object : Runnable {
        override fun run() {
            scope.launch { repo.applyGradualReductions() }
            handler.postDelayed(this, 6 * 60 * 60 * 1000L)
        }
    }

    private val limitCheckRunnable = object : Runnable {
        override fun run() {
            val tcc = TimeChangeReceiver.lastTimeChangeAt
            if (tcc > lastTimeChangeCheck) {
                lastTimeChangeCheck = tcc
                recalcAppLimitsMs()
                openCounts.clear()
                OpenCountProvider.openCounts.value = openCounts.toMap()
                notifiedThresholds.clear()
                trackedNotificationDate = currentDateString()
            }

            val today = currentDateString()
            if (trackedNotificationDate != today) {
                notifiedThresholds.clear()
                trackedNotificationDate = today
                recalcAppLimitsMs()
                openCounts.clear()
                OpenCountProvider.openCounts.value = openCounts.toMap()
                tracker.onDayReset()
            }
            val nextDelay = lastActiveApp?.let { app ->
                appLimitsMs[app]?.let { limitMs ->
                    val usage = tracker.getCurrentUsage(app)
                    val remaining = limitMs - usage
                    if (remaining <= 0) {
                        triggerBlock()
                    } else {
                        val thresholds = listOf(900_000, 300_000, 60_000)
                        for (threshold in thresholds) {
                            if (threshold in remaining..<limitMs) {
                                val notified = notifiedThresholds[app] ?: Int.MAX_VALUE
                                if (threshold < notified) {
                                    showLimitWarning(app, threshold)
                                    notifiedThresholds[app] = threshold
                                }
                            }
                        }
                    }
                    val delay = when {
                        remaining > 915_000L -> remaining - 915_000L
                        remaining > 900_000L -> 20_000L
                        remaining > 315_000L -> remaining - 315_000L
                        remaining > 300_000L -> 20_000L
                        remaining > 75_000L -> remaining - 75_000L
                        remaining > 60_000L -> 20_000L
                        remaining > 20_000L -> 40_000L.coerceAtMost(remaining - 5_000L)
                        else -> remaining + 5_000L
                    }
                    delay
                }
            } ?: 300_000L
            if (appLimitsMs.isNotEmpty()) {
                CurrentUsageProvider.currentUsage.value = tracker.getAllUsage(appLimitsMs.keys)
            }
            tracker.save()
            handler.postDelayed(this, nextDelay)
        }
    }

    private fun triggerBlock() {
        performGlobalAction(GLOBAL_ACTION_BACK)

        // Collapse stacked redirects: under event spam each triggerBlock used to post
        // its own delayed redirect, causing N startActivity calls. The pending
        // runnable covers the state; skipping never suppresses the BACK above.
        if (redirectPending) return
        redirectPending = true

        handler.postDelayed({
            redirectPending = false
            val config = cachedRedirectConfig
            var navigated = false

            if (config.type != RedirectType.NONE) {
                val targetPkg = when (config.type) {
                    RedirectType.APP -> config.appPackage
                    RedirectType.THE_DOOR -> packageName
                    RedirectType.NONE -> ""
                }

                if (targetPkg.isNotBlank() && !userBlockedApps.containsKey(targetPkg) && !appLimitsMs.containsKey(
                        targetPkg
                    ) && !isScheduleActive(targetPkg)
                ) {
                    val intent = if (config.type == RedirectType.APP) {
                        packageManager.getLaunchIntentForPackage(config.appPackage)
                    } else {
                        packageManager.getLaunchIntentForPackage(packageName)
                    }
                    if (intent != null) {
                        if (config.type == RedirectType.THE_DOOR && config.theDoorPhrase.isNotBlank()) {
                            intent.putExtra(EXTRA_REDIRECT_PHRASE, config.theDoorPhrase)
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                        navigated = true
                    }
                }
            }

            // Never leave the user parked on a blocked screen: if no redirect fired
            // (type NONE or invalid/blocked target), fall back to HOME.
            if (!navigated) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            if (config.toastEnabled && config.toastMessage.isNotBlank()) {
                Toast.makeText(applicationContext, config.toastMessage, Toast.LENGTH_SHORT).show()
            }
        }, 30L)
    }

    /**
     * Protection-context block: everything triggerBlock does, PLUS the containment
     * overlay and the dedicated escalation counter (separate from app-limit
     * enforcement, never shared, never reset by lockNow).
     *
     * Escalation only counts triggers that arrive while the overlay is NOT yet
     * shown — i.e., real exposure moments (transitions, post-unlock races).
     * Repeated detections while already contained just extend the incident.
     */
    private fun triggerProtectionBlock(triggerPackage: String) {
        val overlayWasShown = overlayManager.isShown
        shield.startIncident(triggerPackage)
        if (overlayWasShown) return
        if (shield.recordEscalation()) {
            Log.d("DoorBug", "ProtectionShield escalation -> lockScreenViaAdmin")
            lockScreenViaAdmin()
        }
        // While the keyguard is up it already covers everything; showing the overlay
        // there would block the owner out of the lock screen. USER_PRESENT re-asserts.
        if (isKeyguardShowing()) return
        overlayManager.show(cachedRedirectConfig.theDoorPhrase)
        startOverlayPoll()
        triggerBlock()
    }

    /**
     * Evaluates the window set and applies the containment overlay decision.
     * Throttled for event-driven calls; pass force=true for poll/re-assert paths.
     *
     * Removal is state-based, never event-package-based: the overlay only lifts
     * after the shield confirms continuous absence of protected windows.
     */
    private fun assertProtectionOverlay(force: Boolean = false, recordEscalation: Boolean = true) {
        // Never evaluate containment while the keyguard is up: the lock screen must
        // stay usable, and absence/presence decisions made behind it are unreliable.
        if (isKeyguardShowing()) return

        val t = SystemClock.uptimeMillis()
        if (!force && t - lastWindowScanAt < WINDOW_SCAN_THROTTLE_MS) return
        lastWindowScanAt = t

        val windowList: List<AccessibilityWindowInfo> = try {
            windows.toList()
        } catch (_: Exception) {
            emptyList()
        }

        val contentTrigger = scanWindowsForProtectedContent(windowList)
        if (contentTrigger != null) {
            if (recordEscalation) {
                triggerProtectionBlock(contentTrigger)
            } else {
                // Re-assertion of a known incident (USER_PRESENT / service rebind):
                // containment and navigation, but no escalation and no lock.
                val wasShown = overlayManager.isShown
                shield.startIncident(contentTrigger)
                if (!wasShown) {
                    overlayManager.show(cachedRedirectConfig.theDoorPhrase)
                    startOverlayPoll()
                    triggerBlock()
                }
            }
            return
        }

        val displayArea = currentDisplayArea()
        val snapshots = windowList.map { w ->
            val pkg = try {
                w.root?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
            val rect = Rect()
            try {
                w.getBoundsInScreen(rect)
            } catch (_: Exception) {
            }
            WindowSnapshot(pkg, w.type, rect.width().toLong() * rect.height().toLong(), displayArea)
        }

        val wasShown = overlayManager.isShown
        // Preventive floating-Settings detection is part of the accessibility
        // protection: if the user did not enable that toggle, opening Settings
        // in popup/split-screen must not contain anything.
        val floatingDetectionEnabled = protectionConfig.get().blockAccessibilitySettings
        if (shield.onWindows(snapshots, floatingDetectionEnabled)) {
            if (!wasShown) {
                overlayManager.show(cachedRedirectConfig.theDoorPhrase)
                startOverlayPoll()
                // Navigate away (redirect/HOME): steals the foreground from the
                // protected window and gives the user a sane landing screen.
                triggerBlock()
            }
        } else {
            overlayManager.hide()
            stopOverlayPoll()
        }
    }

    private fun isKeyguardShowing(): Boolean = try {
        val km = getSystemService(KeyguardManager::class.java)
        km != null && km.isKeyguardLocked
    } catch (_: Exception) {
        false
    }

    /**
     * Content-based detection (phase 2): walks the node tree of visible Settings
     * windows looking for protection keywords. Closes the event.text dependency —
     * titles can arrive empty, but rendered content does not lie.
     */
    private fun scanWindowsForProtectedContent(windows: List<AccessibilityWindowInfo>): String? {
        val cfg = protectionConfig.get()
        val keywords = buildContentKeywords(cfg)
        if (keywords.isEmpty()) return null
        for (w in windows) {
            val root = try {
                w.root
            } catch (_: Exception) {
                null
            } ?: continue
            val pkg = try {
                root.packageName?.toString()
            } catch (_: Exception) {
                null
            } ?: continue
            if (pkg != PackageConstants.SETTINGS) continue
            val content = collectNodeText(root)
            if (content.isEmpty()) continue
            val lower = content.lowercase()
            if (keywords.any { lower.contains(it) }) {
                return PackageConstants.SETTINGS
            }
        }
        return null
    }

    private fun collectNodeText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < CONTENT_SCAN_MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                node.text?.let { sb.append(it).append(' ') }
                node.contentDescription?.let { sb.append(it).append(' ') }
            } catch (_: Exception) {
            }
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (_: Exception) {
                }
            }
        }
        return sb.toString()
    }

    private fun buildSettingsKeywords(cfg: ProtectionConfig): List<String> = buildList {
        if (cfg.blockAccessibilitySettings) {
            addAll(BLOCKED_ACCESSIBILITY)
            // The service's own detail screen is gated by the accessibility toggle too,
            // not only by the uninstall toggle.
            addAll(BLOCKED_THE_DOOR)
        }
        if (cfg.blockDeveloperOptions) addAll(BLOCKED_DEV_OPTIONS)
        if (cfg.blockVpn) addAll(BLOCKED_VPN)
        if (cfg.blockPrivateDns) addAll(BLOCKED_PRIVATE_DNS)
        if (cfg.blockUninstallTheDoor) addAll(BLOCKED_THE_DOOR)
        if (cfg.blockUninstallRethink) addAll(BLOCKED_RETHINK)
        if (cfg.blockLanguageChanges) addAll(BLOCKED_LANGUAGE)
        if (cfg.blockAppInfo) addAll(BLOCKED_ADMIN)
        if (cfg.blockInstallUnknownApps) addAll(BLOCKED_UNKNOWN_INSTALL)
    }

    /**
     * Keywords for the CONTENT scan. Deliberately restricted to high-specificity
     * strings that identify the crown-jewel screens: generic terms like
     * "accessibility" or "language" also match the Settings home screen (which
     * renders them as menu items) and would lock the user out of the entire
     * Settings app. Generic terms stay in the event-based branch, which matches
     * window titles only.
     */
    private fun buildContentKeywords(cfg: ProtectionConfig): List<String> = buildList {
        if (cfg.blockAccessibilitySettings) {
            addAll(CONTENT_BLOCKED_ACCESSIBILITY)
            addAll(BLOCKED_THE_DOOR)
        }
        if (cfg.blockUninstallTheDoor) addAll(BLOCKED_THE_DOOR)
        if (cfg.blockDeveloperOptions) addAll(BLOCKED_DEV_OPTIONS)
        if (cfg.blockInstallUnknownApps) addAll(BLOCKED_UNKNOWN_INSTALL)
    }

    private fun currentDisplayArea(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WindowManager::class.java)
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width().toLong() * bounds.height().toLong()
            } else {
                val dm = resources.displayMetrics
                dm.widthPixels.toLong() * dm.heightPixels.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    private val overlayPollRunnable = object : Runnable {
        override fun run() {
            if (!overlayManager.isShown) return
            assertProtectionOverlay(force = true)
            if (overlayManager.isShown) {
                handler.postDelayed(this, OVERLAY_POLL_MS)
            }
        }
    }

    private fun startOverlayPoll() {
        handler.removeCallbacks(overlayPollRunnable)
        handler.postDelayed(overlayPollRunnable, OVERLAY_POLL_MS)
    }

    private fun stopOverlayPoll() {
        handler.removeCallbacks(overlayPollRunnable)
    }

    private fun isScheduleActive(packageName: String): Boolean {
        val schedules = scheduledApps[packageName] ?: return false
        val now = Calendar.getInstance()
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return schedules.any { schedule ->
            dayOfWeek in schedule.daysOfWeek && currentMin >= schedule.startMinOfDay && currentMin < schedule.endMinOfDay
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        if (!setupComplete) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            assertProtectionOverlay()
            return
        }

        val packageName = event.packageName?.toString() ?: return

        Log.d(
            "DoorBug",
            "event type=${event.eventType} pkg=$packageName cls=${event.className} text=\"${
                event.text.joinToString(" ")
            }\" contentDesc=\"${event.contentDescription}\""
        )

        // Throttled containment evaluation: preventive floating-window detection and
        // content-based keyword scan. Never suppresses the keyword branches below.
        assertProtectionOverlay()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
            if (protectionConfig.get().blockPowerMenu && packageName == PackageConstants.SYSTEM_UI && contentDesc in setOf(
                    "power off,", "desligar,"
                )
            ) {
                triggerBlock()
                return
            }
        }

        val className = event.className?.toString() ?: ""

        if (protectionConfig.get().blockPowerMenu && packageName == PackageConstants.SYSTEM_UI && className.contains(
                "SamsungGlobalActionsDialog"
            )
        ) {
            val dialogText = event.text.joinToString(" ").lowercase()
            if (dialogText.contains("side button settings") || dialogText.contains("configurações do botão lateral")) {
                lockScreenViaAdmin()
                return
            }
        }

        if (packageName == PackageConstants.SETTINGS) {
            val cfg = protectionConfig.get()
            val eventText = event.text.joinToString(" ").lowercase()
            val blockedKeywords = buildSettingsKeywords(cfg)
            val isAppInfoScreen =
                cfg.blockAppInfo && (className.contains("AppInfoDashboardActivity") || className.contains(
                    "InstalledAppDetailsActivity"
                ) || eventText.contains("app info") || eventText.contains("informações do aplicativo") || eventText.contains(
                    "info do app"
                ))
            if (blockedKeywords.any { eventText.contains(it) } || (cfg.blockAppInfo && className.contains(
                    "DeviceAdminSettingsActivity"
                )) || isAppInfoScreen) {
                triggerProtectionBlock(PackageConstants.SETTINGS)
                return
            }
        }

        if (packageName == PackageConstants.SAMSUNG_BIOMETRICS_SETTINGS) {
            val cfg = protectionConfig.get()
            val eventText = event.text.joinToString(" ").lowercase()
            if (cfg.blockAutoBlocker && BLOCKED_AUTO_BLOCKER.any { eventText.contains(it) }) {
                triggerProtectionBlock(PackageConstants.SAMSUNG_BIOMETRICS_SETTINGS)
                return
            }
        }

        val eventText = event.text.joinToString(" ").lowercase()
        if (packageName == PackageConstants.SECURE_FOLDER && protectionConfig.get().blockSecureFolderAddApps && BLOCKED_ADD_APPS_SECURE_FOLDER.any {
                className.contains(
                    it
                ) || eventText.contains(it)
            }) {
            triggerProtectionBlock(PackageConstants.SECURE_FOLDER)
            return
        }

        val isNewApp = lastActiveApp != packageName

        if (isNewApp) {
            trackAppUsage(packageName)
        }

        if (userBlockedApps.containsKey(packageName)) {
            triggerBlock()
            return
        }

        if (isScheduleActive(packageName)) {
            triggerBlock()
            return
        }

        if (isNewApp && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            maxOpensMap[packageName]?.let { maxOpens ->
                val count = (openCounts[packageName] ?: 0) + 1
                openCounts[packageName] = count
                OpenCountProvider.openCounts.value = openCounts.toMap()
                if (count > maxOpens) {
                    triggerBlock()
                    return
                }
            }
        }

        appLimitsMs[packageName]?.let { limitMs ->
            if (isNewApp && tracker.getCurrentUsage(packageName) >= limitMs) {
                triggerBlock()
                return
            }
        }

        if (packageName == PackageConstants.PACKAGE_INSTALLER_GOOGLE || packageName == PackageConstants.PACKAGE_INSTALLER_AOSP) {
            val cfg = protectionConfig.get()
            val eventText = event.text.joinToString(" ").lowercase()
            if (eventText.contains("uninstall") || eventText.contains("desinstalar")) {
                if (cfg.blockUninstallTheDoor && eventText.contains("the door")) {
                    triggerProtectionBlock(packageName)
                    return
                }
                if (cfg.blockUninstallFirefox && eventText.contains("firefox")) {
                    triggerProtectionBlock(packageName)
                    return
                }
                if (cfg.blockUninstallRethink && eventText.contains("rethink")) {
                    triggerProtectionBlock(packageName)
                    return
                }
            }
        }

        if (packageName == PackageConstants.FIREFOX) {
            val cfg = protectionConfig.get()
            val eventText = event.text.joinToString(" ").lowercase()
            if (cfg.blockFirefoxSettings && (eventText.contains("extensions") || eventText.contains(
                    "settings"
                ) || eventText.contains("extensões") || eventText.contains("configurações"))
            ) {
                triggerProtectionBlock(PackageConstants.FIREFOX)
                return
            }
            if (cfg.blockFirefoxUblockOrigin && eventText.contains("ublock origin")) {
                triggerProtectionBlock(PackageConstants.FIREFOX)
                return
            }
            if (cfg.blockFirefoxBlockNSFW && eventText.contains("blocknsfw")) {
                triggerProtectionBlock(PackageConstants.FIREFOX)
                return
            }
        }
    }

    private var lastSideButtonLockAt = 0L

    private fun lockScreenViaAdmin() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSideButtonLockAt < 3000L) {
            Log.d("DoorBug", "SideBtn lock skipped by debounce")
            return
        }
        lastSideButtonLockAt = now
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, TheDoorAdminReceiver::class.java)
            val active = dpm.isAdminActive(admin)
            Log.d("DoorBug", "SideBtn adminActive=$active")
            if (active) {
                dpm.lockNow()
                Log.d("DoorBug", "SideBtn lockNow() called")
            }
        } catch (e: Exception) {
            Log.d("DoorBug", "SideBtn lock failed: ${e::class.java.simpleName} ${e.message}")
        }
    }

    private fun trackAppUsage(packageName: String) {
        tracker.onAppOpened(packageName)
        lastActiveApp = packageName
        if (appLimitsMs.containsKey(packageName)) {
            handler.removeCallbacks(limitCheckRunnable)
            handler.post(limitCheckRunnable)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun showLimitWarning(app: String, thresholdMs: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val minutes = thresholdMs / 60_000
        val appName = getAppName(app)

        val message = when (minutes) {
            1 -> getString(R.string.limit_warning_1min, appName)
            5 -> getString(R.string.limit_warning_5min, appName)
            else -> getString(R.string.limit_warning_15min, appName)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.the_door_notification)
            .setContentTitle(getString(R.string.limit_warning_title, appName))
            .setContentText(message).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()

        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID_BASE + app.hashCode(), notification)
    }

    private fun getAppName(packageName: String): String {
        return appNameCache.getOrPut(packageName) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
        }
    }

    private fun currentDateString(): String {
        val cal = Calendar.getInstance()
        return String.format(
            Locale.getDefault(),
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    override fun onInterrupt() {
        // onInterrupt only asks to stop current feedback; the enforcement loops must
        // survive it (they were previously killed here and never rescheduled).
        handler.removeCallbacks(gradualReductionCheck)
        handler.removeCallbacks(limitCheckRunnable)
        handler.postDelayed(gradualReductionCheck, 6 * 60 * 60 * 1000L)
        handler.postDelayed(limitCheckRunnable, 5_000L)
        overlayManager.hide()
        stopOverlayPoll()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && overlayManager.shouldConsumeKeys()) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                    super.onKeyEvent(event)

                else -> true
            }
        }
        return super.onKeyEvent(event)
    }
}
