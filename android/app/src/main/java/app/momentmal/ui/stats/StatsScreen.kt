package app.momentmal.ui.stats

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.momentmal.blocker.LimitRules
import app.momentmal.container
import app.momentmal.srs.Mastery
import app.momentmal.stats.StatsCalc
import app.momentmal.ui.settings.ScreenScaffold
import app.momentmal.ui.settings.onResumeValue
import app.momentmal.ui.theme.MM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class DeckStats(val name: String, val mastery: Map<Mastery, Int>, val total: Int, val accuracy30: Int?)
data class Bar(val label: String, val value: Long)

data class StatsUi(
    val loading: Boolean = true,
    val streak: Int = 0,
    val answeredToday: Int = 0,
    val baselineMin: Int? = null,
    val savedTotalMs: Long = 0,
    val usageTodayMs: Long = 0,
    val usage7: List<Bar> = emptyList(),
    val challenges7: Int = 0,
    val skipped7: Int = 0,
    val abandoned7: Int = 0,
    val protectionOff7: Int = 0,
    val decks: List<DeckStats> = emptyList(),
    val forecast: List<Bar> = emptyList(),
)

class StatsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _ui = MutableStateFlow(StatsUi())
    val ui = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = withContext(Dispatchers.IO) { compute() }
    }

    fun setBaseline(min: Int) = viewModelScope.launch { c.stats.setBaselineMin(min); refresh() }

    /** From Android usage data (needs "Nutzungsdaten" access). Returns false if not possible. */
    fun computeBaseline(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val min = withContext(Dispatchers.IO) { c.stats.computeBaselineMin(c.blockerPrefs.current().blocked) }
        if (min != null) setBaseline(min)
        onResult(min != null)
    }

    private suspend fun compute(): StatsUi {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val now = System.currentTimeMillis()
        val (catalog, _) = c.content()
        val snap = c.blockerPrefs.current()

        val reviews = c.db.reviews().all()
        val perDay = reviews.groupingBy { day(it.timestamp) }.eachCount()
        val streak = StatsCalc.streak(perDay, snap.pauseDays.map(LocalDate::parse).toSet(), today)

        val usage = c.db.usage().all().groupBy { LocalDate.parse(it.day) }.mapValues { (_, v) -> v.sumOf { it.ms } }
        val baseline = c.stats.baselineMin()
        val install = day(getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).firstInstallTime)
        val saved = baseline?.let { StatsCalc.savedMs(it * 60_000L, usage, install, today) } ?: 0

        val usage7 = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            Bar(if (back == 0) "Heute" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2), usage[d] ?: 0)
        }

        val weekAgo = now - 7 * 86_400_000L
        val challenges = c.db.sessions().since(weekAgo).filter { it.origin != "direct" && !it.origin.startsWith("protect:") }

        val states = c.db.cardStates().all().associateBy { it.cardId }
        val since30 = now - 30 * 86_400_000L
        val recent = reviews.filter { it.timestamp >= since30 }.groupBy { it.deckId }
        val decks = catalog.decks.map { d ->
            val cards = catalog.byDeck[d.id].orEmpty()
            val r = recent[d.id].orEmpty()
            DeckStats(
                d.name,
                cards.groupingBy { Mastery.of(states[it.id]?.toSrs()) }.eachCount(),
                cards.size,
                if (r.isEmpty()) null else r.count { it.rating >= 3 } * 100 / r.size,
            )
        }

        val forecast = (0..6).map { i ->
            val d = today.plusDays(i.toLong())
            val n = states.values.count { s ->
                s.reps > 0 && catalog.byId.containsKey(s.cardId) && day(s.due).let { if (i == 0) !it.isAfter(d) else it == d }
            }
            Bar(if (i == 0) "Heute" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2), n.toLong())
        }

        return StatsUi(
            loading = false, streak = streak, answeredToday = perDay[today] ?: 0,
            baselineMin = baseline, savedTotalMs = saved, usageTodayMs = usage[today] ?: 0, usage7 = usage7,
            challenges7 = challenges.size, skipped7 = challenges.count { it.skipped },
            abandoned7 = challenges.count { !it.completed && !it.skipped },
            protectionOff7 = snap.protectionOffDays.map(LocalDate::parse).count { !it.isBefore(today.minusDays(6)) },
            decks = decks, forecast = forecast,
        )
    }
}

@Composable
fun StatsScreen(onBack: () -> Unit, vm: StatsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // returning from the usage-access settings: try computing the baseline
    val access = onResumeValue { ctx.container.stats.hasUsageAccess() }
    var computeMsg by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("Statistik", onBack) {
        if (ui.loading) return@ScreenScaffold
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Modifier.weight(1f), "SERIE", "${ui.streak}", if (ui.streak == 1) "Tag" else "Tage",
                    if (ui.answeredToday >= StatsCalc.STREAK_MIN_ANSWERS) "Heute geschafft" else "Heute ${ui.answeredToday}/${StatsCalc.STREAK_MIN_ANSWERS}")
                val savedMin = ui.savedTotalMs / 60_000
                Tile(Modifier.weight(1f), "ZEIT GESPART",
                    when { ui.baselineMin == null -> "–"; savedMin < 60 -> "$savedMin"; else -> "${savedMin / 60}" },
                    when { ui.baselineMin == null -> ""; savedMin < 60 -> "min"; else -> "h ${savedMin % 60} min" },
                    if (ui.baselineMin == null) "Vergleichswert fehlt" else "seit Installation")
            }

            Section("SOCIAL MEDIA · LETZTE 7 TAGE")
            BarChart(ui.usage7, reference = ui.baselineMin?.let { it * 60_000L }, format = { LimitRules.formatDuration(it) })
            ui.baselineMin?.let {
                Text("Gestrichelt: vorher Ø ${LimitRules.formatDuration(it * 60_000L)} am Tag", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
            }

            BaselineCard(ui.baselineMin, access, computeMsg,
                onCompute = {
                    if (!access) ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    else vm.computeBaseline { ok -> computeMsg = if (ok) "Aus deinen Nutzungsdaten berechnet." else "Keine Nutzungsdaten gefunden – stell ihn von Hand ein." }
                },
                onSet = vm::setBaseline,
            )

            Section("QUIZ-SPERRE · 7 TAGE")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mini(Modifier.weight(1f), ui.challenges7 - ui.skipped7 - ui.abandoned7, "gemacht")
                Mini(Modifier.weight(1f), ui.abandoned7, "verzichtet")
                Mini(Modifier.weight(1f), ui.skipped7, "übersprungen")
            }
            if (ui.protectionOff7 > 0) Text(
                "Der Schutz war an ${ui.protectionOff7} ${if (ui.protectionOff7 == 1) "Tag" else "Tagen"} ausgeschaltet.",
                style = MaterialTheme.typography.bodySmall, color = MM.Yellow,
            )

            Section("THEMEN")
            ui.decks.forEach { DeckRow(it) }
            MasteryLegend()

            Section("FÄLLIG · NÄCHSTE 7 TAGE")
            BarChart(ui.forecast, reference = null, format = { "$it Karten" })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(t: String) =
    Text(t, style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun Tile(modifier: Modifier, label: String, value: String, unit: String, sub: String) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MM.Surface).border(1.dp, MM.Border, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MM.Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall, color = MM.Text)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodyLarge, color = MM.Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MM.Muted)
    }
}

@Composable
private fun Mini(modifier: Modifier, n: Int, label: String) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MM.Surface).border(1.dp, MM.Border, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Text("$n", style = MaterialTheme.typography.headlineSmall, color = MM.Text)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MM.Muted)
    }
}

/**
 * Single-series bar chart: thin sage bars with rounded tops anchored to the baseline,
 * optional dashed reference line, tap a bar to read its value (selected bar stays highlighted).
 */
@Composable
private fun BarChart(bars: List<Bar>, reference: Long?, format: (Long) -> String) {
    var selected by remember(bars) { mutableStateOf(bars.lastIndex) }
    val max = maxOf(bars.maxOfOrNull { it.value } ?: 0, reference ?: 0, 1)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        bars.getOrNull(selected)?.let {
            Text("${it.label}: ${format(it.value)}", style = MaterialTheme.typography.bodyMedium, color = MM.Text)
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier.fillMaxWidth().height(120.dp).pointerInput(bars) {
                detectTapGestures { o -> selected = (o.x / (size.width / bars.size)).toInt().coerceIn(0, bars.lastIndex) }
            },
        ) {
            val slot = size.width / bars.size
            val w = minOf(slot * 0.5f, 28.dp.toPx())
            // baseline
            drawLine(MM.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            bars.forEachIndexed { i, b ->
                val h = size.height * b.value / max
                if (h > 0) drawRoundRect(
                    color = if (i == selected) MM.Sage else MM.Sage.copy(alpha = 0.45f),
                    topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            reference?.let {
                val y = size.height - size.height * it / max
                drawLine(MM.Muted, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            bars.forEachIndexed { i, b ->
                Text(b.label, style = MaterialTheme.typography.labelMedium, color = if (i == selected) MM.Text else MM.Muted,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DeckRow(d: DeckStats) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(d.name, style = MaterialTheme.typography.titleMedium, color = MM.Text, modifier = Modifier.weight(1f))
            Text(d.accuracy30?.let { "$it % richtig (30 T)" } ?: "noch keine Antworten", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
        }
        // ordinal levels → one hue, dark→light; 2dp surface gap between segments
        Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (m in Mastery.entries.reversed()) {
                val n = d.mastery[m] ?: 0
                if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(MM.mastery(m)))
            }
        }
        val learned = (d.mastery[Mastery.GEFESTIGT] ?: 0) + (d.mastery[Mastery.GEMEISTERT] ?: 0)
        Text("$learned von ${d.total} gefestigt", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MasteryLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in Mastery.entries.reversed()) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MM.mastery(m)))
            Spacer(Modifier.width(4.dp))
            Text(m.label, style = MaterialTheme.typography.labelMedium, color = MM.Muted)
        }
    }
}

@Composable
private fun BaselineCard(baseline: Int?, access: Boolean, msg: String?, onCompute: () -> Unit, onSet: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Vergleichswert „vorher“", style = MaterialTheme.typography.titleMedium, color = MM.Text)
        Text(
            "Wie viel Zeit hast du vor Moment mal täglich in diesen Apps verbracht? Daraus wird die gesparte Zeit berechnet.",
            style = MaterialTheme.typography.bodySmall, color = MM.Muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSet(((baseline ?: 60) - 5).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Remove, "weniger", tint = MM.Muted) }
            Text(baseline?.let { "Ø " + LimitRules.formatDuration(it * 60_000L) + " / Tag" } ?: "nicht gesetzt",
                style = MaterialTheme.typography.titleMedium, color = MM.Sage, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onSet((baseline ?: 55) + 5) }) { Icon(Icons.Rounded.Add, "mehr", tint = MM.Muted) }
        }
        Text(
            if (access) "Aus Nutzungsdaten berechnen" else "Nutzungsdaten erlauben & berechnen",
            style = MaterialTheme.typography.labelLarge, color = MM.Sage,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCompute).padding(vertical = 6.dp, horizontal = 4.dp),
        )
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MM.Muted) }
    }
}
