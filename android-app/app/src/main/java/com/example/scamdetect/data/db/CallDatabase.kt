package com.example.scamdetect.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * Workstream B.7: the user dashboard's database. Lives only on the phone (nothing is uploaded, and
 * android:allowBackup="false" keeps it out of cloud backups). No transcripts: the one exception is
 * `riskiestSentence`, saved only when the user turned on "Save the riskiest sentence".
 */
@Entity
data class CallRecord(
    /** The call_id sent to the gateway (a UUID); "migrated-…" for rows moved from the old History. */
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long,
    /** SAFE | UNSURE | SCAM | NOT CHECKED: what the summary showed. */
    val finalVerdict: String,
    /** The verdict on the bar when the call ended; null for migrated rows. */
    val liveVerdict: String?,
    val topReason: String,
    val callScore: Float?,
    /** Signals that fired during the call: "urgency", "money", "impersonation". */
    val signalsFired: List<String>,
    /** "bert" (end-of-call review) | "fallback" (live verdict) | "unavailable" (server not reached) | "migrated" */
    val review: String,
    /** The caller line that scored highest. Null unless the user allowed saving it. */
    val riskiestSentence: String?,
)

/** One caller line's scores, for the risk-over-time chart. Numbers only. */
@Entity(
    primaryKeys = ["callId", "seq"],
    foreignKeys = [ForeignKey(
        entity = CallRecord::class,
        parentColumns = ["id"],
        childColumns = ["callId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("callId")],
)
data class ScorePoint(
    val callId: String,
    val seq: Long,
    val secondsIntoCall: Float,
    /** The call's combined score after this line. */
    val combined: Float?,
    /** This line's own scores; null = that check did not answer. */
    val urgency: Float?,
    val money: Float?,
    val impersonation: Float?,
)

class Converters {
    @TypeConverter fun fromList(v: List<String>): String = v.joinToString(",")
    @TypeConverter fun toList(v: String): List<String> = v.split(',').filter { it.isNotBlank() }
}

@Dao
interface CallDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCall(call: CallRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<ScorePoint>)

    /** One call and its chart points, all or nothing. */
    @Transaction
    suspend fun insert(call: CallRecord, points: List<ScorePoint>) {
        insertCall(call)
        if (points.isNotEmpty()) insertPoints(points)
    }

    @Query("SELECT * FROM CallRecord ORDER BY endedAt DESC")
    fun calls(): Flow<List<CallRecord>>

    @Query("SELECT * FROM CallRecord WHERE id = :id")
    fun call(id: String): Flow<CallRecord?>

    @Query("SELECT * FROM ScorePoint WHERE callId = :callId ORDER BY seq")
    fun points(callId: String): Flow<List<ScorePoint>>

    /** "Delete my history": every call; their points go with them (ON DELETE CASCADE). */
    @Query("DELETE FROM CallRecord")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM ScorePoint")
    suspend fun pointCount(): Int

    @Query("UPDATE CallRecord SET riskiestSentence = NULL")
    suspend fun forgetSentences()
}

@Database(entities = [CallRecord::class, ScorePoint::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class CallDatabase : RoomDatabase() {
    abstract fun calls(): CallDao

    companion object {
        @Volatile private var instance: CallDatabase? = null

        fun get(context: Context): CallDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CallDatabase::class.java, "calls.db")
                .build()
                .also { instance = it }
        }
    }
}
