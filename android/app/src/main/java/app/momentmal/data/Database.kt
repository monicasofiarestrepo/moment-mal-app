package app.momentmal.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Upsert
import app.momentmal.srs.Phase
import app.momentmal.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "card_state", indices = [Index("deckId"), Index("due")])
data class CardStateEntity(
    @PrimaryKey val cardId: String,
    val deckId: String,
    val phase: Int,
    val stability: Double,
    val difficulty: Double,
    val due: Long,
    val lastReview: Long?,
    val reps: Int,
    val lapses: Int,
    val step: Int,
    val consecutiveGreens: Int,
    /** Last language shown for bilingual cards ("EN"/"ES"); the next showing uses the other one. */
    val lastLang: String?,
    val firstSeen: Long,
    /** Card no longer exists in the content (note deleted). Kept for history. */
    val archived: Boolean = false,
) {
    fun toSrs() = SrsState(
        Phase.entries[phase], stability, difficulty, due, lastReview, reps, lapses, step, consecutiveGreens,
    )

    companion object {
        fun from(cardId: String, deckId: String, s: SrsState, lastLang: String?, firstSeen: Long) = CardStateEntity(
            cardId, deckId, s.phase.ordinal, s.stability, s.difficulty, s.due, s.lastReview, s.reps, s.lapses,
            s.step, s.consecutiveGreens, lastLang, firstSeen,
        )
    }
}

@Serializable
@Entity(tableName = "review_log", indices = [Index("cardId"), Index("timestamp")])
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val deckId: String,
    val sessionId: Long,
    /** 1..4 (Again..Easy). */
    val rating: Int,
    /** "FLIP" or "MCQ". */
    val mode: String,
    val timestamp: Long,
    val durationMs: Long,
    val stabilityAfter: Double,
    val masteryAfter: String,
)

@Serializable
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "direct" or the package/domain that triggered the challenge. */
    val origin: String,
    val deckId: String?,
    /** Required questions (null = unlimited direct study). */
    val required: Int?,
    val answered: Int = 0,
    val completed: Boolean = false,
    val skipped: Boolean = false,
    val startedAt: Long,
    val endedAt: Long? = null,
)

@Dao
interface CardStateDao {
    @Query("SELECT * FROM card_state")
    suspend fun all(): List<CardStateEntity>

    @Query("SELECT * FROM card_state")
    fun observeAll(): Flow<List<CardStateEntity>>

    @Upsert
    suspend fun upsert(state: CardStateEntity)

    @Query("SELECT COUNT(*) FROM card_state WHERE firstSeen >= :since")
    suspend fun newSince(since: Long): Int

    @Insert
    suspend fun insertAll(rows: List<CardStateEntity>)

    @Query("DELETE FROM card_state")
    suspend fun deleteAll()
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity)

    @Query("SELECT COUNT(*) FROM review_log WHERE timestamp >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT * FROM review_log")
    suspend fun all(): List<ReviewLogEntity>

    @Query("SELECT * FROM review_log WHERE timestamp >= :since")
    suspend fun since(since: Long): List<ReviewLogEntity>

    @Insert
    suspend fun insertAll(rows: List<ReviewLogEntity>)

    @Query("DELETE FROM review_log")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: SessionEntity): Long

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Update
    suspend fun update(s: SessionEntity)

    @Query("SELECT * FROM session")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM session WHERE startedAt >= :since")
    suspend fun since(since: Long): List<SessionEntity>

    @Insert
    suspend fun insertAll(rows: List<SessionEntity>)

    @Query("DELETE FROM session")
    suspend fun deleteAll()
}

/** Time spent per day in a blocked app ("com.instagram.android") or site ("web:instagram.com"). */
@Serializable
@Entity(tableName = "app_usage_day", primaryKeys = ["day", "usageKey"])
data class UsageDayEntity(
    /** ISO date, e.g. "2026-11-26". */
    val day: String,
    val usageKey: String,
    val ms: Long,
)

@Dao
abstract class UsageDao {
    @Query("SELECT * FROM app_usage_day WHERE day = :day")
    abstract suspend fun forDay(day: String): List<UsageDayEntity>

    @Query("SELECT * FROM app_usage_day WHERE day = :day")
    abstract fun observeDay(day: String): Flow<List<UsageDayEntity>>

    @Query("INSERT OR IGNORE INTO app_usage_day(day, usageKey, ms) VALUES(:day, :key, 0)")
    protected abstract suspend fun ensure(day: String, key: String)

    @Query("UPDATE app_usage_day SET ms = ms + :ms WHERE day = :day AND usageKey = :key")
    protected abstract suspend fun increment(day: String, key: String, ms: Long)

    @Transaction
    open suspend fun add(day: String, key: String, ms: Long) {
        ensure(day, key)
        increment(day, key, ms)
    }

    @Query("SELECT * FROM app_usage_day")
    abstract suspend fun all(): List<UsageDayEntity>

    @Insert
    abstract suspend fun insertAll(rows: List<UsageDayEntity>)

    @Query("DELETE FROM app_usage_day")
    abstract suspend fun deleteAll()
}

@Database(
    entities = [CardStateEntity::class, ReviewLogEntity::class, SessionEntity::class, UsageDayEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardStates(): CardStateDao
    abstract fun reviews(): ReviewLogDao
    abstract fun sessions(): SessionDao
    abstract fun usage(): UsageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_usage_day` (`day` TEXT NOT NULL, `usageKey` TEXT NOT NULL, " +
                        "`ms` INTEGER NOT NULL, PRIMARY KEY(`day`, `usageKey`))",
                )
            }
        }

        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "momentmal.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
