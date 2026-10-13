package eu.kanade.tachiyomi.ui.reader.translate.service

import android.graphics.Bitmap
import eu.kanade.tachiyomi.ui.reader.translate.model.OverlayStyle
import eu.kanade.tachiyomi.ui.reader.translate.model.TextBlock

interface PageRenderer {
    fun render(
        page: Bitmap,
        blocks: List<TextBlock>,
        translated: List<String>,
        style: OverlayStyle,
    ): Bitmap
}
