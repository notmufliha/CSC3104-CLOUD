package com.example.scamdetect.data

import android.content.Context
import com.example.scamdetect.logic.DevLog
import com.example.scamdetect.ondevice.OnDeviceScorer
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/** The Developer view's memory for this run of the app (logic/Developer.kt). In memory only. */
object Dev {
    val log = DevLog()
}

/**
 * Copies each gateway request and reply into the Developer view's log, masked (DevLog.add masks the bodies). Does
 * nothing unless the view is unlocked. Never changes the request or the reply: the reply body is only peeked.
 */
object DevHttpLog : Interceptor {
    private const val MAX_BODY = 64L * 1024

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!Dev.log.recording) return chain.proceed(request)
        val sent = runCatching { Buffer().also { request.body?.writeTo(it) }.readUtf8() }.getOrDefault("")
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Dev.log.add(System.currentTimeMillis(), request.method, request.url.encodedPath, 0,
                (System.nanoTime() - start) / 1_000_000, sent, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            throw e
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val body = runCatching { response.peekBody(MAX_BODY).string() }.getOrDefault("")
        Dev.log.add(System.currentTimeMillis(), request.method, request.url.encodedPath, response.code, ms, sent, body)
        return response
    }
}

/**
 * Developer view → "Reset app to first launch": the phone as after a new install. Clears the database (all 4
 * tables), every setting (the Developer view's flag too), the PIN, the downloaded models and the in-memory logs.
 * The models and scripts built into the app are not touched.
 */
object AppReset {
    suspend fun clearAll(context: Context, history: CallHistory, prefs: AppPrefs, pins: PinStore) {
        history.deleteAll()
        prefs.clearAll()
        pins.clear()
        ModelUpdater.dir(context).deleteRecursively()
        OnDeviceScorer.reload()
        Dev.log.clear()
        Dev.log.recording = false
    }
}
