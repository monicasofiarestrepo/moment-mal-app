package app.momentmal.blocker

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

private val Context.blockerStore by preferencesDataStore("blocker")

/** Fast, immutable view of the blocker settings for the accessibility service. */
data class BlockerSnapshot(
    val blocked: Set<String> = BlockerPrefs.DEFAULT_BLOCKED,
    val domains: Set<String> = BlockerPrefs.DEFAULT_DOMAINS,
    /** Grace per key: package name, or "web:<domain>". */
    val graceUntil: Map<String, Long> = emptyMap(),
    val skipsLeft: Int = BlockerPrefs.SKIPS_PER_DAY,
    val appLimitMin: Int = BlockerPrefs.DEFAULT_APP_LIMIT,
    val totalLimitMin: Int = BlockerPrefs.DEFAULT_TOTAL_LIMIT,
    val pausedToday: Boolean = false,
    val pausesLeft: Int = BlockerPrefs.PAUSES_PER_MONTH,
    /** ISO dates of all pause days (they don't break the streak). */
    val pauseDays: Set<String> = emptySet(),
) {
    fun inGrace(key: String, now: Long) = (graceUntil[key] ?: 0) > now
    val appLimitMs get() = appLimitMin * 60_000L
    val totalLimitMs get() = totalLimitMin * 60_000L
}

class BlockerPrefs(private val context: Context) {
    private val store get() = context.blockerStore

    val snapshot: Flow<BlockerSnapshot> = store.data.map { it.toSnapshot() }

    private fun Preferences.toSnapshot(): BlockerSnapshot {
        val grace = asMap().entries
            .filter { it.key.name.startsWith(GRACE_PREFIX) }
            .associate { it.key.name.removePrefix(GRACE_PREFIX) to (it.value as Long) }
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val skipsUsed = if (this[SKIP_DAY] == today) this[SKIP_USED] ?: 0 else 0
        val pausesUsed = if (this[PAUSE_MONTH] == month) this[PAUSE_USED] ?: 0 else 0
        return BlockerSnapshot(
            blocked = this[BLOCKED] ?: DEFAULT_BLOCKED,
            domains = this[DOMAINS] ?: DEFAULT_DOMAINS,
            graceUntil = grace,
            skipsLeft = (SKIPS_PER_DAY - skipsUsed).coerceAtLeast(0),
            appLimitMin = this[APP_LIMIT] ?: DEFAULT_APP_LIMIT,
            totalLimitMin = this[TOTAL_LIMIT] ?: DEFAULT_TOTAL_LIMIT,
            pausedToday = this[PAUSE_DAY] == today,
            pausesLeft = (PAUSES_PER_MONTH - pausesUsed).coerceAtLeast(0),
            pauseDays = this[PAUSE_DAYS] ?: emptySet(),
        )
    }

    suspend fun setBlocked(pkg: String, blocked: Boolean) = store.edit {
        val cur = it[BLOCKED] ?: DEFAULT_BLOCKED
        it[BLOCKED] = if (blocked) cur + pkg else cur - pkg
    }

    suspend fun setDomain(domain: String, blocked: Boolean) = store.edit {
        val cur = it[DOMAINS] ?: DEFAULT_DOMAINS
        it[DOMAINS] = if (blocked) cur + domain else cur - domain
    }

    suspend fun setLimits(appMin: Int, totalMin: Int) = store.edit {
        it[APP_LIMIT] = appMin
        it[TOTAL_LIMIT] = totalMin
    }

    suspend fun grantGrace(key: String, minutes: Int = GRACE_MINUTES) {
        store.edit { it[longPreferencesKey(GRACE_PREFIX + key)] = System.currentTimeMillis() + minutes * 60_000L }
    }

    /** Consumes one of today's skips. Returns false when none are left. */
    suspend fun useSkip(): Boolean {
        var ok = false
        store.edit {
            val today = LocalDate.now().toString()
            val used = if (it[SKIP_DAY] == today) it[SKIP_USED] ?: 0 else 0
            if (used < SKIPS_PER_DAY) {
                it[SKIP_DAY] = today
                it[SKIP_USED] = used + 1
                ok = true
            }
        }
        return ok
    }

    /** Turns the blocker off until midnight. Max 3 per calendar month. */
    suspend fun usePauseDay(): Boolean {
        var ok = false
        store.edit {
            val today = LocalDate.now().toString()
            val month = YearMonth.now().toString()
            if (it[PAUSE_DAY] == today) { ok = true; return@edit }
            val used = if (it[PAUSE_MONTH] == month) it[PAUSE_USED] ?: 0 else 0
            if (used < PAUSES_PER_MONTH) {
                it[PAUSE_MONTH] = month
                it[PAUSE_USED] = used + 1
                it[PAUSE_DAY] = today
                it[PAUSE_DAYS] = (it[PAUSE_DAYS] ?: emptySet()) + today
                ok = true
            }
        }
        return ok
    }

    suspend fun current() = snapshot.first()

    /** Used by backup import. */
    suspend fun restore(blocked: Set<String>, domains: Set<String>, appMin: Int, totalMin: Int, pauseDays: Set<String>) = store.edit {
        it[BLOCKED] = blocked
        it[DOMAINS] = domains
        it[APP_LIMIT] = appMin
        it[TOTAL_LIMIT] = totalMin
        it[PAUSE_DAYS] = pauseDays
    }

    companion object {
        const val GRACE_MINUTES = 20
        const val SKIPS_PER_DAY = 3
        const val PAUSES_PER_MONTH = 3
        const val DEFAULT_APP_LIMIT = 30
        const val DEFAULT_TOTAL_LIMIT = 90
        private const val GRACE_PREFIX = "grace_"
        private val BLOCKED = stringSetPreferencesKey("blocked")
        private val DOMAINS = stringSetPreferencesKey("domains")
        private val SKIP_DAY = stringPreferencesKey("skip_day")
        private val SKIP_USED = intPreferencesKey("skip_used")
        private val APP_LIMIT = intPreferencesKey("app_limit_min")
        private val TOTAL_LIMIT = intPreferencesKey("total_limit_min")
        private val PAUSE_DAY = stringPreferencesKey("pause_day")
        private val PAUSE_MONTH = stringPreferencesKey("pause_month")
        private val PAUSE_USED = intPreferencesKey("pause_used")
        private val PAUSE_DAYS = stringSetPreferencesKey("pause_days")

        val DEFAULT_BLOCKED = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok (other regions)
            "com.pinterest",
            "com.facebook.katana",
            "com.google.android.youtube",
            "com.twitter.android", // X
            "com.reddit.frontpage",
        )

        val DEFAULT_DOMAINS = setOf(
            "instagram.com", "tiktok.com", "pinterest.com", "facebook.com",
            "youtube.com", "x.com", "twitter.com", "reddit.com",
        )
    }
}
