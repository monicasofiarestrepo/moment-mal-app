package app.momentmal.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Rating(val v: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class Phase { NEW, LEARNING, REVIEW, RELEARNING }

/** Scheduling state of one card. Times in epoch millis. */
data class SrsState(
    val phase: Phase = Phase.NEW,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0,
    val lastReview: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val step: Int = 0,
    val consecutiveGreens: Int = 0,
)

/**
 * FSRS-5 (the algorithm used by modern Anki) with Anki-style learning steps.
 * https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-Algorithm
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_W,
    private val desiredRetention: Double = 0.9,
    private val learningSteps: List<Long> = listOf(1 * MIN, 10 * MIN),
    private val relearningSteps: List<Long> = listOf(10 * MIN),
    private val maxIntervalDays: Int = 3650,
) {
    fun retrievability(elapsedDays: Double, s: Double) = (1 + FACTOR * elapsedDays / s).pow(DECAY)

    private fun initS(r: Rating) = max(w[r.v - 1], 0.1)
    private fun initD(r: Rating) = (w[4] - exp(w[5] * (r.v - 1)) + 1).coerceIn(1.0, 10.0)

    private fun nextD(d: Double, r: Rating): Double {
        val delta = -w[6] * (r.v - 3)
        val damped = d + delta * (10 - d) / 9
        return (w[7] * initD(Rating.EASY) + (1 - w[7]) * damped).coerceIn(1.0, 10.0)
    }

    private fun recallS(d: Double, s: Double, rr: Double, r: Rating): Double {
        val hard = if (r == Rating.HARD) w[15] else 1.0
        val easy = if (r == Rating.EASY) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - rr)) - 1) * hard * easy)
    }

    private fun forgetS(d: Double, s: Double, rr: Double): Double =
        min(w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - rr)), s)

    private fun shortTermS(s: Double, r: Rating) = s * exp(w[17] * (r.v - 3 + w[18]))

    fun intervalDays(s: Double): Int {
        val i = s / FACTOR * (desiredRetention.pow(1 / DECAY) - 1)
        return i.roundToLong().toInt().coerceIn(1, maxIntervalDays)
    }

    fun review(st: SrsState, r: Rating, now: Long): SrsState {
        // --- memory model ---
        val (s, d) = if (st.phase == Phase.NEW || st.lastReview == null) {
            initS(r) to initD(r)
        } else {
            val elapsed = (now - st.lastReview).coerceAtLeast(0) / DAY.toDouble()
            val s = when {
                elapsed < 1.0 -> shortTermS(st.stability, r)
                r == Rating.AGAIN -> forgetS(st.difficulty, st.stability, retrievability(elapsed, st.stability))
                else -> recallS(st.difficulty, st.stability, retrievability(elapsed, st.stability), r)
            }
            s.coerceAtLeast(0.01) to nextD(st.difficulty, r)
        }

        val greens = when (r) {
            Rating.GOOD, Rating.EASY -> st.consecutiveGreens + 1
            Rating.AGAIN -> 0
            Rating.HARD -> st.consecutiveGreens
        }
        val base = st.copy(stability = s, difficulty = d, lastReview = now, reps = st.reps + 1, consecutiveGreens = greens)

        // --- scheduling ---
        fun graduate() = base.copy(phase = Phase.REVIEW, step = 0, due = now + intervalDays(s) * DAY)
        return when (st.phase) {
            Phase.NEW, Phase.LEARNING -> when (r) {
                Rating.AGAIN -> base.copy(phase = Phase.LEARNING, step = 0, due = now + learningSteps[0])
                Rating.HARD -> base.copy(phase = Phase.LEARNING, step = st.step, due = now + hardDelay(learningSteps, st.step))
                Rating.GOOD -> if (st.step + 1 >= learningSteps.size) graduate()
                    else base.copy(phase = Phase.LEARNING, step = st.step + 1, due = now + learningSteps[st.step + 1])
                Rating.EASY -> graduate()
            }
            Phase.REVIEW -> when (r) {
                Rating.AGAIN -> base.copy(phase = Phase.RELEARNING, step = 0, lapses = st.lapses + 1, due = now + relearningSteps[0])
                else -> graduate()
            }
            Phase.RELEARNING -> when (r) {
                Rating.AGAIN -> base.copy(step = 0, due = now + relearningSteps[0])
                Rating.HARD -> base.copy(due = now + hardDelay(relearningSteps, st.step))
                else -> graduate()
            }
        }
    }

    private fun hardDelay(steps: List<Long>, step: Int): Long =
        if (step == 0 && steps.size > 1) (steps[0] + steps[1]) / 2 else (steps[step.coerceAtMost(steps.lastIndex)] * 1.5).toLong()

    /** Time until due for each button, for the labels above the traffic light. */
    fun preview(st: SrsState, now: Long): Map<Rating, Long> =
        Rating.entries.associateWith { review(st, it, now).due - now }

    companion object {
        const val MIN = 60_000L
        const val DAY = 86_400_000L
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0
        val DEFAULT_W = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
            1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
        )

        fun formatDelay(ms: Long): String = when {
            ms < 60 * MIN -> "${(ms / MIN).coerceAtLeast(1)} min"
            ms < DAY -> "${ms / (60 * MIN)} h"
            ms < 30 * DAY -> "${ms / DAY} T"
            ms < 365 * DAY -> "${ms / (30 * DAY)} Mon"
            else -> "%.1f J".format(ms / (365.0 * DAY))
        }
    }
}

/** How well a card is learned. Only GEFESTIGT+ cards appear as multiple-choice quizzes. */
enum class Mastery(val label: String) {
    NEU("Neu"), LERNEN("Lernen"), JUNG("Jung"), GEFESTIGT("Gefestigt"), GEMEISTERT("Gemeistert");

    companion object {
        fun of(st: SrsState?): Mastery = when {
            st == null || st.reps == 0 -> NEU
            st.consecutiveGreens >= 3 && st.stability >= 90 -> GEMEISTERT
            st.consecutiveGreens >= 3 && st.stability >= 21 -> GEFESTIGT
            st.consecutiveGreens >= 2 -> JUNG
            else -> LERNEN
        }
    }
}
