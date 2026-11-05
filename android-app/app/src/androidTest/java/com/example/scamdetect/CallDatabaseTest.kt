package com.example.scamdetect

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.data.CallHistory
import com.example.scamdetect.data.db.CallDatabase
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ScorePoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Workstream B.7 on a device: Room, "Delete my history", the one-time move of the old History. */
@RunWith(AndroidJUnit4::class)
class CallDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CallDatabase

    private fun call(id: String, endedAt: Long, sentence: String? = null) = CallRecord(
        id = id, startedAt = endedAt - 60_000, endedAt = endedAt, finalVerdict = "SCAM", liveVerdict = "SCAM",
        topReason = "Asked for your OTP or money", callScore = 0.8f, signalsFired = listOf("money", "impersonation"),
        review = "fallback", riskiestSentence = sentence,
    )

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, CallDatabase::class.java).build()
    }

    @After fun close() = db.close()

    @Test fun savesCallsWithPointsNewestFirstAndDeletesEverything() = runBlocking {
        val dao = db.calls()
        dao.insert(call("a", 1_000), listOf(ScorePoint("a", 1, 1.5f, 0.2f, 0.1f, 0.2f, 0.3f), ScorePoint("a", 3, 4f, 0.7f, 0.9f, 0.95f, 0.8f)))
        dao.insert(call("b", 2_000), emptyList())
        assertEquals(listOf("b", "a"), dao.calls().first().map { it.id })
        assertEquals(listOf(1L, 3L), dao.points("a").first().map { it.seq })
        assertEquals(listOf("money", "impersonation"), dao.call("a").first()!!.signalsFired)

        dao.insert(call("a", 9_999), emptyList())                 // same call saved twice: the first row stays
        assertEquals(1_000L, dao.call("a").first()!!.endedAt)

        dao.deleteAll()
        assertTrue(dao.calls().first().isEmpty())
        assertEquals(0, dao.pointCount())                         // points went with their calls (CASCADE)
    }

    @Test fun turningSavingOffForgetsSavedSentences() = runBlocking {
        val dao = db.calls()
        dao.insert(call("a", 1_000, sentence = "Give me the OTP now"), emptyList())
        dao.forgetSentences()
        assertNull(dao.call("a").first()!!.riskiestSentence)
    }

    @Test fun oldHistoryMovesIntoRoomOnceAndTheKeyIsRemoved() = runBlocking {
        val sp = context.getSharedPreferences("scamguard", Context.MODE_PRIVATE)
        sp.edit().putString(
            "history",
            """[{"timeMillis":1700000000000,"verdict":"SCAM","topReason":"asking for your OTP","combinedScore":0.8},
                {"timeMillis":1699000000000,"verdict":"SAFE","topReason":"no warning signs","combinedScore":0.1}]""",
        ).commit()
        val prefs = AppPrefs(context)
        val history = CallHistory(context, prefs)   // the app's real database file
        history.deleteAll()
        sp.edit().putString("history", sp.getString("history", null)).commit()

        history.migrateLegacy()
        history.migrateLegacy()                     // nothing left to move: no duplicates
        val rows = history.calls.first()
        assertEquals(listOf("SCAM", "SAFE"), rows.map { it.finalVerdict })
        assertTrue(rows.all { it.review == "migrated" })
        assertNull(prefs.legacyHistory())           // the old key is gone
        history.deleteAll()
    }
}
