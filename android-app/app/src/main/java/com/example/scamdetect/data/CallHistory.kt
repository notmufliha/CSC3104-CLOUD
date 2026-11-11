package com.example.scamdetect.data

import android.content.Context
import com.example.scamdetect.data.db.CallDatabase
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ImplicitLabel
import com.example.scamdetect.data.db.LabelSentence
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.ImplicitLabels
import com.example.scamdetect.logic.Mask
import com.example.scamdetect.ondevice.PhoneTrainer
import kotlinx.coroutines.flow.Flow

/** Workstream B.7: the dashboard's history, on the phone only (Room). Nothing here is ever uploaded. */
class CallHistory(context: Context, private val prefs: AppPrefs) {
    private val db = CallDatabase.get(context)
    private val dao = db.calls()
    private val labelDao = db.labels()

    val calls: Flow<List<CallRecord>> = dao.calls()
    fun call(id: String): Flow<CallRecord?> = dao.call(id)
    fun points(callId: String): Flow<List<ScorePoint>> = dao.points(callId)

    /** G.13: how many implicit labels this phone holds (shown next to the opt-in switch). */
    val labelCount: Flow<Int> = labelDao.labelCount()

    suspend fun save(call: CallRecord, points: List<ScorePoint>) = dao.insert(call, points)

    /** "Delete my history": every call and its chart points, plus G.12's labels and any stored sentences. */
    suspend fun deleteAll() = db.deleteHistory()

    /** "Save the riskiest sentence" turned off: forget the ones already saved too. */
    suspend fun forgetSentences() = dao.forgetSentences()

    /**
     * G.12: keep these labels for a call. The caller's lines are stored with them (masked, see Mask) only while
     * "Help improve detection" is on; otherwise just the labels.
     */
    suspend fun recordLabels(callId: String, labels: List<ImplicitLabels.Label>, callerLines: List<Pair<Long, String>>) {
        if (callId.isEmpty() || labels.isEmpty()) return
        val now = System.currentTimeMillis()
        val added = labels.count { labelDao.insertLabel(ImplicitLabel(callId = callId, label = it.label, source = it.source, time = now)) != -1L }
        if (added > 0 && prefs.helpImproveDetection) {
            labelDao.insertSentences(
                callerLines.filter { it.second.isNotBlank() }.map { (seq, text) -> LabelSentence(callId, seq, Mask.mask(text)) }
            )
        }
    }

    /**
     * G (real-phone training): every labelled call that still has its masked lines, with one label per call.
     * "This was fine" wins (the user said so); otherwise the call's labels must agree, or the call is left out.
     */
    suspend fun labelledCalls(): List<PhoneTrainer.LabelledCall> =
        labelDao.labels().groupBy { it.callId }.mapNotNull { (callId, labels) ->
            val label = when {
                labels.any { it.source == ImplicitLabels.SOURCE_FINE } -> 0
                labels.all { it.label == 1 } -> 1
                labels.all { it.label == 0 } -> 0
                else -> return@mapNotNull null
            }
            val lines = labelDao.sentences(callId).map { it.text }
            if (lines.isEmpty()) null else PhoneTrainer.LabelledCall(label, lines)
        }

    /** "Help improve detection" turned off: delete the stored sentences; the labels (numbers only) stay. */
    suspend fun forgetLabelSentences() = labelDao.deleteSentences()

    /** One-time move of the old SharedPreferences History into Room; the old key is removed afterwards. */
    suspend fun migrateLegacy() {
        val old = prefs.legacyHistory() ?: return
        Dashboard.fromLegacy(old).forEach { dao.insertCall(it) }   // fixed ids: a second run inserts nothing
        prefs.removeLegacyHistory()
    }
}
