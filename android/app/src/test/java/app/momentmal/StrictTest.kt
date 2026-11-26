package app.momentmal

import app.momentmal.blocker.BlockerSnapshot
import app.momentmal.blocker.FreeTime
import app.momentmal.blocker.PendingChanges
import app.momentmal.blocker.StagedChange
import app.momentmal.blocker.StrictRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StrictTest {
    private val today = LocalDate.of(2026, 11, 26)
    private val base = BlockerSnapshot(
        blocked = setOf("ig", "tt"), domains = setOf("x.com"), appLimitMin = 30, totalLimitMin = 90, strict = true,
    )

    @Test fun pendingRelaxationWaitsUntilItsDay() {
        val pending = PendingChanges(unblock = setOf("tt"), appLimitMin = 45, strictOff = true, day = today.plusDays(1).toString())
        val s = base.copy(pending = pending)
        val now = StrictRules.effective(s, today)
        assertEquals(setOf("ig", "tt"), now.blocked)
        assertEquals(30, now.appLimitMin)
        assertTrue(now.strict)
        val tomorrow = StrictRules.effective(s, today.plusDays(1))
        assertEquals(setOf("ig"), tomorrow.blocked)
        assertEquals(45, tomorrow.appLimitMin)
        assertFalse(tomorrow.strict)
    }

    @Test fun stagedChangesRoundTrip() {
        listOf(StagedChange.Unblock("com.instagram.android"), StagedChange.Undomain("x.com"), StagedChange.Limits(40, 120), StagedChange.StrictOff,
            StagedChange.FreeTimeUp("web:youtube.com", 45), StagedChange.FreeTimeUp("com.zhiliaoapp.musically", 30))
            .forEach { assertEquals(it, StagedChange.decode(it.encode())) }
    }

    @Test fun relaxingDetection() {
        assertTrue(StrictRules.isRelaxing(30, 90, 35, 90))
        assertTrue(StrictRules.isRelaxing(30, 90, 30, 95))
        assertFalse(StrictRules.isRelaxing(30, 90, 25, 90))
    }

    @Test fun dangerousScreens() {
        val desc = "zeigt vorher ein kurzes Quiz"
        // app info (Spanish Samsung UI)
        assertTrue(StrictRules.isDangerousScreen(listOf("Información de la aplicación", "Moment mal", "Desinstalar", "Forzar detención"), "Moment mal", desc))
        // our accessibility page, recognised by its description even without the name
        assertTrue(StrictRules.isDangerousScreen(listOf("Erkennt, wenn du eine blockierte App öffnest, und zeigt vorher ein kurzes Quiz."), "Moment mal", desc))
        // uninstall dialog (English)
        assertTrue(StrictRules.isDangerousScreen(listOf("Moment mal", "Do you want to uninstall this app?", "OK"), "Moment mal", desc))
        // another app's info page
        assertFalse(StrictRules.isDangerousScreen(listOf("Instagram", "Uninstall", "Force stop"), "Moment mal", desc))
        // settings home / app list with our name but nothing dangerous
        assertFalse(StrictRules.isDangerousScreen(listOf("Apps", "Moment mal", "Instagram", "WhatsApp"), "Moment mal", desc))
    }

    @Test fun freeTimeDefaultsAndOverrides() {
        assertEquals(45, FreeTime.default("com.google.android.youtube"))
        assertEquals(45, FreeTime.default("web:youtube.com"))
        assertEquals(15, FreeTime.default("com.zhiliaoapp.musically"))
        assertEquals(15, FreeTime.default("web:x.com"))
        val s = base.copy(freeMinutes = mapOf("com.instagram.android" to 30))
        assertEquals(30, s.freeMinutesFor("com.instagram.android"))
        assertEquals(15, s.freeMinutesFor("com.twitter.android"))
    }
}
