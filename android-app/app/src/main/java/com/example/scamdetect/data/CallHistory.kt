package com.example.scamdetect.data

import android.content.Context
import com.example.scamdetect.data.db.CallDatabase
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.logic.Dashboard
import kotlinx.coroutines.flow.Flow

/** Workstream B.7: the dashboard's history, on the phone only (Room). Nothing here is ever uploaded. */
class CallHistory(context: Context, private val prefs: AppPrefs) {
    private val dao = CallDatabase.get(context).calls()

    val calls: Flow<List<CallRecord>> = dao.calls()
    fun call(id: String): Flow<CallRecord?> = dao.call(id)
    fun points(callId: String): Flow<List<ScorePoint>> = dao.points(callId)

    suspend fun save(call: CallRecord, points: List<ScorePoint>) = dao.insert(call, points)

    /** "Delete my history": every call and its chart points. */
    suspend fun deleteAll() = dao.deleteAll()

    /** "Save the riskiest sentence" turned off: forget the ones already saved too. */
    suspend fun forgetSentences() = dao.forgetSentences()

    /** One-time move of the old SharedPreferences History into Room; the old key is removed afterwards. */
    suspend fun migrateLegacy() {
        val old = prefs.legacyHistory() ?: return
        Dashboard.fromLegacy(old).forEach { dao.insertCall(it) }   // fixed ids: a second run inserts nothing
        prefs.removeLegacyHistory()
    }
}
