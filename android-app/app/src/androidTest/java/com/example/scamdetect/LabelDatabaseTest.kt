package com.example.scamdetect

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.data.CallHistory
import com.example.scamdetect.data.db.CallDatabase
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.logic.ImplicitLabels
import com.example.scamdetect.logic.ImplicitLabels.Label
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith

/** Workstream G.12 / G.13 on a device: implicit labels, the opt-in, "Delete my history", and the 1 -> 2 migration. */
@RunWith(AndroidJUnit4::class)
class LabelDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = AppPrefs(context)
    private lateinit var history: CallHistory   // the app's real database file, emptied before and after

    private val lines = listOf(1L to "Hello, this is Officer Tan from SPF.", 3L to "Read me the OTP sent to 91234567.")

    private fun call(id: String) = CallRecord(
        id = id, startedAt = 0, endedAt = 1, finalVerdict = "SCAM", liveVerdict = "SCAM", topReason = "asking for your OTP",
        callScore = 0.8f, signalsFired = listOf("money"), review = "fallback", riskiestSentence = null,
    )

    @Before fun setUp() = runBlocking {
        history = CallHistory(context, prefs)
        history.deleteAll()
    }

    @After fun tearDown() = runBlocking {
        history.deleteAll()
        prefs.helpImproveDetection = false
    }

    private val db get() = CallDatabase.get(context)

    @Test fun optInOffKeepsLabelsOnly() = runBlocking {
        prefs.helpImproveDetection = false
        history.recordLabels("a", listOf(Label(1, ImplicitLabels.SOURCE_HUNG_UP)), lines)
        assertEquals(listOf(1), db.labels().labels().map { it.label })
        assertEquals(0, db.labels().sentenceCount())
    }

    @Test fun optInOnStoresMaskedCallerLinesAndOffDeletesThem() = runBlocking {
        prefs.helpImproveDetection = true
        history.recordLabels("a", listOf(Label(0, ImplicitLabels.SOURCE_FINE)), lines)
        val stored = db.labels().sentences("a")
        assertEquals(listOf(1L, 3L), stored.map { it.seq })
        assertTrue(stored[1].text.contains("[PHONE_NUMBER]"))    // masked with H's Mask
        assertTrue(stored.none { it.text.contains("91234567") })

        history.forgetLabelSentences()                           // switch turned off
        assertEquals(0, db.labels().sentenceCount())
        assertEquals(1, db.labels().labels().size)               // the label (a number) stays
    }

    @Test fun oneLabelPerCallAndSource() = runBlocking {
        repeat(3) { history.recordLabels("a", listOf(Label(0, ImplicitLabels.SOURCE_FINE)), lines) }
        history.recordLabels("a", listOf(Label(1, ImplicitLabels.SOURCE_REVIEW)), lines)
        assertEquals(2, db.labels().labels().size)
    }

    @Test fun deleteMyHistoryAlsoDeletesLabelsAndSentences() = runBlocking {
        prefs.helpImproveDetection = true
        history.save(call("a"), emptyList())
        history.recordLabels("a", listOf(Label(1, ImplicitLabels.SOURCE_HUNG_UP)), lines)
        history.deleteAll()
        assertTrue(history.calls.first().isEmpty())
        assertTrue(db.labels().labels().isEmpty())
        assertEquals(0, db.labels().sentenceCount())
        assertEquals(0, history.labelCount.first())
    }

    @Test fun newCallsRecordTheirPrivacyLevel() = runBlocking {
        history.save(call("a").copy(privacyLevel = "on_device"), emptyList())
        history.save(call("b"), emptyList())
        assertEquals("on_device", history.call("a").first()!!.privacyLevel)
        assertEquals("cloud", history.call("b").first()!!.privacyLevel)
    }

    // ------------------------------------------------------------ migration from Workstream B's version 1

    /**
     * A real version-1 database (B's schema, from schemas/.../1.json in the test assets) with one call in it, opened
     * by the app's Room. Room runs AutoMigration(1 -> 2) and then checks every table against version 2 itself, so a
     * wrong migration fails here. (No room-testing: its serializers need a newer kotlinx-serialization than the app.)
     */
    @Test fun version1CallsSurviveAndBecomeCloud() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.example.scamdetect.data.db.CallDatabase/1.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { v1 ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                v1.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (k in 0 until indices.length()) v1.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) v1.execSQL(setup.getString(i))
            v1.execSQL(
                "INSERT INTO CallRecord (id, startedAt, endedAt, finalVerdict, liveVerdict, topReason, callScore, " +
                    "signalsFired, review, riskiestSentence) VALUES ('old', 1, 2, 'SCAM', 'SCAM', 'asking for your OTP', " +
                    "0.8, 'money', 'bert', NULL)"
            )
            v1.execSQL("INSERT INTO ScorePoint (callId, seq, secondsIntoCall, combined, urgency, money, impersonation) VALUES ('old', 1, 1.5, 0.7, 0.1, 0.9, 0.2)")
            v1.version = schema.getInt("version")
        }
        assertEquals(1, schema.getInt("version"))

        val migrated = Room.databaseBuilder(context, CallDatabase::class.java, name).build()
        runBlocking {
            val row = migrated.calls().call("old").first()!!
            assertEquals("SCAM", row.finalVerdict)
            assertEquals(listOf("money"), row.signalsFired)
            assertEquals("cloud", row.privacyLevel)               // everything before G ran in the cloud
            assertEquals(1, migrated.calls().points("old").first().size)
            assertTrue(migrated.labels().labels().isEmpty())
            assertEquals(2, migrated.openHelper.readableDatabase.version)
        }
        migrated.close()
        context.deleteDatabase(name)
    }
}
