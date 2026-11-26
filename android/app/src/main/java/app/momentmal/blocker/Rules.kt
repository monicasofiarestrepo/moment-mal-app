package app.momentmal.blocker

/** Pure rules, unit-tested in RulesTest. */
object LimitRules {
    const val BASE = 10
    const val OVER_LIMIT = 20
    const val PER_EXTRA_BLOCK = 10
    const val EXTRA_BLOCK_MS = 2 * 60 * 60_000L

    data class Demand(val required: Int, val overMs: Long)

    /**
     * Within limits: 10 questions. Over the per-app or the total limit (whichever is exceeded more):
     * 20 + 10 for every full 2 hours over.
     */
    fun demand(appUsedMs: Long, totalUsedMs: Long, appLimitMs: Long, totalLimitMs: Long): Demand {
        val over = maxOf(appUsedMs - appLimitMs, totalUsedMs - totalLimitMs)
        if (over <= 0) return Demand(BASE, 0)
        return Demand(OVER_LIMIT + PER_EXTRA_BLOCK * (over / EXTRA_BLOCK_MS).toInt(), over)
    }

    fun formatDuration(ms: Long): String {
        val min = ms / 60_000
        val h = min / 60
        return if (h > 0) "$h h ${min % 60} min" else "$min min"
    }
}

/** Browser address bars and domain matching. */
object WebRules {
    /** Browser package → view id of its address bar. */
    val URL_BARS = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
    )

    const val WEB_PREFIX = "web:"

    /** "https://www.instagram.com/p/x?y" → "instagram.com". Null for search terms / garbage. */
    fun hostOf(text: String?): String? {
        var t = text?.trim()?.lowercase() ?: return null
        if (t.isEmpty() || ' ' in t) return null
        t = t.removePrefix("https://").removePrefix("http://")
        val host = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if ('.' !in host) return null
        return host.removePrefix("www.")
    }

    /** The blocked domain this host belongs to (subdomains included), or null. */
    fun match(host: String, domains: Set<String>): String? =
        domains.firstOrNull { host == it || host.endsWith(".$it") }

    fun normalizeDomain(input: String): String? = hostOf(input)
}
