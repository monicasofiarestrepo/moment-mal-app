package app.momentmal.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.momentmal.container
import app.momentmal.data.SessionEntity
import app.momentmal.session.ItemKind
import app.momentmal.session.SessionEngine
import app.momentmal.session.SessionItem
import app.momentmal.session.SessionMode
import app.momentmal.srs.Mastery
import app.momentmal.srs.Rating
import app.momentmal.ui.start.startOfToday
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudyUi(
    val loading: Boolean = true,
    val item: SessionItem? = null,
    val revealed: Boolean = false,
    /** MCQ: index the user picked. */
    val chosen: Int? = null,
    val counted: Int = 0,
    val required: Int? = null,
    /** Mastery reached by the last answered card (shown briefly as feedback). */
    val lastMastery: Mastery? = null,
    val finished: Boolean = false,
)

class StudyViewModel(app: Application, private val mode: SessionMode, private val origin: String) : AndroidViewModel(app) {
    private val c = app.container
    private val _ui = MutableStateFlow(StudyUi())
    val ui = _ui.asStateFlow()

    private lateinit var engine: SessionEngine
    private var sessionId = 0L
    private var shownAt = 0L

    init {
        viewModelScope.launch {
            val (catalog, presenter) = c.content()
            val states = c.db.cardStates().all().associateBy { it.cardId }
            val newToday = c.db.cardStates().newSince(startOfToday())
            engine = SessionEngine(catalog, presenter, c.fsrs, states, mode, newToday)
            sessionId = c.db.sessions().insert(
                SessionEntity(
                    origin = origin, deckId = (mode as? SessionMode.Direct)?.deckId,
                    required = engine.required, startedAt = System.currentTimeMillis(),
                ),
            )
            _ui.value = StudyUi(loading = false, required = engine.required)
            advance()
        }
    }

    fun reveal() = _ui.update { it.copy(revealed = true) }

    fun rate(r: Rating) {
        val item = _ui.value.item ?: return
        if (item.kind != ItemKind.FLIP || !_ui.value.revealed) return
        record(item, r)
        advance()
    }

    fun choose(index: Int) {
        val s = _ui.value
        val item = s.item ?: return
        val q = item.mcq ?: return
        if (s.chosen != null) return
        record(item, if (index == q.correct) Rating.GOOD else Rating.AGAIN)
        _ui.update { it.copy(chosen = index, revealed = true) }
    }

    /** After an MCQ answer was shown. */
    fun next() = advance()

    private fun record(item: SessionItem, r: Rating) {
        val res = engine.answer(item, r, System.currentTimeMillis() - shownAt, sessionId)
        _ui.update { it.copy(counted = engine.counted, lastMastery = res.mastery) }
        viewModelScope.launch {
            c.db.cardStates().upsert(res.state)
            c.db.reviews().insert(res.log)
        }
    }

    private fun advance() {
        val item = engine.next()
        shownAt = System.currentTimeMillis()
        _ui.update { it.copy(item = item, revealed = false, chosen = null, finished = item == null) }
        if (item == null) finishSession()
    }

    private fun finishSession() {
        viewModelScope.launch { closeSession() }
    }

    private suspend fun closeSession() = withContext(NonCancellable) {
        if (sessionId == 0L) return@withContext
        val s = c.db.sessions().get(sessionId) ?: return@withContext
        if (s.endedAt != null) return@withContext
        c.db.sessions().update(
            s.copy(
                answered = engine.counted,
                completed = engine.required?.let { engine.counted >= it } ?: false,
                endedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Challenge skipped via the daily skip button. */
    suspend fun markSkipped() = withContext(NonCancellable) {
        val s = c.db.sessions().get(sessionId) ?: return@withContext
        c.db.sessions().update(s.copy(skipped = true, answered = engine.counted, endedAt = System.currentTimeMillis()))
    }

    override fun onCleared() {
        // Direct sessions end when the screen closes.
        if (sessionId != 0L) kotlinx.coroutines.MainScope().launch { closeSession() }
        super.onCleared()
    }
}
