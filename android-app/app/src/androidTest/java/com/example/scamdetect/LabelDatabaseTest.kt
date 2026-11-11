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
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.ImplicitLabels
import com.example.scamdetect.logic.Reasons
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

    // ------------------------------------------------------------ migrations (1 -> 2 -> 3)

    /**
     * Creates a real database at an old `version` from its exported schema (schemas/.../<version>.json, in the test
     * assets) and runs `fill` on it. Opening it with the app's Room then runs the migrations up to the current
     * version and Room checks every table against the current schema itself, so a wrong migration fails.
     * (No room-testing: its serializers need a newer kotlinx-serialization than the app.)
     */
    private fun oldDatabase(name: String, version: Int, fill: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.example.scamdetect.data.db.CallDatabase/$version.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (k in 0 until indices.length()) db.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            fill(db)
            db.version = version
        }
    }

    /** The app's own builder (same migrations as CallDatabase.get). */
    private fun openMigrated(name: String) =
        Room.databaseBuilder(context, CallDatabase::class.java, name).addMigrations(CallDatabase.MIGRATION_2_3).build()

    /** Workstream B's version 1: calls survive both migrations, become "cloud", and get a reason code. */
    @Test fun version1CallsSurviveAndBecomeCloud() {
        val name = "migration-test-v1.db"
        oldDatabase(name, 1) { v1 ->
            v1.execSQL(
                "INSERT INTO CallRecord (id, startedAt, endedAt, finalVerdict, liveVerdict, topReason, callScore, " +
                    "signalsFired, review, riskiestSentence) VALUES ('old', 1, 2, 'SCAM', 'SCAM', 'asking for your OTP', " +
                    "0.8, 'money', 'bert', NULL)"
            )
            v1.execSQL("INSERT INTO ScorePoint (callId, seq, secondsIntoCall, combined, urgency, money, impersonation) VALUES ('old', 1, 1.5, 0.7, 0.1, 0.9, 0.2)")
        }
        val migrated = openMigrated(name)
        runBlocking {
            val row = migrated.calls().call("old").first()!!
            assertEquals("SCAM", row.finalVerdict)
            assertEquals(listOf("money"), row.signalsFired)
            assertEquals("cloud", row.privacyLevel)               // everything before G ran in the cloud
            assertEquals(Reasons.MONEY, row.reasonCode)
            assertEquals(1, migrated.calls().points("old").first().size)
            assertTrue(migrated.labels().labels().isEmpty())
            assertEquals(3, migrated.openHelper.readableDatabase.version)
        }
        migrated.close()
        context.deleteDatabase(name)
    }

    /**
     * Version 2 (before reason codes): every old wording, the app's and the server's, gets the same code, so the
     * dashboard shows one wording and counts them together; an unknown text keeps showing as saved.
     */
    @Test fun version2ReasonsBecomeCodesShownInTheServersWording() {
        val name = "migration-test-v2.db"
        val rows = listOf(
            "a" to "asking for your OTP",                                     // the app's (levels 2/3)
            "b" to "Asked for your OTP or money",                             // the server's (Cloud)
            "c" to "asking you to transfer money",
            "d" to "pretending to be an official (police, bank, government)",
            "e" to "Claims to be police, a bank or the government",
            "f" to "pressuring you to act right now",
            "g" to "several small warning signs together",
            "h" to "no warning signs",
            "i" to "server not reached",
            "j" to "Warning signs earlier in the call",
            "k" to "some reason nobody knows",
            "l" to "it's got 'quotes'",
        )
        oldDatabase(name, 2) { v2 ->
            rows.forEach { (id, reason) ->
                v2.execSQL(
                    "INSERT INTO CallRecord (id, startedAt, endedAt, finalVerdict, liveVerdict, topReason, callScore, " +
                        "signalsFired, review, riskiestSentence, privacyLevel) VALUES (?, 1, 2, 'SCAM', 'SCAM', ?, 0.8, 'money', " +
                        "'fallback', NULL, 'on_device')",
                    arrayOf<Any>(id, reason),
                )
            }
        }
        val migrated = openMigrated(name)
        runBlocking {
            val byId = migrated.calls().calls().first().associateBy { it.id }
            val codes = rows.associate { (id, _) -> id to byId.getValue(id).reasonCode }
            assertEquals(
                mapOf("a" to "money", "b" to "money", "c" to "money", "d" to "impersonation", "e" to "impersonation",
                    "f" to "urgency", "g" to "several", "h" to "none", "i" to "server_not_reached", "j" to "earlier",
                    "k" to null, "l" to null),
                codes,
            )
            assertEquals("Asked for your OTP or money", Reasons.display(byId.getValue("a")))
            assertEquals("some reason nobody knows", Reasons.display(byId.getValue("k")))   // unknown: as saved
            assertEquals("on_device", byId.getValue("a").privacyLevel)                       // v2 data kept
            // one wording: the app's and the server's money reasons now count together
            val top = Dashboard.topReasons(byId.values.toList())
            assertEquals("Asked for your OTP or money" to 3, top.first())
            assertEquals(3, migrated.openHelper.readableDatabase.version)
        }
        migrated.close()
        context.deleteDatabase(name)
    }
}
