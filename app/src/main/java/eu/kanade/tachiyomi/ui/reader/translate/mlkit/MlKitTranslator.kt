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

    private fun detectLanguageTag(text: String): String {
        var hasKana = false
        var hasHangul = false
        var hasHanzi = false
        var hasLatin = false

        for (ch in text) {
            val code = ch.code
            when {
                (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) -> hasKana = true
                (code in 0xAC00..0xD7AF) || (code in 0x1100..0x11FF) || (code in 0x3130..0x318F) -> hasHangul = true
                (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) -> hasHanzi = true
                (code in 0x41..0x5A) || (code in 0x61..0x7A) -> hasLatin = true
            }
        }

        return when {
            hasHangul -> TranslateLanguage.KOREAN
            hasKana -> TranslateLanguage.JAPANESE
            hasHanzi -> TranslateLanguage.CHINESE
            hasLatin -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.JAPANESE
        }
    }

    private fun resolveSourceLanguageTag(from: SourceLanguage, textSample: String): String {
        return when (from) {
            SourceLanguage.JAPANESE -> TranslateLanguage.JAPANESE
            SourceLanguage.CHINESE -> TranslateLanguage.CHINESE
            SourceLanguage.KOREAN -> TranslateLanguage.KOREAN
            SourceLanguage.ENGLISH -> TranslateLanguage.ENGLISH
            SourceLanguage.AUTO -> detectLanguageTag(textSample)
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

        val allSample = blocks.joinToString(" ")
        val sourceTag = resolveSourceLanguageTag(from, allSample)
        val targetTag = resolveTargetLanguageTag(to)

        if (sourceTag == targetTag) {
            return@withContext blocks
        }

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
                val normalized = normalizeMangaTextForTranslation(blockText)
                if (normalized.isBlank()) {
                    ""
                } else {
                    try {
                        val translated = Tasks.await(translator.translate(normalized))
                        postProcessTranslatedText(translated)
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

    private fun normalizeMangaTextForTranslation(text: String): String {
        var clean = text.trim()
        if (clean.isBlank()) return ""

        clean = clean.replace("ー{2,}".toRegex(), "ー")
        clean = clean.replace("〜{2,}".toRegex(), "〜")
        clean = clean.replace("[!！]{2,}".toRegex(), "！")
        clean = clean.replace("[?？]{2,}".toRegex(), "？")
        clean = clean.replace("[.．…]{2,}".toRegex(), "…")

        if (clean.endsWith("っ") || clean.endsWith("ッ")) {
            clean = clean.dropLast(1) + "！"
        }

        return clean
    }

    private fun postProcessTranslatedText(text: String): String {
        var res = text.trim()
        if (res.isBlank()) return ""
        if (res[0].isLowerCase()) {
            res = res.replaceFirstChar { it.uppercase() }
        }
        return res
    }
}
