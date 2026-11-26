package app.momentmal.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- manifest ----------

@Serializable
data class Manifest(val version: Int, val decks: List<DeckRef>)

@Serializable
data class DeckRef(
    val id: String,
    val name: String,
    val file: String,
    val weight: Double,
    val format: String = "bilingual",
)

// ---------- Deutsch ----------

@Serializable
data class Beispiel(val de: String, val es: String)

@Serializable
data class Frage(val id: String, val frage: String, val antwort: String, val distractors: List<String>)

@Serializable
sealed class DeNote {
    abstract val id: String
    abstract val tags: List<String>
    abstract val directions: List<String>
    abstract val notes: String?
    /** 1 leicht · 2 mittel · 3 schwer (optional; new cards are introduced easiest first). */
    abstract val stufe: Int?
}

@Serializable @SerialName("nomen")
data class Nomen(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val wort: String, val artikel: String, val plural: String? = null, val genitiv: String? = null,
    val es: String, val beispiel: Beispiel? = null,
) : DeNote() {
    val mitArtikel get() = "$artikel $wort"
}

@Serializable @SerialName("verb")
data class Verb(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val infinitiv: String, val es: String, val praesens: String, val praeteritum: String, val perfekt: String,
    val rektion: String? = null, val beispiel: Beispiel? = null,
) : DeNote()

@Serializable @SerialName("verb_praep")
data class VerbPraep(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val verb: String, val praep: String, val kasus: String, val es: String, val beispiel: Beispiel? = null,
) : DeNote()

@Serializable @SerialName("adjektiv")
data class Adjektiv(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val wort: String, val es: String, val komparativ: String? = null, val superlativ: String? = null,
    val beispiel: Beispiel? = null,
) : DeNote()

@Serializable @SerialName("ausdruck")
data class Ausdruck(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val de: String, val es: String, val register: String, val beispiel: Beispiel? = null,
) : DeNote()

@Serializable @SerialName("redewendung")
data class Redewendung(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val de: String, val woertlich: String, val es: String, val beispiel: Beispiel? = null,
) : DeNote()

@Serializable @SerialName("satz")
data class Satz(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val de: String, val es: String,
) : DeNote()

/** Shared shape of deklination + grammatik cloze notes. */
interface Cloze {
    val satz: String
    val loesung: List<String>
    val distractors: List<List<String>>
    val erklaerung: String
    val es: String
    val label: String
}

@Serializable @SerialName("deklination")
data class Deklination(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    override val satz: String, override val loesung: List<String>, val kasus: String,
    override val distractors: List<List<String>>, override val erklaerung: String, override val es: String,
) : DeNote(), Cloze {
    override val label get() = "Deklination · $kasus"
}

@Serializable @SerialName("grammatik")
data class Grammatik(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val thema: String, override val satz: String, override val loesung: List<String>,
    override val distractors: List<List<String>>, override val erklaerung: String, override val es: String,
) : DeNote(), Cloze {
    override val label get() = "Grammatik · $thema"
}

@Serializable @SerialName("lesetext")
data class Lesetext(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val titel: String, val text: String, val es: String, val fragen: List<Frage>,
) : DeNote()

@Serializable @SerialName("grammatik_frage")
data class GrammatikFrage(
    override val id: String, override val tags: List<String>, override val directions: List<String>,
    override val notes: String? = null, override val stufe: Int? = null,
    val thema: String, val frage: String, val antwort: String, val kurz: String,
    val distractors: List<String>, val beispiel: Beispiel? = null,
) : DeNote()

@Serializable
data class DeDeckFile(val deck: String, val notes: List<DeNote>)

// ---------- bilingual (systemdesign, interview, future decks) ----------

@Serializable
data class BiSide(val frage: String, val antwort: String, val kurz: String, val details: String? = null)

@Serializable
data class BiNote(
    val id: String, val type: String, val tags: List<String>, val directions: List<String>,
    val notes: String? = null, val stufe: Int? = null, val en: BiSide, val es: BiSide,
) {
    fun side(lang: Lang) = if (lang == Lang.EN) en else es
}

@Serializable
data class BiDeckFile(val deck: String, val notes: List<BiNote>)

enum class Lang { EN, ES }
