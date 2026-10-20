package dev.viveknshah.payannouncer.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.viveknshah.payannouncer.parser.PaymentRecord
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * One Hindi speaker per process.
 *
 * A new announcement uses `QUEUE_FLUSH`, so a ₹500 payment does not wait
 * behind five stale ₹10 announcements. The engine reports a missing Hindi
 * voice instead of silently speaking English.
 *
 * TTS calls are marshalled to the main looper; ledger work stays on its own
 * dispatcher.
 */
class HindiTtsEngine(context: Context) {

    enum class Status {
        INITIALIZING,
        READY,
        HINDI_MISSING,
        ERROR,
    }

    @Volatile
    var status: Status = Status.INITIALIZING
        private set

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val counter = AtomicLong()

    private var engine: TextToSpeech? = null
    private var currentUtterance: String? = null

    private val focusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { }
        .build()

    init {
        main.post { startEngine() }
    }

    /** Speak one ledger record, or stay silent for duplicates/excluded items. */
    fun announce(record: PaymentRecord) {
        HindiAnnouncement.text(record)?.let(::speak)
    }

    /** Speak arbitrary text with latest-wins queueing. */
    fun speak(text: String) {
        if (text.isBlank()) return
        main.post {
            val tts = engine
            if (status != Status.READY || tts == null) {
                Log.w(TAG, "Hindi TTS is not ready; announcement skipped")
                return@post
            }
            val utteranceId = "pay-${counter.incrementAndGet()}"
            currentUtterance = utteranceId
            audioManager?.requestAudioFocus(focusRequest)
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                currentUtterance = null
                abandonFocus()
            }
        }
    }

    fun speakVoiceCheck() {
        speak("नमस्ते। भुगतान की आवाज़ तैयार है।")
    }

    fun shutdown() {
        main.post {
            currentUtterance = null
            engine?.stop()
            engine?.shutdown()
            engine = null
            status = Status.ERROR
            abandonFocus()
        }
    }

    private fun startEngine() {
        val created = TextToSpeech(appContext) { code ->
            main.post { finishInit(code) }
        }
        engine = created
    }

    private fun finishInit(code: Int) {
        val tts = engine
        if (code != TextToSpeech.SUCCESS || tts == null) {
            Log.e(TAG, "TextToSpeech init failed: $code")
            status = Status.ERROR
            return
        }

        val language = tts.setLanguage(Locale.forLanguageTag("hi-IN"))
        if (language == TextToSpeech.LANG_MISSING_DATA ||
            language == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "Hindi voice is missing on this device")
            status = Status.HINDI_MISSING
            return
        }

        tts.setSpeechRate(0.95f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == currentUtterance) {
                        currentUtterance = null
                        abandonFocus()
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == currentUtterance) {
                        currentUtterance = null
                        abandonFocus()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onError(utteranceId)
                }
            },
        )
        status = Status.READY
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = "HindiTts"
    }
}
