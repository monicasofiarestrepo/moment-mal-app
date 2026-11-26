package app.momentmal.blocker

import java.time.LocalDate

/** Relaxing changes confirmed with a challenge; they take effect on [day] (ISO date). */
data class PendingChanges(
    val unblock: Set<String> = emptySet(),
    val undomain: Set<String> = emptySet(),
    val appLimitMin: Int? = null,
    val totalLimitMin: Int? = null,
    val strictOff: Boolean = false,
    val day: String? = null,
) {
    fun isDue(today: LocalDate) = day != null && !today.isBefore(LocalDate.parse(day))
    val isEmpty get() = unblock.isEmpty() && undomain.isEmpty() && appLimitMin == null && totalLimitMin == null && !strictOff
}

/** A relaxing change waiting for its 30-question confirmation. */
sealed interface StagedChange {
    data class Unblock(val pkg: String) : StagedChange
    data class Undomain(val domain: String) : StagedChange
    data class Limits(val appMin: Int, val totalMin: Int) : StagedChange
    data object StrictOff : StagedChange
    /** More free minutes after the challenge for one app/site (applies immediately once confirmed). */
    data class FreeTimeUp(val key: String, val minutes: Int) : StagedChange

    fun encode(): String = when (this) {
        is Unblock -> "unblock|$pkg"
        is Undomain -> "undomain|$domain"
        is Limits -> "limits|$appMin|$totalMin"
        StrictOff -> "strictoff"
        is FreeTimeUp -> "freetime|$key|$minutes"
    }

    companion object {
        fun decode(s: String): StagedChange? {
            val p = s.split('|')
            return when (p[0]) {
                "unblock" -> Unblock(p[1])
                "undomain" -> Undomain(p[1])
                "limits" -> Limits(p[1].toInt(), p[2].toInt())
                "strictoff" -> StrictOff
                "freetime" -> FreeTimeUp(p[1], p[2].toInt())
                else -> null
            }
        }
    }
}

/** Free minutes after a completed challenge. */
object FreeTime {
    const val SHORT = 15
    const val LONG = 45
    const val MIN = 5
    const val MAX = 45
    const val STEP = 5

    /** YouTube (app and website) gets long-form time; short-form feeds get 15 minutes. */
    private val LONG_FORM = setOf("com.google.android.youtube", "web:youtube.com")

    fun default(key: String) = if (key in LONG_FORM) LONG else SHORT
}

object StrictRules {
    const val CONFIRM_QUESTIONS = 30
    const val SETTINGS_GRACE_MIN = 5

    /** Challenge keys that protect Moment mal itself (never skippable, not affected by pause days). */
    const val KEY_SETTINGS = "protect:settings"
    const val KEY_CHANGE = "protect:change"

    fun isProtectKey(key: String) = key.startsWith("protect:")

    /** Settings applied as of [today]: due pending relaxations count, not-yet-due ones don't. */
    fun effective(base: BlockerSnapshot, today: LocalDate): BlockerSnapshot {
        val p = base.pending
        if (!p.isDue(today)) return base
        return base.copy(
            blocked = base.blocked - p.unblock,
            domains = base.domains - p.undomain,
            appLimitMin = p.appLimitMin ?: base.appLimitMin,
            totalLimitMin = p.totalLimitMin ?: base.totalLimitMin,
            strict = base.strict && !p.strictOff,
        )
    }

    /** Is lowering/raising [newMin] vs [current] a relaxation? */
    fun isRelaxing(currentApp: Int, currentTotal: Int, newApp: Int, newTotal: Int) = newApp > currentApp || newTotal > currentTotal

    // ---------------------------------------------------------------- settings guard

    /** System apps where Moment mal could be switched off or uninstalled. */
    val GUARDED_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.accessibility",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /** Words of the dangerous actions (EN / ES / DE, incl. Samsung wording). */
    private val DANGER = listOf(
        "uninstall", "force stop", "deactivate", "turn off", "disable",
        "desinstalar", "forzar", "desactivar", "detener",
        "deinstallieren", "stopp erzwingen", "beenden erzwingen", "deaktivieren", "ausschalten",
    )

    /**
     * True when the visible window is about Moment mal *and* offers a way to switch it off:
     * our accessibility page (recognised by its description), app info, device-admin
     * deactivation or the uninstall dialog.
     */
    fun isDangerousScreen(texts: List<String>, appName: String, a11yDescriptionSnippet: String): Boolean {
        val all = texts.joinToString("\n").lowercase()
        if (a11yDescriptionSnippet.lowercase() in all) return true
        if (appName.lowercase() !in all) return false
        return DANGER.any { it in all }
    }
}
