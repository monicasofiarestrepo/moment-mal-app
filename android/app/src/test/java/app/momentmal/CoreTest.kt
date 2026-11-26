package app.momentmal

import app.momentmal.content.BiCard
import app.momentmal.content.Catalog
import app.momentmal.content.DeCard
import app.momentmal.content.Lang
import app.momentmal.content.Presenter
import app.momentmal.data.CardStateEntity
import app.momentmal.session.ItemKind
import app.momentmal.session.SessionEngine
import app.momentmal.session.SessionMode
import app.momentmal.srs.Fsrs
import app.momentmal.srs.Mastery
import app.momentmal.srs.Phase
import app.momentmal.srs.Rating
import app.momentmal.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class CoreTest {
    private val contentRoot = File("../../content")
    private val catalog = Catalog.load { File(contentRoot, it).readText() }
    private val presenter = Presenter(catalog)
    private val fsrs = Fsrs()
    private val day = Fsrs.DAY

    @Test fun parsesAllDecks() {
        assertEquals(listOf("deutsch", "systemdesign", "interview", "dev", "pm"), catalog.decks.map { it.id })
        assertEquals(400, catalog.byDeck["deutsch"]!!.map { it.noteId }.distinct().size)
        for (d in listOf("systemdesign", "interview", "dev", "pm")) assertEquals(d, 100, catalog.byDeck[d]!!.size)
        // every noun has an #artikel card
        val nouns = catalog.cards.filterIsInstance<DeCard>().filter { it.note is app.momentmal.content.Nomen }.map { it.noteId }.distinct().size
        assertEquals(nouns, catalog.cards.count { it is DeCard && it.direction == "artikel" })
        assertEquals(catalog.cards.size, catalog.byId.size) // ids unique
        assertTrue("every note has a stufe", catalog.cards.all { it.stufe in 1..3 })
    }

    @Test fun newCardsStartEasy() {
        var easy = 0; var total = 0
        repeat(100) { seed ->
            val e = engine(SessionMode.Direct("deutsch"), seed = seed)
            repeat(5) { e.next()?.let { total++; if (it.card.stufe == 1) easy++ } }
        }
        assertTrue("mostly stufe 1 at the start: $easy/$total", easy.toDouble() / total > 0.7)
    }

    @Test fun everyCardRendersAndMostHaveMcq() {
        var mcqs = 0
        for (c in catalog.cards) for (lang in Lang.entries) {
            val f = presenter.flashcard(c, lang)
            assertTrue(c.id, f.front.isNotEmpty() && f.back.isNotEmpty())
            val q = presenter.mcq(c, lang, Random(1))
            if (q != null) {
                mcqs++
                assertTrue(c.id, q.options.size in 3..4)
                assertEquals(c.id, q.options.size, q.options.distinct().size)
                assertTrue(c.id, q.correct in q.options.indices)
            }
        }
        assertEquals("all cards should support MCQ", catalog.cards.size * 2, mcqs)
    }

    @Test fun fsrsLearningAndReview() {
        var s = SrsState()
        val t0 = 1_000_000_000_000L
        s = fsrs.review(s, Rating.GOOD, t0)
        assertEquals(Phase.LEARNING, s.phase)
        s = fsrs.review(s, Rating.GOOD, t0 + 10 * Fsrs.MIN)
        assertEquals(Phase.REVIEW, s.phase)
        assertTrue(s.due >= t0 + day)
        var t = s.due
        var prevS = s.stability
        repeat(5) {
            s = fsrs.review(s, Rating.GOOD, t)
            assertTrue("stability grows", s.stability > prevS)
            prevS = s.stability; t = s.due
        }
        assertTrue("well-learned card is GEFESTIGT+", Mastery.of(s) >= Mastery.GEFESTIGT)
        val lapse = fsrs.review(s, Rating.AGAIN, t)
        assertEquals(Phase.RELEARNING, lapse.phase)
        assertEquals(1, lapse.lapses)
        assertEquals(0, lapse.consecutiveGreens)
        assertTrue(lapse.stability < s.stability)
    }

    private fun engine(mode: SessionMode, states: Map<String, CardStateEntity> = emptyMap(), seed: Int = 7, now: Long = 2_000_000_000_000L) =
        SessionEngine(catalog, presenter, fsrs, states, mode, 0, rnd = Random(seed), clock = { now })

    @Test fun challengeServesRequiredCountWithoutDuplicateNotes() {
        val e = engine(SessionMode.Challenge(10))
        val notes = mutableSetOf<String>()
        while (true) {
            val item = e.next() ?: break
            if (!item.isRepeat) assertTrue("no duplicate note", notes.add(item.card.noteId))
            e.answer(item, if (notes.size == 3 && !item.isRepeat) Rating.AGAIN else Rating.GOOD, 1000, 1)
        }
        assertEquals(10, e.counted)
        assertTrue(e.isComplete)
    }

    @Test fun challengeEndsAfterRequiredEvenWithAllAgain() {
        val e = engine(SessionMode.Challenge(10))
        var served = 0
        while (true) {
            val item = e.next() ?: break
            assertTrue("no repeats inside a challenge", !item.isRepeat)
            e.answer(item, Rating.AGAIN, 1000, 1)
            served++
            assertTrue("never more than required", served <= 10)
        }
        assertEquals(10, served)
        assertTrue(e.isComplete)
    }

    @Test fun challengeCanBeExtendedByFive() {
        val e = engine(SessionMode.Challenge(10))
        repeat(10) { e.answer(e.next()!!, Rating.GOOD, 1000, 1) }
        assertTrue(e.next() == null && e.isComplete)
        e.extend(5)
        assertEquals(15, e.required)
        var more = 0
        while (true) { val i = e.next() ?: break; e.answer(i, Rating.GOOD, 1000, 1); more++ }
        assertEquals(5, more)
        assertTrue(e.isComplete)
    }

    @Test fun directStudyStillRepeatsAgainCards() {
        val e = engine(SessionMode.Direct("deutsch"))
        val first = e.next()!!
        e.answer(first, Rating.AGAIN, 1000, 1)
        val later = (1..8).mapNotNull { e.next()?.also { i -> e.answer(i, Rating.GOOD, 1000, 1) } }
        assertTrue("again card returns in direct mode", later.any { it.isRepeat && it.card.id == first.card.id })
    }

    @Test fun topicWeightsApproximately70Percent() {
        var de = 0; var total = 0
        repeat(200) { seed ->
            val e = engine(SessionMode.Challenge(10), seed = seed)
            repeat(10) { e.next()?.let { total++; if (it.card.deckId == "deutsch") de++ } }
        }
        val share = de.toDouble() / total
        assertTrue("deutsch share $share", share in 0.64..0.76)
    }

    @Test fun mcqOnlyForWellLearnedCards() {
        val now = 2_000_000_000_000L
        val learned = catalog.cards.take(40).associate {
            it.id to CardStateEntity(it.id, it.deckId, Phase.REVIEW.ordinal, 40.0, 5.0, now + 10 * day, now - 30 * day, 5, 0, 0, 4, null, 0)
        }
        var mcq = 0
        repeat(50) { seed ->
            val e = engine(SessionMode.Direct("deutsch"), learned, seed, now)
            repeat(20) {
                val item = e.next() ?: return@repeat
                if (item.kind == ItemKind.MCQ) { mcq++; assertTrue(item.card.id in learned) }
            }
        }
        assertTrue("some MCQs served", mcq > 0)
    }

    @Test fun bilingualAlternatesLanguage() {
        val e = engine(SessionMode.Direct("interview"))
        val first = e.next()!!
        assertTrue(first.card is BiCard)
        e.answer(first, Rating.GOOD, 1000, 1)
        val st = e.stateOf(first.card.id)
        assertNotNull(st)
        assertEquals(first.lang.name, st!!.lastLang)
    }
}
