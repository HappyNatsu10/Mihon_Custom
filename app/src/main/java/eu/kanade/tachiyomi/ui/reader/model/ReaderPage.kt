package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    // CUSTOM: Feature C - In-reader translation
    var translatedFile: File? = null
    var isShowingTranslation: Boolean = false
    var ocrText: String? = null
    var translatedText: String? = null

    fun getEffectiveStream(): (() -> InputStream)? {
        val file = translatedFile
        if (isShowingTranslation && file != null && file.exists()) {
            return { FileInputStream(file) }
        }
        return stream
    }
}
