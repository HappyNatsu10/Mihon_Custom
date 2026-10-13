package eu.kanade.tachiyomi.ui.reader.translate.service

import eu.kanade.tachiyomi.ui.reader.translate.model.SourceLanguage

interface TextTranslator {
    suspend fun translate(
        blocks: List<String>,
        from: SourceLanguage,
        to: String,
    ): List<String>
}
