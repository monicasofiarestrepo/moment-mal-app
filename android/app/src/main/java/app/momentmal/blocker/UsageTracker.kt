package app.momentmal.blocker

import app.momentmal.data.UsageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Measures time spent in blocked apps/sites from foreground changes reported by the
 * accessibility service. One open segment at a time; closed on every switch, on screen-off
 * and periodically (so long sessions are persisted and counted live).
 * Main-thread only.
 */
class UsageTracker(private val dao: UsageDao, private val scope: CoroutineScope) {
    private var day = LocalDate.now().toString()
    private val today = mutableMapOf<String, Long>()
    private var curKey: String? = null
    private var curStart = 0L

    fun load() {
        val d = day
        scope.launch {
            val rows = dao.forDay(d)
            if (d != day) return@launch
            for (r in rows) today[r.usageKey] = maxOf(today[r.usageKey] ?: 0, r.ms)
        }
    }

    /** [key] = blocked key now in front, or null when nothing tracked is in front. */
    fun switchTo(key: String?, now: Long = System.currentTimeMillis()) {
        if (key == curKey) return
        close(now)
        if (key != null) {
            curKey = key
            curStart = now
        }
    }

    /** Persist the running segment without ending it. */
    fun flush(now: Long = System.currentTimeMillis()) {
        val k = curKey ?: return
        close(now)
        curKey = k
        curStart = now
    }

    private fun close(now: Long) {
        val k = curKey ?: return
        curKey = null
        val ms = (now - curStart).coerceIn(0, 6 * 60 * 60_000L)
        if (ms < 1000) return
        rollDay()
        today[k] = (today[k] ?: 0) + ms
        val d = day
        scope.launch { dao.add(d, k, ms) }
    }

    private fun rollDay() {
        val d = LocalDate.now().toString()
        if (d != day) {
            day = d
            today.clear()
        }
    }

    fun usedMs(key: String, now: Long = System.currentTimeMillis()): Long {
        rollDay()
        return (today[key] ?: 0) + if (key == curKey) (now - curStart).coerceAtLeast(0) else 0
    }

    fun totalMs(now: Long = System.currentTimeMillis()): Long {
        rollDay()
        return today.values.sum() + if (curKey != null) (now - curStart).coerceAtLeast(0) else 0
    }
}
