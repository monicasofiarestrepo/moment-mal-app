package app.momentmal.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.momentmal.blocker.BlockerPrefs
import app.momentmal.blocker.BlockerService
import app.momentmal.blocker.LimitRules
import app.momentmal.container
import app.momentmal.ui.theme.MM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ shared

@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MM.Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", tint = MM.Muted) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = MM.Text)
        }
        content()
    }
}

/** Re-evaluates [block] every time the screen resumes (after returning from system settings). */
@Composable
fun <T> onResumeValue(block: () -> T): T {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return remember(tick) { block() }
}

fun protectionReady(ctx: Context) = BlockerService.isEnabled(ctx)

// ------------------------------------------------------------------ settings + setup

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(onBack: () -> Unit, onApps: () -> Unit, onLimits: () -> Unit, onPause: () -> Unit, onContent: () -> Unit, onBackup: () -> Unit) {
    val ctx = LocalContext.current
    val a11y = onResumeValue { BlockerService.isEnabled(ctx) }
    val notif = onResumeValue {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    var notifAsked by remember { mutableStateOf(false) }
    val battery = onResumeValue { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifAsked = true }
    val snap by ctx.container.blockerPrefs.snapshot.collectAsState(initial = null)

    ScreenScaffold("Einstellungen", onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("SCHUTZ EINRICHTEN")
            Step(
                n = 1, done = a11y, title = "Eingeschränkte Einstellungen zulassen",
                text = "Nur einmal nötig, weil die App nicht aus dem Play Store kommt: App-Info öffnen → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“.",
                action = "App-Info öffnen",
            ) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
            Step(
                n = 2, done = a11y, title = "Bedienungshilfe aktivieren",
                text = "Bedienungshilfen → Installierte Apps → Moment mal → Ein. Damit erkennt Moment mal, wenn du Instagram & Co. öffnest.",
                action = "Bedienungshilfen öffnen",
            ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Step(
                n = 3, done = notif, title = "Benachrichtigungen erlauben",
                text = "Für deine Notizen: Was du in der App machen wolltest, erscheint nach dem Quiz als Benachrichtigung.",
                action = if (notifAsked && !notif) "In den Einstellungen erlauben" else "Erlauben",
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !notifAsked) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            Step(
                n = 4, done = battery, title = "Akku nicht einschränken",
                text = "Sonst beendet Samsung den Dienst im Hintergrund. Zusätzlich: Einstellungen → Akku → Hintergrundnutzungslimits → „Nie im Standby“ → Moment mal hinzufügen.",
                action = "Erlauben",
            ) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }

            Section("BLOCKIEREN")
            NavRow(
                "Apps & Websites",
                "${snap?.blocked?.count { isInstalled(ctx, it) } ?: "–"} Apps · ${snap?.domains?.size ?: "–"} Websites · ${BlockerPrefs.GRACE_MINUTES} Min. frei nach dem Quiz",
                onApps,
            )
            NavRow(
                "Limits",
                snap?.let { "${it.appLimitMin} Min. pro App · ${LimitRules.formatDuration(it.totalLimitMs)} insgesamt" } ?: "",
                onLimits,
            )
            NavRow(
                "Pausetag",
                snap?.let { if (it.pausedToday) "Heute ist Pause" else "Noch ${it.pausesLeft} von ${BlockerPrefs.PAUSES_PER_MONTH} diesen Monat" } ?: "",
                onPause,
            )
            Text(
                "Überspringen: noch ${snap?.skipsLeft ?: "–"} von ${BlockerPrefs.SKIPS_PER_DAY} heute.",
                style = MaterialTheme.typography.bodySmall, color = MM.Muted, modifier = Modifier.padding(start = 4.dp),
            )

            Section("DATEN")
            NavRow("Inhalte", "Karten aus GitHub synchronisieren", onContent)
            NavRow("Backup", "Exportieren, importieren, wöchentlich automatisch", onBackup)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun isInstalled(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MM.Muted, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MM.Surface)
            .border(1.dp, if (done) MM.Sage.copy(alpha = 0.35f) else MM.Border, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(if (done) MM.Sage.copy(alpha = 0.2f) else MM.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Rounded.Check, null, tint = MM.Sage, modifier = Modifier.size(16.dp))
                else Text("$n", style = MaterialTheme.typography.labelLarge, color = MM.Muted)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (done) MM.Muted else MM.Text)
        }
        if (!done) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MM.Muted)
            Text(
                action, style = MaterialTheme.typography.labelLarge, color = MM.Sage,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MM.Surface)
            .border(1.dp, MM.Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MM.Text)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MM.Muted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MM.Muted)
    }
}

// ------------------------------------------------------------------ blocked apps

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap)

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.container.blockerPrefs
    val snap by prefs.snapshot.collectAsState(initial = null)
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != ctx.packageName }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap()) }
        }
    }

    ScreenScaffold("Apps & Websites", onBack) {
        val blocked = snap?.blocked.orEmpty()
        // blocked first, then alphabetical; order frozen at load so rows don't jump while toggling
        val sorted = remember(apps, snap != null) {
            apps?.sortedWith(compareBy({ it.pkg !in blocked }, { it.label.lowercase() }))
        }
        if (sorted == null) return@ScreenScaffold
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text(
                    "Beim Öffnen kommt zuerst das Quiz.",
                    style = MaterialTheme.typography.bodyMedium, color = MM.Muted, modifier = Modifier.padding(bottom = 12.dp),
                )
                WebsitesSection()
            }
            items(sorted, key = { it.pkg }) { app ->
                val on = app.pkg in blocked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { scope.launch { prefs.setBlocked(app.pkg, !on) } }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(app.icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, style = MaterialTheme.typography.bodyLarge, color = MM.Text, modifier = Modifier.weight(1f))
                    Switch(
                        checked = on,
                        onCheckedChange = { scope.launch { prefs.setBlocked(app.pkg, it) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MM.Bg, checkedTrackColor = MM.Sage,
                            uncheckedThumbColor = MM.Muted, uncheckedTrackColor = MM.SurfaceHigh, uncheckedBorderColor = MM.Border,
                        ),
                    )
                }
            }
        }
    }
}
