package fr.cvvm.picpiste.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.cart.CartAlert
import java.util.Locale

/**
 * Says the alert out loud (through the car speakers when Android Auto is connected).
 * Speaks when an alert starts or changes, then repeats every [repeatMs] while it lasts.
 */
class AlertSpeaker(context: Context, private val repeatMs: Long = 15_000) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var ready = false
    private var lastKey: String? = null
    private var lastSpokenMs = 0L

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.FRANCE
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    audio.abandonAudioFocusRequest(focus)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    audio.abandonAudioFocusRequest(focus)
                }
            })
            ready = true
        }
    }

    fun update(alert: CartAlert, enabled: Boolean, nowMs: Long) {
        if (alert.level != AlertLevel.DANGER || !enabled) {
            lastKey = null
            return
        }
        val key = alert.title
        if (key == lastKey && nowMs - lastSpokenMs < repeatMs) return
        lastKey = key
        lastSpokenMs = nowMs
        speak(alert.speech)
    }

    fun speak(text: String) {
        if (!ready) return
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
    }

    fun shutdown() = tts.shutdown()
}
