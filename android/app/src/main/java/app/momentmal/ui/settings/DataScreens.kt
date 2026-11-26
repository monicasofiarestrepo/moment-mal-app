package app.momentmal.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.momentmal.backup.BackupManager
import app.momentmal.container
import app.momentmal.ui.study.PrimaryButton
import app.momentmal.ui.theme.CardText
import app.momentmal.ui.theme.MM
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MM.Sage.copy(alpha = 0.5f), unfocusedBorderColor = MM.Border, cursorColor = MM.Sage,
)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MM.Sage,
    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
)

// ------------------------------------------------------------------ content sync

@Composable
fun ContentScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sync = ctx.container.sync
    val st by sync.state.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(st?.repo) { st?.let { if (repo.isEmpty()) { repo = it.repo; branch = it.branch } } }
    LaunchedEffect(status) { version = ctx.container.contentVersion() }
    val s = st ?: return

    ScreenScaffold("Inhalte", onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Die Karten kommen aus deinem privaten GitHub-Repo. Änderungen dort werden täglich (und beim Öffnen der App) geladen. Dein Fortschritt bleibt erhalten, solange die IDs gleich bleiben.",
                style = MaterialTheme.typography.bodyMedium, color = MM.Muted,
            )
            Card {
                Text("Version ${version ?: "–"}", style = MaterialTheme.typography.titleMedium, color = MM.Text)
                Text(
                    s.lastSyncAt?.let { "Zuletzt geprüft: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Noch nie synchronisiert",
                    style = MaterialTheme.typography.bodySmall, color = MM.Muted,
                )
                s.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MM.Yellow) }
                status?.takeIf { it != s.lastError }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MM.Sage) }
                PrimaryButton(if (busy) "Lädt …" else "Jetzt synchronisieren") {
                    if (!busy) scope.launch { busy = true; status = sync.sync(force = true); busy = false }
                }
            }

            Text("VERBINDUNG", style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text("Repo (Besitzer/Name)") },
                singleLine = true, textStyle = CardText.body, shape = RoundedCornerShape(16.dp), colors = fieldColors())
            OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("Branch") },
                singleLine = true, textStyle = CardText.body, shape = RoundedCornerShape(16.dp), colors = fieldColors())
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(),
                label = { Text(if (s.hasToken) "Token gespeichert – neuen einfügen?" else "GitHub-Token") },
                singleLine = true, textStyle = CardText.body, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            PrimaryButton("Speichern & synchronisieren") {
                scope.launch {
                    busy = true
                    sync.setRepo(repo, branch)
                    if (token.isNotBlank()) sync.setToken(token)
                    token = ""
                    status = sync.sync(force = true)
                    busy = false
                }
            }
            Card {
                Text("Token erstellen", style = MaterialTheme.typography.titleMedium, color = MM.Text)
                Text(
                    "GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token.\n" +
                        "Repository access: Only select repositories → ${s.repo.substringAfter('/')}\n" +
                        "Permissions → Repository → Contents: Read-only. Kopieren und hier einfügen. Er wird verschlüsselt gespeichert.",
                    style = MaterialTheme.typography.bodySmall, color = MM.Muted,
                )
                Link("Seite öffnen") {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ backup

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val backup = ctx.container.backup
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { scope.launch { status = runCatching { backup.exportTo(it) }.getOrElse { e -> "Fehler: ${e.message}" } } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }

    ScreenScaffold("Backup", onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Sichert deinen ganzen Fortschritt (Karten, Antworten, Nutzung, Einstellungen) als eine JSON-Datei. " +
                    "Automatisch jede Woche in Download/${BackupManager.FOLDER} – die letzten 4 bleiben.",
                style = MaterialTheme.typography.bodyMedium, color = MM.Muted,
            )
            PrimaryButton("Fortschritt exportieren") { exporter.launch(BackupManager.fileName()) }
            Card {
                Text("Importieren", style = MaterialTheme.typography.titleMedium, color = MM.Text)
                Text("Ersetzt deinen aktuellen Fortschritt komplett durch die Datei.", style = MaterialTheme.typography.bodySmall, color = MM.Muted)
                val p = pendingImport
                if (p == null) Link("Datei wählen") { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
                else {
                    Text("Wirklich alles ersetzen?", style = MaterialTheme.typography.bodyMedium, color = MM.Yellow)
                    Link("Ja, wiederherstellen") {
                        scope.launch {
                            status = runCatching { backup.importFrom(p) }.getOrElse { e -> "Datei ungültig: ${e.message}" }
                            pendingImport = null
                        }
                    }
                    Link("Abbrechen") { pendingImport = null }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MM.Sage) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
