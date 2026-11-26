package app.momentmal.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.momentmal.blocker.BlockerPrefs
import app.momentmal.blocker.LimitRules
import app.momentmal.blocker.WebRules
import app.momentmal.blocker.StagedChange
import app.momentmal.blocker.Strict
import app.momentmal.blocker.StrictRules
import app.momentmal.container
import app.momentmal.ui.study.PrimaryButton
import app.momentmal.ui.theme.CardText
import app.momentmal.ui.theme.MM
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

// ------------------------------------------------------------------ limits + today's usage

@Composable
fun LimitsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.container.blockerPrefs
    val snap by prefs.snapshot.collectAsState(initial = null)
    val usage by remember { ctx.container.db.usage().observeDay(LocalDate.now().toString()) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val s = snap ?: return

    ScreenScaffold("Limits", onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Über dem Limit kostet jede App ${LimitRules.OVER_LIMIT} Fragen statt ${LimitRules.BASE}, " +
                    "plus ${LimitRules.PER_EXTRA_BLOCK} für jede weiteren 2 Stunden. Nach der freien Zeit (je App) " +
                    "kommt das Quiz dann auch mitten in der App.",
                style = MaterialTheme.typography.bodyMedium, color = MM.Muted,
            )
            val change: (Int, Int) -> Unit = { app, total ->
                scope.launch {
                    if (s.strict && StrictRules.isRelaxing(s.appLimitMin, s.totalLimitMin, app, total))
                        Strict.requestRelax(ctx, StagedChange.Limits(app, total))
                    else prefs.setLimits(app, total)
                }
            }
            Stepper("Pro App", s.appLimitMin) { change(it, s.totalLimitMin) }
            Stepper("Insgesamt", s.totalLimitMin) { change(s.appLimitMin, it) }
            if (s.pending.appLimitMin != null || s.pending.totalLimitMin != null) Text(
                "Ab morgen: ${s.pending.appLimitMin ?: s.appLimitMin} Min. pro App · " +
                    LimitRules.formatDuration((s.pending.totalLimitMin ?: s.totalLimitMin) * 60_000L) + " insgesamt",
                style = MaterialTheme.typography.bodySmall, color = MM.Yellow,
            )
            if (s.strict) Text(
                "Strenger Modus: höher stellen kostet ${StrictRules.CONFIRM_QUESTIONS} Fragen und gilt ab morgen. Niedriger gilt sofort.",
                style = MaterialTheme.typography.bodySmall, color = MM.Muted,
            )

            val total = usage.sumOf { it.ms }
            Text("HEUTE", style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 12.dp))
            UsageBar("Insgesamt", total, s.totalLimitMs)
            usage.sortedByDescending { it.ms }.forEach { u ->
                UsageBar(labelFor(ctx, u.usageKey), u.ms, s.appLimitMs)
            }
            if (usage.isEmpty()) Text("Noch nichts. Schön.", style = MaterialTheme.typography.bodyMedium, color = MM.Muted)
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun labelFor(ctx: android.content.Context, key: String): String =
    if (key.startsWith(WebRules.WEB_PREFIX)) key.removePrefix(WebRules.WEB_PREFIX)
    else runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(key, 0)).toString() }.getOrDefault(key)

@Composable
private fun Stepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MM.Text, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((minutes - 5).coerceAtLeast(5)) }) { Icon(Icons.Rounded.Remove, "weniger", tint = MM.Muted) }
        Text(LimitRules.formatDuration(minutes * 60_000L), style = MaterialTheme.typography.titleMedium, color = MM.Sage,
            modifier = Modifier.width(84.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((minutes + 5).coerceAtMost(600)) }) { Icon(Icons.Rounded.Add, "mehr", tint = MM.Muted) }
    }
}

@Composable
private fun UsageBar(label: String, ms: Long, limitMs: Long) {
    val frac = if (limitMs > 0) (ms.toFloat() / limitMs).coerceIn(0f, 1f) else 0f
    val over = ms > limitMs
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MM.Text, modifier = Modifier.weight(1f))
            Text(
                "${LimitRules.formatDuration(ms)} / ${LimitRules.formatDuration(limitMs)}",
                style = MaterialTheme.typography.bodySmall, color = if (over) MM.Yellow else MM.Muted,
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MM.Border)) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(CircleShape).background(if (over) MM.Yellow else MM.Sage))
        }
    }
}

// ------------------------------------------------------------------ pause day

const val PAUSE_PHRASE = "Ich brauche heute wirklich eine Pause."

@Composable
fun PauseScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.container.blockerPrefs
    val snap by prefs.snapshot.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(-1) }
    val s = snap ?: return

    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown-- }
        if (countdown == 0) { prefs.usePauseDay(); countdown = -1 }
    }

    ScreenScaffold("Pausetag", onBack) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                s.pausedToday -> {
                    Text("Heute ist Pause.", style = MaterialTheme.typography.headlineSmall, color = MM.Text)
                    Text("Bis Mitternacht kommt kein Quiz. Die Zeit wird trotzdem gezählt.", style = MaterialTheme.typography.bodyLarge, color = MM.Muted)
                }
                s.pausesLeft == 0 -> {
                    Text("Diesen Monat keine Pausetage mehr.", style = MaterialTheme.typography.headlineSmall, color = MM.Text)
                    Text("Am 1. gibt es wieder ${BlockerPrefs.PAUSES_PER_MONTH}.", style = MaterialTheme.typography.bodyLarge, color = MM.Muted)
                }
                else -> {
                    Text(
                        "Ein Pausetag schaltet Moment mal bis Mitternacht aus. Noch ${s.pausesLeft} von ${BlockerPrefs.PAUSES_PER_MONTH} diesen Monat.",
                        style = MaterialTheme.typography.bodyLarge, color = MM.Muted,
                    )
                    Text("Schreib zur Bestätigung:", style = MaterialTheme.typography.bodyMedium, color = MM.Muted)
                    Text("„$PAUSE_PHRASE“", style = CardText.body)
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it; countdown = -1 },
                        modifier = Modifier.fillMaxWidth(), textStyle = CardText.body, shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MM.Sage.copy(alpha = 0.5f), unfocusedBorderColor = MM.Border, cursorColor = MM.Sage),
                    )
                    val matches = typed.trim() == PAUSE_PHRASE
                    when {
                        countdown > 0 -> Text("Pause beginnt in $countdown s … (zurück = abbrechen)", style = MaterialTheme.typography.bodyMedium, color = MM.Yellow)
                        matches -> PrimaryButton("Pausetag nehmen") { countdown = 30 }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ websites (used inside AppsScreen)

@Composable
fun WebsitesSection(onEditFreeTime: (key: String, label: String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val prefs = ctx.container.blockerPrefs
    val snap by prefs.snapshot.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val domains = snap?.domains?.sorted().orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("WEBSITES", style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        Text("In Chrome & Co. – inklusive Subdomains (m., vm., …).", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
        for (d in domains) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Language, null, tint = MM.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(d, style = MaterialTheme.typography.bodyLarge, color = MM.Text, modifier = Modifier.weight(1f))
                val pendingFree = d in snap?.pending?.undomain.orEmpty()
                if (!pendingFree) snap?.let { s -> FreeTimeChip(s.freeMinutesFor(WebRules.WEB_PREFIX + d)) { onEditFreeTime(WebRules.WEB_PREFIX + d, d) } }
                if (pendingFree) Text("ab morgen frei ", style = MaterialTheme.typography.bodySmall, color = MM.Yellow)
                else IconButton(onClick = {
                    scope.launch {
                        if (snap?.strict == true) Strict.requestRelax(ctx, StagedChange.Undomain(d)) else prefs.setDomain(d, false)
                    }
                }) {
                    Icon(Icons.Rounded.Close, "entfernen", tint = MM.Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        val add = {
            WebRules.normalizeDomain(input)?.let { d -> scope.launch { prefs.setDomain(d, true) }; input = "" }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("z. B. twitch.tv", color = MM.Muted) },
            textStyle = CardText.body, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { IconButton(onClick = { add() }) { Icon(Icons.Rounded.Add, "hinzufügen", tint = MM.Sage) } },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MM.Sage.copy(alpha = 0.5f), unfocusedBorderColor = MM.Border, cursorColor = MM.Sage),
        )
        Text("APPS", style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    }
}
