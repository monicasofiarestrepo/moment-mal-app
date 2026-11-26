package app.momentmal.ui.start

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import app.momentmal.ui.settings.onResumeValue
import app.momentmal.ui.settings.protectionReady
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.momentmal.container
import app.momentmal.srs.Mastery
import app.momentmal.ui.theme.MM
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class DeckSummary(val id: String, val name: String, val due: Int, val unseen: Int, val mastery: Map<Mastery, Int>, val total: Int)
data class StartUi(val loading: Boolean = true, val decks: List<DeckSummary> = emptyList(), val answeredToday: Int = 0)

fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

class StartViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val ui = combine(
        flow { emit(c.content().first) },
        c.db.cardStates().observeAll(),
        c.db.reviews().observeCountSince(startOfToday()),
    ) { catalog, states, today ->
        val now = System.currentTimeMillis()
        val byId = states.associateBy { it.cardId }
        StartUi(
            loading = false,
            answeredToday = today,
            decks = catalog.decks.map { d ->
                val cards = catalog.byDeck[d.id].orEmpty()
                val st = cards.map { byId[it.id] }
                DeckSummary(
                    id = d.id, name = d.name,
                    due = st.count { it != null && it.reps > 0 && it.due <= now },
                    unseen = st.count { it == null || it.reps == 0 },
                    mastery = st.groupingBy { Mastery.of(it?.toSrs()) }.eachCount(),
                    total = cards.size,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StartUi())
}

@Composable
fun StartScreen(onStudy: (deckId: String?) -> Unit, onSettings: () -> Unit, onStats: () -> Unit, vm: StartViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 11 -> "Guten Morgen."
        hour < 18 -> "Guten Tag."
        else -> "Guten Abend."
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MM.Bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val ctx = LocalContext.current
        val protectedNow = onResumeValue { protectionReady(ctx) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MOMENT MAL", style = MaterialTheme.typography.labelSmall, color = MM.Sage, modifier = Modifier.weight(1f))
            IconButton(onClick = onStats) { Icon(Icons.Rounded.BarChart, "Statistik", tint = MM.Muted) }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Tune, "Einstellungen", tint = MM.Muted) }
        }
        Text(greeting, style = MaterialTheme.typography.headlineMedium, color = MM.Text)
        Text(
            if (ui.answeredToday == 0) "Was möchtest du heute lernen?" else "Heute schon ${ui.answeredToday} Antworten. Weiter so.",
            style = MaterialTheme.typography.bodyLarge, color = MM.Muted,
        )

        if (!protectedNow) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MM.Yellow.copy(alpha = 0.10f))
                    .border(1.dp, MM.Yellow.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onSettings)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Der Schutz ist noch nicht aktiv.", style = MaterialTheme.typography.bodyMedium, color = MM.Yellow, modifier = Modifier.weight(1f))
                Text("Einrichten", style = MaterialTheme.typography.labelLarge, color = MM.Yellow)
            }
        }

        Spacer(Modifier.height(4.dp))

        // Mixed session (same weights as the blocker: 70/15/15)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MM.Sage.copy(alpha = 0.14f))
                .border(1.dp, MM.Sage.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .clickable { onStudy(null) }
                .padding(20.dp),
        ) {
            Column {
                Text("Gemischt lernen", style = MaterialTheme.typography.titleLarge, color = MM.Sage)
                Text("Alle Themen, Deutsch im Fokus", style = MaterialTheme.typography.bodyMedium, color = MM.Muted)
            }
        }

        Text("THEMEN", style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 8.dp))
        for (d in ui.decks) DeckCard(d) { onStudy(d.id) }

        if (ui.decks.isNotEmpty()) Legend()
    }
}

@Composable
private fun DeckCard(d: DeckSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, color = MM.Text, modifier = Modifier.weight(1f))
            if (d.due > 0) Text("${d.due} fällig", style = MaterialTheme.typography.labelLarge, color = MM.Yellow)
        }
        Text("${d.total} Karten · ${d.unseen} neu", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
        MasteryBar(d.mastery, d.total)
    }
}

@Composable
private fun MasteryBar(counts: Map<Mastery, Int>, total: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MM.mastery(Mastery.NEU)),
    ) {
        for (m in Mastery.entries.reversed()) {
            if (m == Mastery.NEU) continue
            val n = counts[m] ?: 0
            if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxSize().background(MM.mastery(m)))
        }
        val rest = counts[Mastery.NEU] ?: 0
        if (rest > 0 || total == 0) Spacer(Modifier.weight(maxOf(rest, 1).toFloat()))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        for (m in Mastery.entries.reversed()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MM.mastery(m)))
                Spacer(Modifier.width(4.dp))
                Text(m.label, style = MaterialTheme.typography.labelMedium, color = MM.Muted)
            }
        }
    }
}
