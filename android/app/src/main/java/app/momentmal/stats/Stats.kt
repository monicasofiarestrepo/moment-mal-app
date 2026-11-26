package app.momentmal.stats

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Pure calculations (unit-tested in StatsTest). */
object StatsCalc {
    const val STREAK_MIN_ANSWERS = 10

    /**
     * Consecutive days with ≥10 answers. Pause days neither break nor extend the streak.
     * Today only counts once reached; an unfinished today doesn't break it.
     */
    fun streak(answersPerDay: Map<LocalDate, Int>, pauseDays: Set<LocalDate>, today: LocalDate): Int {
        fun ok(d: LocalDate) = (answersPerDay[d] ?: 0) >= STREAK_MIN_ANSWERS
        var d = if (ok(today)) today else today.minusDays(1)
        var n = 0
        var guard = 0
        while (guard++ < 5000) {
            when {
                ok(d) -> n++
                d in pauseDays -> {}
                else -> break
            }
            d = d.minusDays(1)
        }
        return n
    }

    /** Σ max(0, baseline − used) over the full days since install (today excluded). */
    fun savedMs(baselineMs: Long, usedPerDay: Map<LocalDate, Long>, installDay: LocalDate, today: LocalDate): Long {
        var total = 0L
        var d = installDay
        while (d.isBefore(today)) {
            total += (baselineMs - (usedPerDay[d] ?: 0)).coerceAtLeast(0)
            d = d.plusDays(1)
        }
        return total
    }
}

private val Context.statsStore by preferencesDataStore("stats")

/** "Before" baseline (minutes/day in blocked apps) for the time-saved stat. */
class StatsPrefs(private val context: Context) {
    private val key = intPreferencesKey("baseline_min")

    val baseline: Flow<Int?> = context.statsStore.data.map { it[key] }

    suspend fun baselineMin(): Int? = baseline.first()

    suspend fun setBaselineMin(min: Int) {
        context.statsStore.edit { it[key] = min.coerceIn(0, 24 * 60) }
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /**
     * Average daily foreground time of [packages] in the 14 days before Moment mal was installed,
     * from Android's usage data. Null without usage access or data.
     */
    fun computeBaselineMin(packages: Set<String>): Int? {
        if (!hasUsageAccess()) return null
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        val begin = installed - 14 * 86_400_000L
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val stats = usm.queryAndAggregateUsageStats(begin, installed)
        if (stats.isEmpty()) return null
        val totalMs = packages.sumOf { stats[it]?.totalTimeInForeground ?: 0L }
        return (totalMs / 14 / 60_000).toInt()
    }
}
