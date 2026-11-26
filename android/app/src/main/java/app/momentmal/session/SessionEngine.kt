package app.momentmal.session

import app.momentmal.content.BiCard
import app.momentmal.content.CardDef
import app.momentmal.content.Catalog
import app.momentmal.content.Flashcard
import app.momentmal.content.Lang
import app.momentmal.content.Mcq
import app.momentmal.content.Presenter
import app.momentmal.data.CardStateEntity
import app.momentmal.data.ReviewLogEntity
import app.momentmal.srs.Fsrs
import app.momentmal.srs.Mastery
import app.momentmal.srs.Rating
import app.momentmal.srs.SrsState
import kotlin.random.Random

sealed interface SessionMode {
    /** Opened from the launcher. [deckId] null = "Gemischt" (weighted). Unlimited. */
    data class Direct(val deckId: String?) : SessionMode

    /** Triggered by a blocked app. Weighted topics, [required] counted answers. */
    data class Challenge(val required: Int) : SessionMode
}

data class SessionConfig(
    val newCardsPerDay: Int = 20,
    /** Share of items served as multiple choice when GEFESTIGT+ cards exist. */
    val mcqShare: Double = 0.3,
    /** Max times an "Again" card comes back within one session. */
    val maxRepeats: Int = 2,
    /** Direct mode: an "Again" card returns after this many other items. */
    val repeatGap: Int = 4,
    /** Share of new cards taken from the easiest remaining difficulty level. */
    val easiestFirstShare: Double = 0.75,
)

enum class ItemKind { FLIP, MCQ }

data class SessionItem(
    val card: CardDef,
    val kind: ItemKind,
    val lang: Lang,
    val flash: Flashcard,
    val mcq: Mcq?,
    /** Re-shown after "Again"; does not count towards the required number. */
    val isRepeat: Boolean,
    val isNew: Boolean,
    /** Delay per button, for the labels above the traffic light. */
    val intervals: Map<Rating, Long>,
)

data class AnswerResult(val state: CardStateEntity, val log: ReviewLogEntity, val mastery: Mastery)

class SessionEngine(
    private val catalog: Catalog,
    private val presenter: Presenter,
    private val fsrs: Fsrs,
    initialStates: Map<String, CardStateEntity>,
    val mode: SessionMode,
    newIntroducedToday: Int,
    private val config: SessionConfig = SessionConfig(),
    private val rnd: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val states = initialStates.toMutableMap()
    private val usedNotes = mutableSetOf<String>()
    private val repeatCounts = mutableMapOf<String, Int>()
    private val repeatQueue = ArrayDeque<Pair<CardDef, Int>>() // card, served-index after which it may return
    private var newToday = newIntroducedToday
    private var served = 0

    /** Answers that counted towards the requirement (repeats excluded). */
    var counted = 0
        private set

    val required: Int? get() = (mode as? SessionMode.Challenge)?.required
    val isComplete: Boolean get() = required?.let { counted >= it } ?: false

    fun stateOf(cardId: String): CardStateEntity? = states[cardId]

    fun next(): SessionItem? {
        val req = required
        val item = when {
            // Challenge: exactly the required number of answers, then the way is free.
            req != null && counted >= req -> null
            req != null -> pickFresh()
            else -> {
                val ready = if (req == null) repeatQueue.firstOrNull { it.second <= served } else null
                if (ready != null) {
                    repeatQueue.remove(ready)
                    make(ready.first, mcq = false, repeat = true)
                } else {
                    pickFresh() ?: repeatQueue.removeFirstOrNull()?.let { make(it.first, mcq = false, repeat = true) }
                }
            }
        }
        if (item != null) served++
        return item
    }

    fun answer(item: SessionItem, rating: Rating, durationMs: Long, sessionId: Long): AnswerResult {
        val now = clock()
        val prev = states[item.card.id]
        val srs = fsrs.review(prev?.toSrs() ?: SrsState(), rating, now)
        val lang = if (item.card is BiCard) item.lang.name else prev?.lastLang
        val entity = CardStateEntity.from(item.card.id, item.card.deckId, srs, lang, prev?.firstSeen ?: now)
        states[item.card.id] = entity
        if (!item.isRepeat) counted++
        // "Again" cards come back later in *direct* study; in a challenge FSRS re-schedules
        // them (due in 1 min) so they show up in the next session instead of prolonging this one.
        if (rating == Rating.AGAIN && mode is SessionMode.Direct) {
            val n = repeatCounts.getOrDefault(item.card.id, 0)
            if (n < config.maxRepeats) {
                repeatCounts[item.card.id] = n + 1
                repeatQueue.addLast(item.card to served + config.repeatGap)
            }
        }
        val mastery = Mastery.of(srs)
        val log = ReviewLogEntity(
            cardId = item.card.id, deckId = item.card.deckId, sessionId = sessionId, rating = rating.v,
            mode = item.kind.name, timestamp = now, durationMs = durationMs,
            stabilityAfter = srs.stability, masteryAfter = mastery.name,
        )
        return AnswerResult(entity, log, mastery)
    }

    // ------------------------------------------------------------------

    private fun deckOrder(): List<String> {
        val m = mode
        if (m is SessionMode.Direct && m.deckId != null) return listOf(m.deckId)
        val decks = catalog.decks.filter { !catalog.byDeck[it.id].isNullOrEmpty() }
        val total = decks.sumOf { it.weight }
        var roll = rnd.nextDouble() * total
        val first = decks.firstOrNull { roll -= it.weight; roll < 0 } ?: decks.last()
        return listOf(first.id) + decks.filter { it.id != first.id }.shuffled(rnd).map { it.id }
    }

    private fun pickFresh(): SessionItem? {
        for (deck in deckOrder()) pickFromDeck(deck)?.let { return it }
        return null
    }

    private fun pickFromDeck(deckId: String): SessionItem? {
        val now = clock()
        val cands = catalog.byDeck[deckId].orEmpty().filter { it.noteId !in usedNotes }
        if (cands.isEmpty()) return null
        fun st(c: CardDef) = states[c.id]

        // 1) multiple choice on well-learned cards
        val mastered = cands.filter { Mastery.of(st(it)?.toSrs()) >= Mastery.GEFESTIGT }
        if (mastered.isNotEmpty() && rnd.nextDouble() < config.mcqShare) {
            val c = mastered.filter { st(it)!!.due <= now }.minByOrNull { st(it)!!.due } ?: mastered.random(rnd)
            make(c, mcq = true, repeat = false)?.let { return it }
        }
        // 2) due reviews, most overdue first
        cands.filter { st(it)?.let { s -> s.reps > 0 && s.due <= now } == true }
            .minByOrNull { st(it)!!.due }
            ?.let { return make(it, mcq = false, repeat = false) }
        // 3) new cards (daily cap)
        val fresh = cands.filter { (st(it)?.reps ?: 0) == 0 }
        if (newToday < config.newCardsPerDay && fresh.isNotEmpty()) {
            return make(pickNew(fresh), mcq = false, repeat = false)
        }
        // 4) fallback: weakest known cards, otherwise anything
        val known = cands.filter { (st(it)?.reps ?: 0) > 0 }.sortedBy { st(it)!!.stability }.take(10)
        val pick = if (known.isNotEmpty()) known.random(rnd) else cands.random(rnd)
        return make(pick, mcq = false, repeat = false)
    }

    /**
     * New cards ramp up in difficulty: usually (75 %) one of the easiest unseen ones,
     * sometimes any unseen card so harder material also appears early.
     */
    private fun pickNew(fresh: List<CardDef>): CardDef {
        val easiest = fresh.minOf { it.stufe ?: 2 }
        return if (rnd.nextDouble() < config.easiestFirstShare) fresh.filter { (it.stufe ?: 2) == easiest }.random(rnd)
        else fresh.random(rnd)
    }

    private fun make(card: CardDef, mcq: Boolean, repeat: Boolean): SessionItem? {
        val state = states[card.id]
        val lang = if (state?.lastLang == Lang.EN.name) Lang.ES else Lang.EN
        val q = if (mcq) presenter.mcq(card, lang, rnd) else null
        if (mcq && q == null) return null
        val isNew = (state?.reps ?: 0) == 0
        if (!repeat) {
            usedNotes += card.noteId
            if (isNew) newToday++
        }
        return SessionItem(
            card = card,
            kind = if (q != null) ItemKind.MCQ else ItemKind.FLIP,
            lang = lang,
            flash = presenter.flashcard(card, lang),
            mcq = q,
            isRepeat = repeat,
            isNew = isNew,
            intervals = fsrs.preview(state?.toSrs() ?: SrsState(), clock()),
        )
    }
}
