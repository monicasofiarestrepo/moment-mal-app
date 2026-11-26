package app.momentmal.blocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import app.momentmal.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches which app (or, in browsers, which site) is in front — like one sec.
 *
 * - Entering a blocked app/site without an active grace period shows the challenge on top
 *   of it, in its own task. Finishing reveals the app/page exactly where it was.
 * - Time in blocked apps/sites is measured; over the limit the challenge asks for more
 *   questions and, once the grace period ends, interrupts even while the app stays open.
 * - A pause day disables interception (time is still measured).
 */
class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var snap = BlockerSnapshot()
    private lateinit var tracker: UsageTracker

    /** Current foreground key: package name, or "web:<domain>" for a blocked site in a browser. */
    private var lastKey: String? = null
    private var lastPkg: String? = null
    private var lastLaunchAt = 0L
    private var lastLaunchKey: String? = null
    private var lastUrlCheck = 0L
    private var transient = setOf<String>()
    private var homePkgs = setOf<String>()

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    tracker.switchTo(null)
                    lastKey = null
                    lastPkg = null
                }
                // Unlocked straight into an app: treat it as entering that app again.
                Intent.ACTION_USER_PRESENT -> handler.postDelayed({ evaluateCurrentWindow() }, 400)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        transient = TRANSIENT + imePackages()
        homePkgs = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        tracker = UsageTracker(container.db.usage(), scope).also { it.load() }
        scope.launch { container.blockerPrefs.snapshot.collect { snap = it } }
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Overlays like the keyboard, notification shade or share sheet don't count as leaving the app.
        if (pkg in transient) return

        val isBrowser = pkg in WebRules.URL_BARS
        val isGuarded = pkg in StrictRules.GUARDED_PACKAGES
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // content changes only matter for browsers (address bar) and guarded settings screens, throttled
            if (!(isBrowser || isGuarded) || pkg != lastPkg) return
            val now = System.currentTimeMillis()
            if (now - lastUrlCheck < URL_THROTTLE_MS) return
            lastUrlCheck = now
        }
        handleForeground(pkg)
    }

    private fun handleForeground(pkg: String) {
        if (returnSafetyNet(pkg)) return
        val key = when {
            pkg in WebRules.URL_BARS -> browserKey(pkg)
            pkg in StrictRules.GUARDED_PACKAGES && snap.strict && isDangerousScreen(pkg) -> StrictRules.KEY_SETTINGS
            else -> pkg
        }
        lastPkg = pkg
        if (key == lastKey) return
        lastKey = key

        if (key == StrictRules.KEY_SETTINGS) {
            tracker.switchTo(null)
            maybeIntercept(key, pkg)
            return
        }

        val blocked = isBlocked(key)
        tracker.switchTo(if (blocked) key else null)
        if (blocked) maybeIntercept(key, pkg)
    }

    /** "web:<domain>" when a blocked site is open, the browser package otherwise. */
    private fun browserKey(pkg: String): String {
        val host = readUrl(pkg)?.let(WebRules::hostOf)
        if (host == null) {
            // Address bar hidden (scrolling) or being edited: keep the current site.
            return lastKey?.takeIf { lastPkg == pkg } ?: pkg
        }
        return WebRules.match(host, snap.domains)?.let { WebRules.WEB_PREFIX + it } ?: pkg
    }

    private fun readUrl(pkg: String): String? {
        val id = WebRules.URL_BARS[pkg] ?: return null
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull() ?: return null
        if (node.isFocused) return null // user is typing a new address/search
        return node.text?.toString()
    }

    /** Reads the visible texts of a settings/installer window (bounded walk). */
    private fun isDangerousScreen(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val texts = ArrayList<String>()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 400) {
            val n = queue.removeFirst()
            n.text?.let { texts += it.toString() }
            n.contentDescription?.let { texts += it.toString() }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return StrictRules.isDangerousScreen(texts, appName, A11Y_SNIPPET)
    }

    private val appName by lazy { getString(app.momentmal.R.string.app_name) }

    private fun isBlocked(key: String) =
        key in snap.blocked || (key.startsWith(WebRules.WEB_PREFIX) && key.removePrefix(WebRules.WEB_PREFIX) in snap.domains)

    private fun maybeIntercept(key: String, pkg: String) {
        val now = System.currentTimeMillis()
        val protect = StrictRules.isProtectKey(key)
        // pause days switch off the quiz for distractions, never the self-protection
        if ((snap.pausedToday && !protect) || snap.inGrace(key, now)) return
        if (key == lastLaunchKey && now - lastLaunchAt < 1500) return
        lastLaunchKey = key
        lastLaunchAt = now

        val demand = if (protect) LimitRules.Demand(StrictRules.CONFIRM_QUESTIONS, 0)
        else LimitRules.demand(tracker.usedMs(key, now), tracker.totalMs(now), snap.appLimitMs, snap.totalLimitMs)
        startActivity(
            Intent(this, ChallengeActivity::class.java)
                .putExtra(ChallengeActivity.EXTRA_PKG, pkg)
                .putExtra(ChallengeActivity.EXTRA_KEY, key)
                .putExtra(ChallengeActivity.EXTRA_REQUIRED, demand.required)
                .putExtra(ChallengeActivity.EXTRA_OVER_MS, demand.overMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    /** Every minute: persist time, and interrupt over-limit use once the grace period is over. */
    private fun onTick() {
        tracker.flush()
        scope.launch { Strict.commitDue(this@BlockerService) }
        val key = lastKey ?: return
        val pkg = lastPkg ?: return
        if (!isBlocked(key)) return
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        val now = System.currentTimeMillis()
        val over = LimitRules.demand(tracker.usedMs(key, now), tracker.totalMs(now), snap.appLimitMs, snap.totalLimitMs).overMs > 0
        if (over) maybeIntercept(key, pkg)
    }

    private fun evaluateCurrentWindow() {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        if (pkg in transient) return
        lastKey = null
        handleForeground(pkg)
    }

    /**
     * Safety net: after the challenge, Android should reveal the blocked app where it was.
     * If the launcher shows up instead, bring the app's existing task back to front.
     */
    private fun returnSafetyNet(pkg: String): Boolean {
        val target = ReturnTracker.pending() ?: return false
        if (pkg == target) {
            ReturnTracker.clear()
            return false
        }
        if (pkg !in homePkgs) return false
        ReturnTracker.clear()
        packageManager.getLaunchIntentForPackage(target)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        return true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenReceiver) }
        if (::tracker.isInitialized) tracker.switchTo(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun imePackages(): Set<String> =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.map { it.packageName }.toSet()

    companion object {
        private const val TICK_MS = 60_000L
        private const val URL_THROTTLE_MS = 500L
        /** Unique part of our accessibility description (identifies our page in Settings). */
        const val A11Y_SNIPPET = "zeigt vorher ein kurzes Quiz"

        private val TRANSIENT = setOf(
            "com.android.systemui",
            "android",
            "com.android.intentresolver",
            "com.google.android.permissioncontroller",
            "com.samsung.android.honeyboard",
            "com.samsung.android.app.cocktailbarservice", // Edge panels
        )

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${BlockerService::class.java.name}"
            return enabled.split(':').any { it.equals(me, ignoreCase = true) || it.endsWith("/.blocker.BlockerService") && it.startsWith(context.packageName) }
        }
    }
}

/** Remembers which app the user should land in right after finishing/skipping a challenge. */
object ReturnTracker {
    @Volatile private var pkg: String? = null
    @Volatile private var at = 0L

    fun expect(target: String) { pkg = target; at = System.currentTimeMillis() }
    fun pending(): String? = pkg?.takeIf { System.currentTimeMillis() - at < 2000 }
    fun clear() { pkg = null }
}
