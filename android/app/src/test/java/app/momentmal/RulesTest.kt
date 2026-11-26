package app.momentmal

import app.momentmal.blocker.LimitRules
import app.momentmal.blocker.WebRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulesTest {
    private val min = 60_000L
    private val h = 60 * min

    @Test fun questionsWithinLimit() {
        assertEquals(LimitRules.Demand(10, 0), LimitRules.demand(10 * min, 50 * min, 30 * min, 90 * min))
        assertEquals(10, LimitRules.demand(30 * min, 90 * min, 30 * min, 90 * min).required) // exactly at limit
    }

    @Test fun questionsOverLimit() {
        assertEquals(20, LimitRules.demand(31 * min, 31 * min, 30 * min, 90 * min).required)
        // spec example: 4 h 30 over → 20 + 10×2 = 40
        assertEquals(40, LimitRules.demand(30 * min + 4 * h + 30 * min, 0, 30 * min, 90 * min).required)
        // total limit can be the bigger overage
        val d = LimitRules.demand(10 * min, 90 * min + 2 * h, 30 * min, 90 * min)
        assertEquals(30, d.required)
        assertEquals(2 * h, d.overMs)
    }

    @Test fun hostParsing() {
        assertEquals("instagram.com", WebRules.hostOf("https://www.instagram.com/p/abc?igsh=1"))
        assertEquals("instagram.com", WebRules.hostOf("instagram.com/reel/xyz"))
        assertEquals("facebook.com", WebRules.match(WebRules.hostOf("m.facebook.com")!!, setOf("facebook.com")))
        assertEquals("vm.tiktok.com", WebRules.hostOf("vm.tiktok.com/ZM123/"))
        assertNull(WebRules.hostOf("rezept lasagne"))
        assertNull(WebRules.hostOf("localhost"))
        assertNull(WebRules.hostOf(""))
    }

    @Test fun domainMatching() {
        val blocked = setOf("tiktok.com", "x.com", "instagram.com")
        assertEquals("tiktok.com", WebRules.match("vm.tiktok.com", blocked))
        assertEquals("x.com", WebRules.match("x.com", blocked))
        assertNull(WebRules.match("box.com", blocked)) // not a suffix match on "x.com"
        assertNull(WebRules.match("google.com", blocked))
    }
}
