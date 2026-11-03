package mihon.feature.readaloud.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import logcat.logcat
import mihon.feature.readaloud.model.ReadAloudSessionState
import mihon.feature.readaloud.model.Turn
import java.io.File
import java.util.Locale

class ReadAloudPlayer(
    private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    private val _sessionState = MutableStateFlow(ReadAloudSessionState())
    val sessionState: StateFlow<ReadAloudSessionState> = _sessionState.asStateFlow()

    private var onCompletionCallback: (() -> Unit)? = null
    private var currentTurns: List<Turn> = emptyList()
    private var ttsTurnIndex = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _sessionState.value = _sessionState.value.copy(isReading = true, isPaused = false)
                    }

                    override fun onDone(utteranceId: String?) {
                        playNextTtsTurn()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        playNextTtsTurn()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        logcat(LogPriority.WARN) { "TTS utterance error: $errorCode" }
                        playNextTtsTurn()
                    }
                })
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                .build()
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun playAudioFile(
        file: File,
        turns: List<Turn>,
        pageNumber: Int,
        onComplete: () -> Unit,
    ) {
        stop()
        requestAudioFocus()

        onCompletionCallback = onComplete
        currentTurns = turns

        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                setOnCompletionListener {
                    _sessionState.value = _sessionState.value.copy(isReading = false, isPaused = false)
                    onCompletionCallback?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    logcat(LogPriority.ERROR) { "MediaPlayer error: what=$what, extra=$extra" }
                    _sessionState.value = _sessionState.value.copy(
                        isReading = false,
                        isPaused = false,
                        error = "Playback error ($what, $extra)",
                    )
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp

            _sessionState.value = ReadAloudSessionState(
                isReading = true,
                isPaused = false,
                currentPage = pageNumber,
                currentSpeaker = turns.firstOrNull()?.speaker ?: "Narrator",
                currentText = turns.firstOrNull()?.text ?: "",
                turns = turns,
                currentTurnIndex = 0,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to start MediaPlayer: ${e.message}" }
            _sessionState.value = _sessionState.value.copy(
                isReading = false,
                isPaused = false,
                error = "Cannot play audio: ${e.message}",
            )
        }
    }

    fun playWithTts(
        turns: List<Turn>,
        languageTag: String,
        pageNumber: Int,
        onComplete: () -> Unit,
    ) {
        stop()
        requestAudioFocus()

        if (!isTtsInitialized || tts == null) {
            _sessionState.value = _sessionState.value.copy(error = "Android TTS is still initializing...")
            return
        }

        onCompletionCallback = onComplete
        currentTurns = turns
        ttsTurnIndex = 0

        try {
            val locale = Locale.forLanguageTag(languageTag)
            tts?.setLanguage(locale)
        } catch (_: Exception) {
            tts?.language = Locale.getDefault()
        }

        _sessionState.value = ReadAloudSessionState(
            isReading = true,
            isPaused = false,
            currentPage = pageNumber,
            currentSpeaker = turns.firstOrNull()?.speaker ?: "Narrator",
            currentText = turns.firstOrNull()?.text ?: "",
            turns = turns,
            currentTurnIndex = 0,
        )

        playNextTtsTurn()
    }

    private fun playNextTtsTurn() {
        if (ttsTurnIndex >= currentTurns.size) {
            _sessionState.value = _sessionState.value.copy(isReading = false, isPaused = false)
            onCompletionCallback?.invoke()
            return
        }

        val turn = currentTurns[ttsTurnIndex]
        _sessionState.value = _sessionState.value.copy(
            currentSpeaker = turn.speaker,
            currentText = turn.text,
            currentTurnIndex = ttsTurnIndex,
        )

        // Pitch shift for multiple speakers
        if (turn.speaker.contains("2")) {
            tts?.setPitch(1.25f)
            tts?.setSpeechRate(1.05f)
        } else if (turn.speaker.contains("1")) {
            tts?.setPitch(0.9f)
            tts?.setSpeechRate(0.95f)
        } else {
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }

        ttsTurnIndex++
        tts?.speak(turn.text, TextToSpeech.QUEUE_FLUSH, null, "readaloud_${System.currentTimeMillis()}")
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _sessionState.value = _sessionState.value.copy(isPaused = true)
            }
        }
        if (tts?.isSpeaking == true) {
            tts?.stop()
            _sessionState.value = _sessionState.value.copy(isPaused = true)
        }
    }

    fun resume() {
        mediaPlayer?.let {
            it.start()
            _sessionState.value = _sessionState.value.copy(isPaused = false, isReading = true)
        }
        if (_sessionState.value.isPaused && currentTurns.isNotEmpty()) {
            _sessionState.value = _sessionState.value.copy(isPaused = false, isReading = true)
            playNextTtsTurn()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            tts?.stop()
        } catch (_: Exception) {}

        _sessionState.value = ReadAloudSessionState(isReading = false, isPaused = false)
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isTtsInitialized = false
    }
}
