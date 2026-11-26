package app.momentmal.blocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.momentmal.R
import app.momentmal.container
import app.momentmal.session.SessionMode
import app.momentmal.ui.study.Finished
import app.momentmal.ui.study.StudyScreen
import app.momentmal.ui.study.StudyViewModel
import app.momentmal.ui.study.studyViewModel
import app.momentmal.ui.theme.CardText
import app.momentmal.ui.theme.MM
import app.momentmal.ui.theme.MomentMalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown on top of a blocked app, in its own task (see manifest). When it finishes,
 * Android brings back the blocked app *as it was* — including a post/link it was opening —
 * instead of relaunching it at its home screen.
 */
class ChallengeActivity : ComponentActivity() {
    private var targetPkg by mutableStateOf("")
    /** Grace key: package name or "web:<domain>". */
    private var targetKey by mutableStateOf("")
    private var required = DEFAULT_REQUIRED
    private var overMs = 0L
    private var immediate = false
    private var leaving = false

    private fun readExtras(i: Intent) {
        targetPkg = i.getStringExtra(EXTRA_PKG).orEmpty()
        targetKey = i.getStringExtra(EXTRA_KEY) ?: targetPkg
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        required = intent.getIntExtra(EXTRA_REQUIRED, DEFAULT_REQUIRED)
        overMs = intent.getLongExtra(EXTRA_OVER_MS, 0)
        immediate = intent.getBooleanExtra(EXTRA_IMMEDIATE, false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MomentMalTheme {
                BackHandler { goHome() }
                val vm = studyViewModel(SessionMode.Challenge(required), origin = targetKey, key = "challenge")
                ChallengeScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Another blocked app was opened while the challenge is up: the same challenge now leads there.
        if (intent.hasExtra(EXTRA_PKG)) readExtras(intent)
    }

    override fun onStop() {
        super.onStop()
        // Left the challenge (Home, recents…): it's abandoned; the next open starts a fresh one.
        if (!isChangingConfigurations && !leaving) finishAndRemoveTask()
    }

    private val protect get() = StrictRules.isProtectKey(targetKey)

    private val appLabel: String
        get() = if (targetKey == StrictRules.KEY_SETTINGS) "die Einstellungen"
        else if (targetKey == StrictRules.KEY_CHANGE) "die Änderung"
        else if (targetKey.startsWith(WebRules.WEB_PREFIX)) targetKey.removePrefix(WebRules.WEB_PREFIX) else runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPkg, 0)).toString()
        }.getOrDefault("die App")

    @Composable
    private fun ChallengeScreen(vm: StudyViewModel) {
        val ui by vm.ui.collectAsStateWithLifecycle()
        val snap by container.blockerPrefs.snapshot.collectAsState(initial = null)
        var note by rememberSaveable { mutableStateOf("") }
        var editing by rememberSaveable { mutableStateOf(false) }
        var showSkip by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { delay(1000); showSkip = true }

        val firstQuestion = ui.counted == 0 && !ui.finished
        val label = appLabel

        StudyScreen(
            vm = vm,
            onClose = {},
            closable = false,
            topBarStart = {
                // Subtle note button, only on the first question (or if a note exists).
                if ((firstQuestion || note.isNotBlank()) && !ui.finished && !protect) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .clickable { editing = !editing }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .alpha(if (note.isBlank()) 0.55f else 0.9f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.EditNote, null, tint = MM.Muted, modifier = Modifier.size(20.dp))
                        Text(
                            if (note.isBlank()) " Notiz" else " ${note.take(24)}${if (note.length > 24) "…" else ""}",
                            style = MaterialTheme.typography.labelMedium, color = MM.Muted,
                        )
                    }
                }
            },
            header = {
                Column {
                    Text(
                        "Moment mal. Erst $required Fragen, dann $label.",
                        style = MaterialTheme.typography.bodyMedium, color = MM.Muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    if (overMs > 0) Text(
                        "Du bist heute ${LimitRules.formatDuration(overMs)} über deinem Limit.",
                        style = MaterialTheme.typography.bodySmall, color = MM.Yellow.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    AnimatedVisibility(editing && firstQuestion, enter = fadeIn(tween(200))) {
                        NoteField(note, onChange = { note = it }, onDone = { editing = false })
                    }
                }
            },
            footer = {
                val left = snap?.skipsLeft ?: 0
                if (showSkip && left > 0 && !protect) {
                    Text(
                        "Überspringen · $left übrig",
                        style = MaterialTheme.typography.labelMedium,
                        color = MM.Muted,
                        modifier = Modifier
                            .alpha(0.35f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch {
                                    if (container.blockerPrefs.useSkip()) {
                                        vm.markSkipped()
                                        proceed(note)
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            },
            finished = { m ->
                Finished(
                    title = if (targetKey == StrictRules.KEY_CHANGE) "Bestätigt." else "Geschafft.",
                    text = when (targetKey) {
                        StrictRules.KEY_CHANGE -> if (immediate) "Die Änderung gilt ab sofort." else "Die Änderung gilt ab morgen."
                        StrictRules.KEY_SETTINGS -> "${StrictRules.SETTINGS_GRACE_MIN} Minuten für die Einstellungen."
                        else -> "${snap?.freeMinutesFor(targetKey) ?: FreeTime.default(targetKey)} Minuten gehören dir."
                    },
                    modifier = m,
                    button = if (targetKey == StrictRules.KEY_CHANGE) "Fertig" else "Weiter zu $label",
                    onDone = { scope.launch { proceed(note) } },
                    extra = { if (note.isNotBlank()) NoteCard(note) },
                )
            },
        )
    }

    @Composable
    private fun NoteField(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
        val focus = remember { FocusRequester() }
        val fm = LocalFocusManager.current
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(
            value = value,
            // Enter = done (no line breaks in a note)
            onValueChange = {
                if ('\n' in it) { onChange(it.replace('\n', ' ').trim()); fm.clearFocus(); onDone() } else onChange(it)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focus),
            placeholder = { Text("Was wolltest du dort machen?", color = MM.Muted) },
            textStyle = CardText.body,
            maxLines = 3,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { fm.clearFocus(); onDone() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MM.Sage.copy(alpha = 0.5f), unfocusedBorderColor = MM.Border,
                cursorColor = MM.Sage, focusedContainerColor = MM.Surface, unfocusedContainerColor = MM.Surface,
            ),
        )
    }

    @Composable
    private fun NoteCard(note: String) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MM.Surface)
                .border(1.dp, MM.Sage.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            var copied by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DEINE NOTIZ", style = MaterialTheme.typography.labelSmall, color = MM.Sage, modifier = Modifier.weight(1f))
                // copy only on purpose, never automatically
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable { copyToClipboard(this@ChallengeActivity, note); copied = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        "Kopieren", tint = if (copied) MM.Sage else MM.Muted, modifier = Modifier.size(18.dp),
                    )
                    Text(if (copied) " Kopiert" else " Kopieren", style = MaterialTheme.typography.labelMedium, color = if (copied) MM.Sage else MM.Muted)
                }
            }
            Text(note, style = CardText.body)
            Text("Steht auch in deinen Benachrichtigungen.", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
        }
    }

    /** Grants the grace period and reveals the blocked app where it was. */
    private suspend fun proceed(note: String) {
        when (targetKey) {
            StrictRules.KEY_CHANGE -> container.blockerPrefs.confirmStaged()
            StrictRules.KEY_SETTINGS -> container.blockerPrefs.grantGrace(targetKey, StrictRules.SETTINGS_GRACE_MIN)
            else -> container.blockerPrefs.grantGrace(targetKey, container.blockerPrefs.current().freeMinutesFor(targetKey))
        }
        if (note.isNotBlank()) deliverNote(note)
        leaving = true
        if (targetPkg != packageName) ReturnTracker.expect(targetPkg)
        finishAndRemoveTask()
        overrideTransitionCompat()
    }

    private fun deliverNote(note: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NOTE, "Notizen", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(this, CHANNEL_NOTE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Du wolltest:")
            .setContentText(note)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note))
            .addAction(0, "Kopieren", NoteCopyReceiver.pending(this, note))
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60_000L)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTE_ID, n) }
    }

    private fun goHome() {
        leaving = true
        // a confirmation started from our own settings: just go back there (the change is dropped)
        if (targetKey == StrictRules.KEY_CHANGE) { finishAndRemoveTask(); return }
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }

    @Suppress("DEPRECATION")
    private fun overrideTransitionCompat() = overridePendingTransition(0, 0)

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_KEY = "key"
        const val EXTRA_REQUIRED = "required"
        const val EXTRA_OVER_MS = "over_ms"
        const val EXTRA_IMMEDIATE = "immediate"
        const val DEFAULT_REQUIRED = LimitRules.BASE
        private const val CHANNEL_NOTE = "note"
        private const val NOTE_ID = 42
    }
}

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Moment mal", text))
}

/** "Kopieren" action on the note notification. */
class NoteCopyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_TEXT)?.let { copyToClipboard(context, it) }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        fun pending(context: Context, text: String): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
            context, 1, Intent(context, NoteCopyReceiver::class.java).putExtra(EXTRA_TEXT, text),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
