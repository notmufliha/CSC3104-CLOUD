package com.example.scamdetect.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import com.example.scamdetect.logic.AlertTone
import java.util.Locale

/**
 * The red warning's sound, vibration and spoken warning, and the yellow banner's single buzz. Same behaviour as
 * iOS UI/Alerts.swift.
 *
 * Red: a long vibration pattern and three beeps on the ALARM stream (alarms play when the phone is on silent), then
 * the warning is read aloud with an English voice (also on the alarm stream). Nothing is spoken while TalkBack is
 * on: TalkBack announces the warning itself, and two voices at once can't be understood.
 */
class Alerts(private val app: Context) : TextToSpeech.OnInitListener {
    /** What to say: the warning, in English. */
    data class Speech(val text: String)

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: Speech? = null
    private var track: AudioTrack? = null

    private val alarm = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Starts the text-to-speech engine early, so the first warning isn't delayed. */
    fun warmUp() {
        if (tts == null) tts = TextToSpeech(app, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.setAudioAttributes(alarm)
        pending?.let { pending = null; if (ttsReady) speakNow(it) }
    }

    val screenReaderOn: Boolean
        get() = app.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    fun red(speech: Speech?) {
        stop()
        vibrate(longArrayOf(0, 700, 250, 700, 250, 1000))
        beep()
        if (speech != null && !screenReaderOn) main.postDelayed({ speak(speech) }, AlertTone.durationMs + 250L)
    }

    /** "Be careful": one short buzz, no sound. */
    fun yellow() = vibrate(longArrayOf(0, 180))

    /** Hang up / close: stop talking and beeping. */
    fun stop() {
        main.removeCallbacksAndMessages(null)
        pending = null
        tts?.stop()
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun speak(s: Speech) {
        val t = tts
        if (t == null || !ttsReady) { pending = s; warmUp(); return }
        speakNow(s)
    }

    private fun speakNow(s: Speech) {
        val t = tts ?: return
        t.language = Locale.UK
        t.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, "scamguard-warning")
    }

    private fun beep() {
        val pcm = AlertTone.pcm()
        runCatching {
            val t = AudioTrack.Builder()
                .setAudioAttributes(alarm)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AlertTone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
        }
    }

    private fun vibrate(pattern: LongArray) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { v?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}
