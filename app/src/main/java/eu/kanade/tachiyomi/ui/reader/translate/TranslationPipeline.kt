package eu.kanade.tachiyomi.ui.reader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.translate.cache.TranslationCache
import eu.kanade.tachiyomi.ui.reader.translate.gemini.GeminiVisionTranslator
import eu.kanade.tachiyomi.ui.reader.translate.mlkit.MlKitOcr
import eu.kanade.tachiyomi.ui.reader.translate.mlkit.MlKitTranslator
import eu.kanade.tachiyomi.ui.reader.translate.model.OverlayStyle
import eu.kanade.tachiyomi.ui.reader.translate.model.SourceLanguage
import eu.kanade.tachiyomi.ui.reader.translate.model.TextBlock
import eu.kanade.tachiyomi.ui.reader.translate.service.DefaultPageRenderer
import eu.kanade.tachiyomi.ui.reader.translate.service.PageOcr
import eu.kanade.tachiyomi.ui.reader.translate.service.PageRenderer
import eu.kanade.tachiyomi.ui.reader.translate.service.TextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import java.io.File

class NoTextFoundException(message: String) : Exception(message)

@Inject
@SingleIn(AppScope::class)
class TranslationPipeline(
    private val context: Context,
    private val readerPreferences: ReaderPreferences,
    private val networkHelper: NetworkHelper,
) {
    val cache = TranslationCache(context)

    private val mlKitOcr: PageOcr by lazy { MlKitOcr() }
    private val mlKitTranslator: TextTranslator by lazy { MlKitTranslator() }
    private val cloudVisionTranslator: eu.kanade.tachiyomi.ui.reader.translate.cloudvision.CloudVisionTranslator by lazy {
        eu.kanade.tachiyomi.ui.reader.translate.cloudvision.CloudVisionTranslator(
            okHttpClient = networkHelper.client,
            apiKeyProvider = { readerPreferences.readerTranslationCloudVisionApiKey.get() },
        )
    }
    private val geminiTranslator: GeminiVisionTranslator by lazy {
        GeminiVisionTranslator(
            okHttpClient = networkHelper.client,
            apiKeyProvider = { readerPreferences.readerTranslationGeminiApiKey.get() },
            modelProvider = { readerPreferences.readerTranslationGeminiModel.get() },
            endpointProvider = { readerPreferences.readerTranslationGeminiEndpoint.get() },
        )
    }
    private val pageRenderer: PageRenderer by lazy {
        DefaultPageRenderer(density = context.resources.displayMetrics.density)
    }

    suspend fun translatePage(page: ReaderPage): File = withContext(Dispatchers.IO) {
        val chapter = page.chapter.chapter
        val pageIdentity = "${chapter.mangaId}_${chapter.id}_${page.index}"

        val provider = readerPreferences.readerTranslationProvider.get()
        val sourceLang = SourceLanguage.fromCode(readerPreferences.readerTranslationSource.get())
        val targetLang = readerPreferences.readerTranslationTarget.get()
        val geminiModel = readerPreferences.readerTranslationGeminiModel.get()
        val opacity = readerPreferences.readerTranslationOverlayOpacity.get()

        val cacheKey = TranslationCache.computeKey(
            pageIdentity = pageIdentity,
            sourceLang = sourceLang.code,
            targetLang = targetLang,
            provider = provider,
            model = if (provider == "gemini") geminiModel else "",
        )

        // 1. Check disk cache
        val existingCachedFile = cache.get(cacheKey)
        if (existingCachedFile != null) {
            logcat(LogPriority.INFO) { "Translation cache hit for key $cacheKey" }
            return@withContext existingCachedFile
        }

        // 2. Load original image stream
        val streamFn = page.stream ?: throw IllegalStateException("Original page image stream is unavailable")
        val originalBitmap = streamFn().use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: throw IllegalStateException("Failed to decode image into bitmap")

        try {
            val blocks: List<TextBlock>
            val translatedTexts: List<String>

            when (provider) {
                "gemini" -> {
                    val pair = geminiTranslator.translatePage(originalBitmap, sourceLang, targetLang)
                    blocks = pair.first
                    translatedTexts = pair.second
                }
                "cloudvision" -> {
                    blocks = cloudVisionTranslator.recognize(originalBitmap)
                    if (blocks.isEmpty()) {
                        throw NoTextFoundException("No text detected on page")
                    }
                    translatedTexts = mlKitTranslator.translate(blocks.map { it.text }, sourceLang, targetLang)
                }
                else -> {
                    blocks = mlKitOcr.recognize(originalBitmap, sourceLang)
                    if (blocks.isEmpty()) {
                        throw NoTextFoundException("No text detected on page")
                    }
                    translatedTexts = mlKitTranslator.translate(blocks.map { it.text }, sourceLang, targetLang)
                }
            }

            if (blocks.isEmpty()) {
                throw NoTextFoundException("No text detected on page")
            }

            page.ocrText = blocks.joinToString("\n") { it.text }
            page.translatedText = translatedTexts.joinToString("\n")

            val renderedBitmap = pageRenderer.render(
                originalBitmap,
                blocks,
                translatedTexts,
                OverlayStyle(backgroundOpacity = opacity),
            )

            val cachedFile = cache.put(cacheKey, renderedBitmap)

            if (renderedBitmap != originalBitmap && !renderedBitmap.isRecycled) {
                renderedBitmap.recycle()
            }

            return@withContext cachedFile
        } finally {
            if (!originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
        }
    }

    fun clearCache(): Long = cache.clearCache()

    fun getCacheSize(): Long = cache.getCacheSizeBytes()
}
