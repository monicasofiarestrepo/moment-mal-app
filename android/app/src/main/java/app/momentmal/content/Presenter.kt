package app.momentmal.content

import kotlin.random.Random

enum class BlockStyle { LABEL, BIG, BODY, EXAMPLE_DE, EXAMPLE_ES, MUTED, HINT }

/** A line of card content. [speakDe] = German text the 🔊 button can read. [artikel] colors a leading article. */
data class Block(
    val text: String,
    val style: BlockStyle,
    val speakDe: Boolean = false,
    val artikel: String? = null,
)

data class Flashcard(val front: List<Block>, val back: List<Block>)

data class Mcq(val prompt: List<Block>, val options: List<String>, val correct: Int, val explanation: List<Block>)

/** Turns cards into displayable content. Distractor pools come from the whole catalog. */
class Presenter(private val catalog: Catalog) {

    private val deNotes: Map<String, List<DeNote>> = catalog.cards.filterIsInstance<DeCard>()
        .map { it.note }.distinctBy { it.id }.groupBy { it::class.simpleName!! }

    private inline fun <reified T : DeNote> pool(): List<T> =
        deNotes[T::class.simpleName!!].orEmpty().filterIsInstance<T>()

    // ---------------- flashcards ----------------

    fun flashcard(card: CardDef, lang: Lang): Flashcard = when (card) {
        is BiCard -> biFlash(card, lang)
        is DeCard -> deFlash(card)
    }

    private fun biFlash(card: BiCard, lang: Lang): Flashcard {
        val s = card.note.side(lang)
        val deckName = catalog.deck(card.deckId).name
        return Flashcard(
            front = listOf(Block("$deckName · ${lang.name}", BlockStyle.LABEL), Block(s.frage, BlockStyle.BIG)),
            back = buildList {
                add(Block(s.antwort, BlockStyle.BODY))
                s.details?.let { add(Block(it, BlockStyle.MUTED)) }
            },
        )
    }

    private fun deFlash(card: DeCard): Flashcard {
        val n = card.note
        val toEs = card.direction == "de-es"
        return when (n) {
            is Nomen -> when (card.direction) {
                "artikel" -> Flashcard(
                    front = listOf(label("Welcher Artikel?"), Block("___ ${n.wort}", BlockStyle.BIG)),
                    back = nomenBack(n, showEs = true),
                )
                else -> if (toEs) Flashcard(
                    front = listOf(label("Nomen · Bedeutung?"), Block(n.mitArtikel, BlockStyle.BIG, true, n.artikel)),
                    back = listOf(Block(n.es, BlockStyle.BIG)) + nomenBack(n, showEs = false),
                ) else Flashcard(
                    front = listOf(label("Auf Deutsch – mit Artikel"), Block(n.es, BlockStyle.BIG)),
                    back = nomenBack(n, showEs = false),
                )
            }
            is Verb -> {
                val de = listOfNotNull(
                    Block(n.infinitiv, BlockStyle.BIG, true),
                    Block("${n.praesens} · ${n.praeteritum} · ${n.perfekt}", BlockStyle.BODY, true),
                    n.rektion?.let { Block(it, BlockStyle.MUTED) },
                )
                flip("Verb", toEs, de, n.es, n.beispiel, n.notes)
            }
            is VerbPraep -> {
                val de = listOf(Block("${n.verb} ${n.praep} + ${n.kasus}", BlockStyle.BIG, true))
                flip("Verb + Präposition", toEs, de, n.es, n.beispiel, n.notes)
            }
            is Adjektiv -> {
                val forms = listOfNotNull(n.komparativ, n.superlativ).joinToString(" · ")
                val de = listOfNotNull(
                    Block(n.wort, BlockStyle.BIG, true),
                    forms.takeIf { it.isNotEmpty() }?.let { Block(it, BlockStyle.BODY, true) },
                )
                flip("Adjektiv", toEs, de, n.es, n.beispiel, n.notes)
            }
            is Ausdruck -> flip("Ausdruck · ${n.register}", toEs, listOf(Block(n.de, BlockStyle.BIG, true)), n.es, n.beispiel, n.notes)
            is Redewendung -> {
                val de = listOf(Block(n.de, BlockStyle.BIG, true), Block("wörtlich: ${n.woertlich}", BlockStyle.MUTED))
                flip("Redewendung", toEs, de, n.es, n.beispiel, n.notes)
            }
            is Satz -> flip("Satz", toEs, listOf(Block(n.de, BlockStyle.BIG, true)), n.es, null, n.notes)
            is Deklination, is Grammatik -> {
                val c = n as Cloze
                Flashcard(
                    front = listOf(label(c.label), Block(c.satz, BlockStyle.BIG)),
                    back = clozeBack(c, n.notes),
                )
            }
            is GrammatikFrage -> Flashcard(
                front = listOf(label("Grammatik · ${n.thema}"), Block(n.frage, BlockStyle.BIG)),
                back = buildList {
                    add(Block(n.antwort, BlockStyle.BODY))
                    n.beispiel?.let { addExample(it) }
                    n.notes?.let { add(Block(it, BlockStyle.HINT)) }
                },
            )
            is Lesetext -> {
                val f = card.frage!!
                Flashcard(
                    front = listOf(label("Lesen · ${n.titel}"), Block(n.text, BlockStyle.BODY, true), Block(f.frage, BlockStyle.BIG)),
                    back = listOf(Block(f.antwort, BlockStyle.BIG, true), Block(n.es, BlockStyle.MUTED)),
                )
            }
        }
    }

    private fun label(t: String) = Block(t, BlockStyle.LABEL)

    private fun nomenBack(n: Nomen, showEs: Boolean) = buildList {
        add(Block(n.mitArtikel, BlockStyle.BIG, true, n.artikel))
        if (showEs) add(Block(n.es, BlockStyle.BODY))
        val forms = listOfNotNull(n.plural?.let { "Pl. $it" } ?: "nur Singular", n.genitiv?.let { "Gen. $it" })
        add(Block(forms.joinToString(" · "), BlockStyle.MUTED))
        n.beispiel?.let { addExample(it) }
        n.notes?.let { add(Block(it, BlockStyle.HINT)) }
    }

    private fun MutableList<Block>.addExample(b: Beispiel) {
        add(Block(b.de, BlockStyle.EXAMPLE_DE, true))
        add(Block(b.es, BlockStyle.EXAMPLE_ES))
    }

    /** Generic two-way card: German side vs Spanish meaning. */
    private fun flip(kind: String, toEs: Boolean, de: List<Block>, es: String, beispiel: Beispiel?, notes: String?): Flashcard {
        val esBlock = Block(es, BlockStyle.BIG)
        val extra = buildList {
            beispiel?.let { addExample(it) }
            notes?.let { add(Block(it, BlockStyle.HINT)) }
        }
        return if (toEs) Flashcard(listOf(label("$kind · Bedeutung?")) + de, listOf(esBlock) + extra)
        else Flashcard(listOf(label("$kind · Auf Deutsch?"), esBlock), de + extra)
    }

    private fun clozeBack(c: Cloze, notes: String?) = buildList {
        add(Block(fill(c.satz, c.loesung), BlockStyle.BIG, true))
        add(Block(c.erklaerung, BlockStyle.BODY))
        add(Block(c.es, BlockStyle.MUTED))
        notes?.let { add(Block(it, BlockStyle.HINT)) }
    }

    // ---------------- multiple choice ----------------

    /** Returns null when no sensible MCQ can be built (caller falls back to a flashcard). */
    fun mcq(card: CardDef, lang: Lang, rnd: Random = Random.Default): Mcq? {
        val flash = flashcard(card, lang)
        return when (card) {
            is BiCard -> {
                val s = card.note.side(lang)
                val others = catalog.byDeck[card.deckId].orEmpty().filterIsInstance<BiCard>()
                    .filter { it.note.id != card.note.id }
                val sameTag = others.filter { o -> o.note.tags.any { it in card.note.tags } }
                val picks = pickDistinct((sameTag.shuffled(rnd) + others.shuffled(rnd)).map { it.note.side(lang).kurz }, s.kurz, 3)
                build(listOf(Block(s.frage, BlockStyle.BIG)), s.kurz, picks, flash.back, rnd)
            }
            is DeCard -> deMcq(card, flash, rnd)
        }
    }

    private fun deMcq(card: DeCard, flash: Flashcard, rnd: Random): Mcq? {
        val n = card.note
        val toEs = card.direction == "de-es"
        fun <T : DeNote> others(list: List<T>) = list.filter { it.id != n.id }.shuffled(rnd)
        return when (n) {
            is Nomen -> when {
                card.direction == "artikel" -> {
                    val opts = listOf("der", "die", "das")
                    Mcq(flash.front, opts, opts.indexOf(n.artikel), flash.back)
                }
                toEs -> build(flash.front, n.es, pickDistinct(others(pool<Nomen>()).map { it.es }, n.es, 3), flash.back, rnd)
                else -> {
                    // emphasis on the article: two wrong-article variants + one other noun
                    val wrongArt = listOf("der", "die", "das").filter { it != n.artikel }.map { "$it ${n.wort}" }
                    val other = others(pool<Nomen>()).first().mitArtikel
                    build(flash.front, n.mitArtikel, wrongArt + other, flash.back, rnd)
                }
            }
            is VerbPraep -> {
                val right = "${n.praep} + ${n.kasus}"
                val combos = PREPS.flatMap { p -> listOf("$p + Akkusativ", "$p + Dativ") }
                val prompt = listOf(label("Welche Präposition?"), Block("${n.verb} ___", BlockStyle.BIG), Block(n.es, BlockStyle.MUTED))
                // prefer same-preposition-wrong-case + common confusions
                val sameP = "${n.praep} + ${if (n.kasus == "Akkusativ") "Dativ" else "Akkusativ"}"
                build(prompt, right, pickDistinct(listOf(sameP) + combos.shuffled(rnd), right, 3), flash.back, rnd)
            }
            is Verb -> twoWay(flash, toEs, n.es, n.infinitiv, others(pool<Verb>()).map { it.es to it.infinitiv }, rnd)
            is Adjektiv -> twoWay(flash, toEs, n.es, n.wort, others(pool<Adjektiv>()).map { it.es to it.wort }, rnd)
            is Ausdruck -> twoWay(flash, toEs, n.es, n.de, others(pool<Ausdruck>()).map { it.es to it.de }, rnd)
            is Redewendung -> twoWay(flash, toEs, n.es, n.de, others(pool<Redewendung>()).map { it.es to it.de }, rnd)
            is Satz -> twoWay(flash, toEs, n.es, n.de, others(pool<Satz>()).map { it.es to it.de }, rnd)
            is Deklination, is Grammatik -> {
                val c = n as Cloze
                build(flash.front, c.loesung.joinToString(" … "), c.distractors.map { it.joinToString(" … ") }, flash.back, rnd)
            }
            is Lesetext -> {
                val f = card.frage!!
                build(flash.front, f.antwort, f.distractors, flash.back, rnd)
            }
            is GrammatikFrage -> build(flash.front, n.kurz, n.distractors, flash.back, rnd)
        }
    }

    /** pairs = (es, de) of other notes of the same type. */
    private fun twoWay(flash: Flashcard, toEs: Boolean, es: String, de: String, pairs: List<Pair<String, String>>, rnd: Random): Mcq? {
        val right = if (toEs) es else de
        val pool = pairs.map { if (toEs) it.first else it.second }
        return build(flash.front, right, pickDistinct(pool, right, 3), flash.back, rnd)
    }

    private fun build(prompt: List<Block>, right: String, wrong: List<String>, explanation: List<Block>, rnd: Random): Mcq? {
        if (wrong.size < 2) return null
        val opts = (wrong.take(3) + right).shuffled(rnd)
        return Mcq(prompt, opts, opts.indexOf(right), explanation)
    }

    private fun pickDistinct(candidates: List<String>, right: String, n: Int) =
        candidates.filter { it != right }.distinct().take(n)

    companion object {
        val PREPS = listOf("auf", "an", "über", "für", "mit", "von", "um", "vor", "bei", "nach", "zu", "unter", "in")

        fun fill(satz: String, loesung: List<String>): String {
            var out = satz
            for (l in loesung) out = out.replaceFirst("___", l)
            return out
        }
    }
}
