package app.momentmal.ui.study

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.momentmal.content.Block
import app.momentmal.content.BlockStyle
import app.momentmal.session.ItemKind
import app.momentmal.session.SessionItem
import app.momentmal.session.SessionMode
import app.momentmal.srs.Fsrs
import app.momentmal.srs.Rating
import app.momentmal.ui.Speaker
import app.momentmal.ui.rememberSpeaker
import app.momentmal.ui.theme.CardText
import app.momentmal.ui.theme.MM

@Composable
fun studyViewModel(mode: SessionMode, origin: String, key: String): StudyViewModel {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(key = key, factory = viewModelFactory { initializer { StudyViewModel(app, mode, origin) } })
}

/**
 * Shared by direct study (launcher) and the blocking challenge.
 * Slots let the challenge add the note button ([topBarStart]), the note field ([header]),
 * the discreet skip button ([footer]) and its own end screen ([finished]).
 */
@Composable
fun StudyScreen(
    vm: StudyViewModel,
    onClose: () -> Unit,
    closable: Boolean = true,
    topBarStart: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    finished: @Composable (Modifier) -> Unit = { m ->
        Finished("Alles wiederholt.", "Für jetzt ist nichts mehr fällig.", m, "Fertig", onClose)
    },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    val haptics = LocalHapticFeedback.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MM.Bg)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        // ---- top bar ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (closable) IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, "Beenden", tint = MM.Muted)
            }
            topBarStart()
            Spacer(Modifier.weight(1f))
            Text(
                ui.required?.let { "${minOf(ui.counted + 1, it)} / $it" } ?: "${ui.counted} beantwortet",
                style = MaterialTheme.typography.labelLarge, color = MM.Muted,
            )
            Spacer(Modifier.width(16.dp))
        }
        ui.required?.let { req ->
            LinearProgressIndicator(
                progress = { ui.counted / req.toFloat() },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = MM.Sage, trackColor = MM.Border, drawStopIndicator = {},
            )
        }
        header()

        when {
            ui.loading -> Box(Modifier.weight(1f))
            ui.finished -> finished(Modifier.weight(1f))
            else -> ui.item?.let { item ->
                AnimatedContent(
                    targetState = item,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                    modifier = Modifier.weight(1f),
                    label = "card",
                ) { current ->
                    CardBody(current, ui.revealed, ui.chosen, speaker) { i ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.choose(i)
                    }
                }
                Controls(item, ui, vm)
                footer()
            }
        }
    }
}

@Composable
private fun CardBody(item: SessionItem, revealed: Boolean, chosen: Int?, speaker: Speaker, onChoose: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 16.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(24.dp))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.isNew || item.isRepeat || item.kind == ItemKind.MCQ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.isNew) Chip("NEU", MM.Sage)
                if (item.isRepeat) Chip("NOCHMAL", MM.Red)
                if (item.kind == ItemKind.MCQ) Chip("QUIZ", MM.Blue)
            }
        }
        val q = item.mcq
        if (item.kind == ItemKind.MCQ && q != null) {
            q.prompt.forEach { BlockView(it, speaker) }
            Spacer(Modifier.height(4.dp))
            q.options.forEachIndexed { i, opt -> OptionRow(opt, i, q.correct, chosen, onChoose) }
            AnimatedVisibility(revealed, enter = fadeIn(tween(250))) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Divider()
                    q.explanation.forEach { BlockView(it, speaker) }
                }
            }
        } else {
            item.flash.front.forEach { BlockView(it, speaker) }
            AnimatedVisibility(revealed, enter = fadeIn(tween(250))) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Divider()
                    item.flash.back.forEach { BlockView(it, speaker) }
                }
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(MM.Border))

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun BlockView(b: Block, speaker: Speaker) {
    val style = when (b.style) {
        BlockStyle.LABEL -> CardText.label
        BlockStyle.BIG -> CardText.big
        BlockStyle.BODY -> CardText.body
        BlockStyle.EXAMPLE_DE -> CardText.exampleDe
        BlockStyle.EXAMPLE_ES, BlockStyle.MUTED -> CardText.muted
        BlockStyle.HINT -> CardText.hint
    }
    val text = if (b.artikel != null && b.text.startsWith(b.artikel + " ")) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = MM.artikel(b.artikel), fontWeight = FontWeight.SemiBold)) { append(b.artikel) }
            append(b.text.removePrefix(b.artikel))
        }
    } else buildAnnotatedString { append(if (b.style == BlockStyle.LABEL) b.text.uppercase() else b.text) }

    Row(verticalAlignment = Alignment.Top) {
        if (b.style == BlockStyle.HINT) {
            Box(Modifier.padding(top = 3.dp, end = 10.dp).width(2.dp).height(18.dp).background(MM.Sage.copy(alpha = 0.6f)))
        }
        Text(text, style = style, modifier = Modifier.weight(1f))
        if (b.speakDe) {
            IconButton(onClick = { speaker.speak(b.text) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Vorlesen", tint = MM.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, index: Int, correct: Int, chosen: Int?, onChoose: (Int) -> Unit) {
    val color = when {
        chosen == null -> MM.Border
        index == correct -> MM.Green
        index == chosen -> MM.Red
        else -> MM.Border
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (chosen != null && (index == correct || index == chosen)) color.copy(alpha = 0.12f) else MM.SurfaceHigh)
            .border(1.dp, color, RoundedCornerShape(16.dp))
            .clickable(enabled = chosen == null) { onChoose(index) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text, style = CardText.body.copy(fontSize = CardText.body.fontSize * 0.92f))
    }
}

@Composable
private fun Controls(item: SessionItem, ui: StudyUi, vm: StudyViewModel) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        when {
            item.kind == ItemKind.MCQ && ui.chosen == null -> {
                Text("Wähle eine Antwort", style = MaterialTheme.typography.bodyMedium, color = MM.Muted, modifier = Modifier.align(Alignment.Center).padding(vertical = 18.dp))
            }
            item.kind == ItemKind.MCQ -> PrimaryButton("Weiter") { vm.next() }
            !ui.revealed -> PrimaryButton("Antwort zeigen") { vm.reveal() }
            else -> TrafficLight(item.intervals) { r ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.rate(r)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MM.Sage.copy(alpha = 0.16f))
            .border(1.dp, MM.Sage.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MM.Sage)
    }
}

@Composable
private fun TrafficLight(intervals: Map<Rating, Long>, onRate: (Rating) -> Unit) {
    val buttons = listOf(
        Triple(Rating.AGAIN, "Nochmal", MM.Red),
        Triple(Rating.HARD, "Schwer", MM.Yellow),
        Triple(Rating.GOOD, "Gut", MM.Green),
        Triple(Rating.EASY, "Leicht", MM.Blue),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for ((r, label, color) in buttons) {
            Column(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(color.copy(alpha = 0.13f))
                    .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .clickable { onRate(r) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(intervals[r]?.let(Fsrs::formatDelay) ?: "", style = MaterialTheme.typography.labelMedium, color = MM.Muted)
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    }
}

@Composable
fun Finished(
    title: String, text: String, modifier: Modifier, button: String, onDone: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MM.Text)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MM.Muted)
        Spacer(Modifier.height(24.dp))
        extra()
        Spacer(Modifier.height(24.dp))
        PrimaryButton(button, onDone)
    }
}
