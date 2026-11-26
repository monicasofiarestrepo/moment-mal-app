package app.momentmal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.momentmal.session.SessionMode
import app.momentmal.ui.settings.AppsScreen
import app.momentmal.ui.settings.BackupScreen
import app.momentmal.ui.settings.ContentScreen
import app.momentmal.ui.stats.StatsScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.momentmal.ui.settings.LimitsScreen
import app.momentmal.ui.settings.PauseScreen
import app.momentmal.ui.settings.SettingsScreen
import app.momentmal.ui.start.StartScreen
import app.momentmal.ui.study.StudyScreen
import app.momentmal.ui.study.studyViewModel
import app.momentmal.ui.theme.MomentMalTheme

/** Launcher entry: direct study. Never redirects to a blocked app afterwards. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // pick up content edits from GitHub (at most every 6 h)
        lifecycleScope.launch { container.sync.syncIfStale() }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MomentMalTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "start") {
                    composable("start") {
                        StartScreen(
                            onStudy = { deck -> nav.navigate("study/${deck ?: MIXED}") },
                            onSettings = { nav.navigate("settings") },
                            onStats = { nav.navigate("stats") },
                        )
                    }
                    composable("study/{deck}") { entry ->
                        val deck = entry.arguments?.getString("deck")?.takeIf { it != MIXED }
                        val vm = studyViewModel(SessionMode.Direct(deck), origin = "direct", key = "study-${entry.id}")
                        StudyScreen(vm, onClose = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onApps = { nav.navigate("apps") },
                            onLimits = { nav.navigate("limits") },
                            onPause = { nav.navigate("pause") },
                            onContent = { nav.navigate("content") },
                            onBackup = { nav.navigate("backup") },
                        )
                    }
                    composable("apps") { AppsScreen(onBack = { nav.popBackStack() }) }
                    composable("limits") { LimitsScreen(onBack = { nav.popBackStack() }) }
                    composable("pause") { PauseScreen(onBack = { nav.popBackStack() }) }
                    composable("content") { ContentScreen(onBack = { nav.popBackStack() }) }
                    composable("backup") { BackupScreen(onBack = { nav.popBackStack() }) }
                    composable("stats") { StatsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    private companion object {
        const val MIXED = "mix"
    }
}
