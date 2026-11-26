package app.momentmal.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import app.momentmal.container
import app.momentmal.data.CardStateEntity
import app.momentmal.data.ReviewLogEntity
import app.momentmal.data.SessionEntity
import app.momentmal.data.UsageDayEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class BackupSettings(
    val blocked: Set<String>,
    val domains: Set<String>,
    val appLimitMin: Int,
    val totalLimitMin: Int,
    val pauseDays: Set<String> = emptySet(),
    val baselineMin: Int? = null,
)

@Serializable
data class Backup(
    val format: Int = 1,
    val exportedAt: Long,
    val cardStates: List<CardStateEntity>,
    val reviews: List<ReviewLogEntity>,
    val sessions: List<SessionEntity>,
    val usage: List<UsageDayEntity>,
    val settings: BackupSettings,
)

/** Export / import all progress as one JSON file; weekly automatic copy in Download/MomentMal. */
class BackupManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val c get() = context.container

    suspend fun build(): Backup {
        val snap = c.blockerPrefs.current()
        return Backup(
            exportedAt = System.currentTimeMillis(),
            cardStates = c.db.cardStates().all(),
            reviews = c.db.reviews().all(),
            sessions = c.db.sessions().all(),
            usage = c.db.usage().all(),
            settings = BackupSettings(
                snap.blocked, snap.domains, snap.appLimitMin, snap.totalLimitMin, snap.pauseDays, c.stats.baselineMin(),
            ),
        )
    }

    suspend fun exportTo(uri: Uri): String = withContext(Dispatchers.IO) {
        val b = build()
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json.encodeToString(Backup.serializer(), b)) }
        "${b.cardStates.size} Karten · ${b.reviews.size} Antworten gesichert"
    }

    /** Replaces all progress with the file's content. */
    suspend fun importFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val b = json.decodeFromString(Backup.serializer(), text)
        c.db.withTransaction {
            c.db.cardStates().deleteAll(); c.db.cardStates().insertAll(b.cardStates)
            c.db.reviews().deleteAll(); c.db.reviews().insertAll(b.reviews)
            c.db.sessions().deleteAll(); c.db.sessions().insertAll(b.sessions)
            c.db.usage().deleteAll(); c.db.usage().insertAll(b.usage)
        }
        with(b.settings) {
            c.blockerPrefs.restore(blocked, domains, appLimitMin, totalLimitMin, pauseDays)
            baselineMin?.let { c.stats.setBaselineMin(it) }
        }
        "${b.cardStates.size} Karten · ${b.reviews.size} Antworten wiederhergestellt"
    }

    /** Weekly automatic backup to Download/MomentMal/, keeping the newest [keep]. */
    suspend fun autoBackup(keep: Int = 4): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = "moment-mal-auto-${LocalDate.now()}.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        }
        val uri = resolver.insert(collection, values) ?: return@withContext
        resolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(json.encodeToString(Backup.serializer(), build())) }

        // prune: only our own files are visible to us without extra permissions
        val old = mutableListOf<Pair<Uri, String>>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("moment-mal-auto-%"),
            null,
        )?.use { cur ->
            while (cur.moveToNext()) old += android.content.ContentUris.withAppendedId(collection, cur.getLong(0)) to cur.getString(1)
        }
        old.sortedByDescending { it.second }.drop(keep).forEach { resolver.delete(it.first, null, null) }
    }

    companion object {
        const val FOLDER = "MomentMal"
        fun fileName() = "moment-mal-backup-${LocalDate.now()}.json"
    }
}
