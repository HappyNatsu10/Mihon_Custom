package eu.kanade.tachiyomi.ui.reader.translate.service

import android.graphics.Bitmap
import eu.kanade.tachiyomi.ui.reader.translate.model.SourceLanguage
import eu.kanade.tachiyomi.ui.reader.translate.model.TextBlock

interface PageOcr {
    suspend fun recognize(page: Bitmap, sourceLang: SourceLanguage): List<TextBlock>
}
