package eu.kanade.tachiyomi.ui.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import logcat.logcat
import java.util.Locale

class ReaderTtsManager(
    private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _isSpeaking.value = false
                        logcat(LogPriority.WARN) { "TTS utterance error: $errorCode" }
                    }
                })
            } else {
                logcat(LogPriority.ERROR) { "TextToSpeech initialization failed with status $status" }
            }
        }
    }

    fun speak(text: String, languageTag: String? = null) {
        if (!isInitialized || tts == null) {
            logcat(LogPriority.WARN) { "TTS requested before initialization completed" }
            return
        }

        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        tts?.stop()

        if (!languageTag.isNullOrBlank()) {
            try {
                val locale = Locale.forLanguageTag(languageTag)
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fall back to default
                    tts?.language = Locale.getDefault()
                }
            } catch (e: Throwable) {
                tts?.language = Locale.getDefault()
            }
        }

        val utteranceId = "reader_tts_${System.currentTimeMillis()}"
        _isSpeaking.value = true
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Throwable) {}
        _isSpeaking.value = false
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
        isInitialized = false
    }
}
