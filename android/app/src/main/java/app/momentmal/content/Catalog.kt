package app.momentmal.content

import kotlinx.serialization.json.Json

/** One reviewable unit. Progress is keyed by [id] = "<noteId>#<direction>". */
sealed interface CardDef {
    val id: String
    val deckId: String
    val noteId: String
    val tags: List<String>
    /** Difficulty 1–3 from the content (null = unknown, treated as 2). */
    val stufe: Int?
}

/** direction: "de-es", "es-de", "artikel", "cloze" or a Lesetext frage id ("q1"…). */
data class DeCard(
    override val id: String,
    override val deckId: String,
    val note: DeNote,
    val direction: String,
    val frage: Frage? = null,
) : CardDef {
    override val noteId get() = note.id
    override val tags get() = note.tags
    override val stufe get() = note.stufe
}

data class BiCard(
    override val id: String,
    override val deckId: String,
    val note: BiNote,
) : CardDef {
    override val noteId get() = note.id
    override val tags get() = note.tags
    override val stufe get() = note.stufe
}

class Catalog(val manifest: Manifest, val cards: List<CardDef>) {
    val decks: List<DeckRef> get() = manifest.decks
    val byId: Map<String, CardDef> = cards.associateBy { it.id }
    val byDeck: Map<String, List<CardDef>> = cards.groupBy { it.deckId }
    fun deck(id: String) = decks.first { it.id == id }

    companion object {
        val json = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

        /** [readFile] resolves a path relative to the content root (e.g. "decks/deutsch.json"). */
        fun load(readFile: (String) -> String): Catalog {
            val manifest = json.decodeFromString<Manifest>(readFile("manifest.json"))
            val cards = manifest.decks.flatMap { ref ->
                val text = readFile(ref.file)
                if (ref.format == "deutsch") {
                    json.decodeFromString<DeDeckFile>(text).notes.flatMap { expandDe(ref.id, it) }
                } else {
                    json.decodeFromString<BiDeckFile>(text).notes.map { BiCard("${it.id}#frage", ref.id, it) }
                }
            }
            return Catalog(manifest, cards)
        }

        fun expandDe(deckId: String, note: DeNote): List<DeCard> {
            val out = mutableListOf<DeCard>()
            for (dir in note.directions) {
                when (dir) {
                    "fragen" -> if (note is Lesetext) note.fragen.forEach {
                        out += DeCard("${note.id}#${it.id}", deckId, note, it.id, it)
                    }
                    else -> out += DeCard("${note.id}#$dir", deckId, note, dir)
                }
            }
            if (note is Nomen) out += DeCard("${note.id}#artikel", deckId, note, "artikel")
            return out
        }
    }
}
