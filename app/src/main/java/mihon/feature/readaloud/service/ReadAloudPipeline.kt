package mihon.feature.readaloud.service

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import mihon.feature.readaloud.cache.ReadAloudAudioCache
import mihon.feature.readaloud.cleaner.TextCleaner
import mihon.feature.readaloud.client.ElevenLabsClient
import mihon.feature.readaloud.client.ElevenLabsException
import mihon.feature.readaloud.director.AnthropicDirector
import mihon.feature.readaloud.director.RuleBasedDirector
import mihon.feature.readaloud.director.TextDirector
import mihon.feature.readaloud.model.DirectorEngine
import mihon.feature.readaloud.model.TextSide
import mihon.feature.readaloud.model.Turn
import mihon.feature.readaloud.model.VoiceEngine
import mihon.feature.readaloud.ocr.ReadAloudOcr
import mihon.feature.readaloud.player.ReadAloudPlayer
import mihon.feature.readaloud.preferences.ReadAloudPreferences
import mihon.feature.readaloud.splitter.TurnSplitter

class NoReadAloudTextException(message: String) : Exception(message)

@Inject
@SingleIn(AppScope::class)
class ReadAloudPipeline(
    private val context: Context,
    val preferences: ReadAloudPreferences,
    private val networkHelper: NetworkHelper,
) {
    val player = ReadAloudPlayer(context)
    val cache = ReadAloudAudioCache(context)
    private val ocr = ReadAloudOcr()

    private val ruleBasedDirector = RuleBasedDirector()
    private val anthropicDirector = AnthropicDirector(
        client = networkHelper.client,
        apiKeyProvider = { preferences.anthropicApiKey.get() },
        fallbackDirector = ruleBasedDirector,
    )

    val elevenLabsClient = ElevenLabsClient(
        client = networkHelper.client,
        apiKeyProvider = { preferences.elevenLabsApiKey.get() },
    )

    private fun getDirector(): TextDirector {
        return when (DirectorEngine.fromCode(preferences.directorEngine.get())) {
            DirectorEngine.ANTHROPIC -> anthropicDirector
            DirectorEngine.RULE_BASED, DirectorEngine.OFF -> ruleBasedDirector
        }
    }

    /**
     * Extracts text, cleans, and directs into turns without making paid voice API calls.
     * Perfect for the "Text Preview" dialog!
     */
    suspend fun previewPageText(page: ReaderPage): Pair<List<Turn>, Int> = withContext(Dispatchers.IO) {
        val streamFn = page.stream ?: throw IllegalStateException("Page image is still loading...")
        val bitmap = streamFn().use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Failed to decode page image into bitmap")

        try {
            val side = TextSide.fromCode(preferences.textSide.get())
            val rawBlocks = ocr.recognize(bitmap, side)
            if (rawBlocks.isEmpty()) {
                throw NoReadAloudTextException("No text detected on this page.")
            }

            val paragraphs = TextCleaner.cleanAndMergeBlocks(rawBlocks, bitmap.width.toFloat(), bitmap.height.toFloat())
            if (paragraphs.isEmpty()) {
                throw NoReadAloudTextException("No readable text found after noise filtering.")
            }

            val emotions = preferences.emotions.get()
            val multiVoice = preferences.multipleVoices.get()
            val turns = getDirector().direct(paragraphs, emotions, multiVoice)
            val splitTurns = TurnSplitter.splitTurns(turns)

            val totalChars = splitTurns.sumOf { it.text.length }
            Pair(splitTurns, totalChars)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Plays the audio of the page, using ElevenLabs or Android built-in TTS.
     */
    suspend fun readPageAloud(
        page: ReaderPage,
        onSessionComplete: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        val engine = VoiceEngine.fromCode(preferences.voiceEngine.get())
        val apiKey = preferences.elevenLabsApiKey.get().trim()

        val (turns, totalChars) = previewPageText(page)
        if (turns.isEmpty()) {
            throw NoReadAloudTextException("No dialogue or text found to read.")
        }

        // If Android Built-in or no ElevenLabs key provided, use offline TTS
        if (engine == VoiceEngine.ANDROID_BUILTIN || apiKey.isBlank()) {
            val lang = preferences.textLanguage.get()
            withContext(Dispatchers.Main) {
                player.playWithTts(turns, lang, page.number, onSessionComplete)
            }
            return@withContext
        }

        // ElevenLabs Engine:
        val narratorId = preferences.narratorVoiceId.get().ifBlank { ElevenLabsClient.DEFAULT_NARRATOR_VOICE_ID }
        val secondaryId = preferences.secondaryVoiceId.get().ifBlank { ElevenLabsClient.DEFAULT_SECONDARY_VOICE_ID }
        val lang = preferences.textLanguage.get()
        val model = preferences.elevenLabsModel.get().ifBlank { ElevenLabsClient.DEFAULT_MODEL_ID }
        val multiVoice = preferences.multipleVoices.get()

        val cacheKey = ReadAloudAudioCache.computeKey(turns, narratorId, secondaryId, model, lang)
        var audioFile = cache.getAudioFile(cacheKey)

        if (audioFile == null) {
            val targetFile = cache.getTargetAudioFile(cacheKey)
            try {
                audioFile = elevenLabsClient.synthesize(
                    turns = turns,
                    narratorVoiceId = narratorId,
                    secondaryVoiceId = secondaryId,
                    languageCode = lang,
                    modelId = model,
                    multipleVoices = multiVoice,
                    outputFile = targetFile,
                )

                // Update on-device character counter
                val newTotal = preferences.characterCount.get() + totalChars
                preferences.characterCount.set(newTotal)

                // Trim cache if needed
                val maxMb = preferences.audioCacheLimitMb.get().coerceAtLeast(50)
                cache.trimToSize(maxMb * 1024L * 1024L)
            } catch (e: ElevenLabsException) {
                logcat(LogPriority.WARN) { "ElevenLabs synthesis failed (${e.statusCode}): ${e.message}" }
                if (preferences.fallbackToAndroidTts.get()) {
                    withContext(Dispatchers.Main) {
                        context.toast("${e.message} Using built-in voice.", Toast.LENGTH_LONG)
                        player.playWithTts(turns, lang, page.number, onSessionComplete)
                    }
                    return@withContext
                } else {
                    throw e
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "ElevenLabs synthesis unexpected error: ${e.message}" }
                if (preferences.fallbackToAndroidTts.get()) {
                    withContext(Dispatchers.Main) {
                        context.toast("ElevenLabs error: ${e.message}. Using built-in voice.", Toast.LENGTH_LONG)
                        player.playWithTts(turns, lang, page.number, onSessionComplete)
                    }
                    return@withContext
                } else {
                    throw e
                }
            }
        }

        withContext(Dispatchers.Main) {
            player.playAudioFile(audioFile, turns, page.number, onSessionComplete)
        }
    }

    fun stop() {
        player.stop()
    }
}
