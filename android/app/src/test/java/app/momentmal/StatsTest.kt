package app.momentmal

import app.momentmal.stats.StatsCalc
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private val today = LocalDate.of(2026, 11, 26)
    private fun d(back: Long) = today.minusDays(back)

    @Test fun streakCountsConsecutiveDays() {
        val a = mapOf(d(0) to 12, d(1) to 10, d(2) to 30, d(3) to 3)
        assertEquals(3, StatsCalc.streak(a, emptySet(), today))
    }

    @Test fun unfinishedTodayDoesNotBreak() {
        val a = mapOf(d(0) to 4, d(1) to 10, d(2) to 10)
        assertEquals(2, StatsCalc.streak(a, emptySet(), today))
    }

    @Test fun pauseDayBridgesButDoesNotCount() {
        val a = mapOf(d(1) to 10, d(3) to 10, d(4) to 10)
        assertEquals(3, StatsCalc.streak(a, setOf(d(2)), today))
        assertEquals(1, StatsCalc.streak(a, emptySet(), today))
    }

    @Test fun savedTimeExcludesTodayAndNeverNegative() {
        val base = 90 * 60_000L
        val used = mapOf(d(2) to 30 * 60_000L, d(1) to 120 * 60_000L, d(0) to 0L)
        // install 2 days ago: day-2 saves 60 min, day-1 saves 0 (over baseline), today excluded
        assertEquals(60 * 60_000L, StatsCalc.savedMs(base, used, d(2), today))
    }
}
