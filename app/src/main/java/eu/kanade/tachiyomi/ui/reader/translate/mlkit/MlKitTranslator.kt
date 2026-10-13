package eu.kanade.tachiyomi.ui.reader.translate.mlkit

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.tachiyomi.ui.reader.translate.model.SourceLanguage
import eu.kanade.tachiyomi.ui.reader.translate.service.TextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat

class MlKitTranslator : TextTranslator {

    private fun resolveSourceLanguageTag(from: SourceLanguage): String {
        return when (from) {
            SourceLanguage.JAPANESE -> TranslateLanguage.JAPANESE
            SourceLanguage.CHINESE -> TranslateLanguage.CHINESE
            SourceLanguage.KOREAN -> TranslateLanguage.KOREAN
            SourceLanguage.ENGLISH -> TranslateLanguage.ENGLISH
            SourceLanguage.AUTO -> TranslateLanguage.JAPANESE // default manga source language
        }
    }

    private fun resolveTargetLanguageTag(to: String): String {
        return TranslateLanguage.fromLanguageTag(to) ?: TranslateLanguage.ENGLISH
    }

    override suspend fun translate(
        blocks: List<String>,
        from: SourceLanguage,
        to: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val sourceTag = resolveSourceLanguageTag(from)
        val targetTag = resolveTargetLanguageTag(to)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceTag)
            .setTargetLanguage(targetTag)
            .build()

        val translator = Translation.getClient(options)
        try {
            val conditions = DownloadConditions.Builder().build()
            try {
                Tasks.await(translator.downloadModelIfNeeded(conditions))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to download or load ML Kit translation model: ${e.message}" }
                throw IllegalStateException("Translation model ($sourceTag->$targetTag) not downloaded. Please connect to internet to download language pack.", e)
            }

            blocks.map { blockText ->
                if (blockText.isBlank()) {
                    ""
                } else {
                    try {
                        Tasks.await(translator.translate(blockText))
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN) { "Failed to translate block: $blockText - ${e.message}" }
                        blockText
                    }
                }
            }
        } finally {
            try {
                translator.close()
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Error closing ML Kit translator: ${e.message}" }
            }
        }
    }
}
