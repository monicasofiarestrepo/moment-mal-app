package app.momentmal

import android.app.Application
import android.content.Context
import app.momentmal.backup.BackupManager
import app.momentmal.blocker.BlockerPrefs
import app.momentmal.content.Catalog
import app.momentmal.content.Manifest
import app.momentmal.content.Presenter
import app.momentmal.data.AppDatabase
import app.momentmal.srs.Fsrs
import app.momentmal.stats.StatsPrefs
import app.momentmal.sync.SyncManager
import app.momentmal.work.Workers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MomentMalApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Workers.schedule(this)
    }
}

val Context.container get() = (applicationContext as MomentMalApp).container

/** Simple manual DI. */
class AppContainer(private val context: Context) {
    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val fsrs = Fsrs()
    val blockerPrefs = BlockerPrefs(context)
    val sync = SyncManager(context)
    val stats = StatsPrefs(context)
    val backup by lazy { BackupManager(context) }

    private val lock = Mutex()
    private var cached: Pair<Catalog, Presenter>? = null

    suspend fun content(): Pair<Catalog, Presenter> = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val read = reader()
            val catalog = runCatching { Catalog.load(read) }.getOrElse { Catalog.load(::readAsset) }
            (catalog to Presenter(catalog)).also { cached = it }
        }
    }

    suspend fun invalidateContent() = lock.withLock { cached = null }

    /** Version of the content currently in use. */
    suspend fun contentVersion(): Int = content().first.manifest.version

    /** The synced copy (GitHub) is used unless the bundled one is newer (e.g. after an app update). */
    private fun reader(): (String) -> String {
        val syncedVersion = runCatching { manifestOf(File(context.filesDir, "content/manifest.json").readText()).version }.getOrNull()
        val bundledVersion = runCatching { manifestOf(readAsset("manifest.json")).version }.getOrDefault(0)
        return if (syncedVersion != null && syncedVersion >= bundledVersion) { p -> File(context.filesDir, "content/$p").readText() }
        else ::readAsset
    }

    private fun manifestOf(text: String) = Catalog.json.decodeFromString<Manifest>(text)

    private fun readAsset(path: String) = context.assets.open("content/$path").bufferedReader().use { it.readText() }
}
