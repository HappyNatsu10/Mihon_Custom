package eu.kanade.tachiyomi.ui.reader.translate.mlkit

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.kanade.tachiyomi.ui.reader.translate.model.SourceLanguage
import eu.kanade.tachiyomi.ui.reader.translate.model.TextBlock
import eu.kanade.tachiyomi.ui.reader.translate.service.PageOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import kotlin.math.max
import kotlin.math.min

class MlKitOcr : PageOcr {

    private fun createRecognizer(lang: SourceLanguage): TextRecognizer {
        return when (lang) {
            SourceLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            SourceLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            SourceLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            SourceLanguage.ENGLISH, SourceLanguage.AUTO -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    override suspend fun recognize(page: Bitmap, sourceLang: SourceLanguage): List<TextBlock> = withContext(Dispatchers.Default) {
        if (sourceLang != SourceLanguage.AUTO) {
            val recognizer = createRecognizer(sourceLang)
            return@withContext try {
                runRecognition(recognizer, page)
            } finally {
                closeRecognizer(recognizer)
            }
        }

        // When AUTO (like Google Lens), detect script automatically:
        // 1. Try Japanese recognizer first (covers Japanese Kanji, Kana, Latin, digits, symbols)
        val jaRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val jaBlocks = try {
            runRecognition(jaRecognizer, page)
        } finally {
            closeRecognizer(jaRecognizer)
        }

        val allText = jaBlocks.joinToString(" ") { it.text }
        val hasCjkOrKana = allText.any { ch ->
            val code = ch.code
            (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) || (code in 0x4E00..0x9FFF)
        }

        if (jaBlocks.isNotEmpty() && hasCjkOrKana) {
            return@withContext jaBlocks
        }

        // 2. If no CJK found, try Korean recognizer (for Korean manhwa)
        val koRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val koBlocks = try {
            runRecognition(koRecognizer, page)
        } finally {
            closeRecognizer(koRecognizer)
        }

        val hasHangul = koBlocks.joinToString(" ") { it.text }.any { ch ->
            val code = ch.code
            (code in 0xAC00..0xD7AF) || (code in 0x1100..0x11FF) || (code in 0x3130..0x318F)
        }

        if (koBlocks.isNotEmpty() && hasHangul) {
            return@withContext koBlocks
        }

        // 3. Fall back to whichever found text (Japanese/Latin or Korean) or run Latin
        if (jaBlocks.isNotEmpty()) {
            return@withContext jaBlocks
        }
        if (koBlocks.isNotEmpty()) {
            return@withContext koBlocks
        }

        val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            runRecognition(latinRecognizer, page)
        } finally {
            closeRecognizer(latinRecognizer)
        }
    }

    private suspend fun runRecognition(recognizer: TextRecognizer, page: Bitmap): List<TextBlock> {
        return if (page.height > 2500) {
            recognizeTallStrip(recognizer, page)
        } else {
            recognizeBitmap(recognizer, page, offsetY = 0f)
        }
    }

    private fun closeRecognizer(recognizer: TextRecognizer) {
        try {
            recognizer.close()
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Error closing ML Kit recognizer: ${e.message}" }
        }
    }

    private suspend fun recognizeBitmap(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        offsetY: Float,
    ): List<TextBlock> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = Tasks.await(recognizer.process(inputImage))
        return visionText.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val rectF = RectF(
                box.left.toFloat(),
                box.top.toFloat() + offsetY,
                box.right.toFloat(),
                box.bottom.toFloat() + offsetY,
            )
            val cornerPoints = block.cornerPoints?.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat() + offsetY) }
            TextBlock(
                text = block.text.trim(),
                boundingBox = rectF,
                cornerPoints = cornerPoints,
            )
        }
    }

    /**
     * Splits tall webtoon strips into overlapping vertical chunks to respect memory and ML Kit limits,
     * then merges and deduplicates blocks along the seams.
     */
    private suspend fun recognizeTallStrip(
        recognizer: TextRecognizer,
        page: Bitmap,
    ): List<TextBlock> {
        val chunkHeight = 2000
        val overlap = 200
        val step = chunkHeight - overlap

        val allBlocks = mutableListOf<TextBlock>()
        var y = 0
        while (y < page.height) {
            val currentChunkHeight = min(chunkHeight, page.height - y)
            if (currentChunkHeight <= 0) break

            val slice = Bitmap.createBitmap(page, 0, y, page.width, currentChunkHeight)
            try {
                val sliceBlocks = recognizeBitmap(recognizer, slice, offsetY = y.toFloat())
                allBlocks.addAll(sliceBlocks)
            } finally {
                if (!slice.isRecycled && slice != page) {
                    slice.recycle()
                }
            }
            y += step
        }

        return deduplicateOverlappingBlocks(allBlocks)
    }

    private fun deduplicateOverlappingBlocks(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size <= 1) return blocks

        val result = mutableListOf<TextBlock>()
        for (candidate in blocks) {
            val duplicate = result.indexOfFirst { existing ->
                val overlapArea = calculateIntersectionArea(existing.boundingBox, candidate.boundingBox)
                val existingArea = existing.boundingBox.width() * existing.boundingBox.height()
                val candidateArea = candidate.boundingBox.width() * candidate.boundingBox.height()
                val minArea = min(existingArea, candidateArea)
                minArea > 0 && (overlapArea / minArea) > 0.45f
            }

            if (duplicate == -1) {
                result.add(candidate)
            } else {
                // Keep the one with longer text or larger bounding area
                val existing = result[duplicate]
                if (candidate.text.length > existing.text.length) {
                    result[duplicate] = candidate
                }
            }
        }
        return result
    }

    private fun calculateIntersectionArea(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        return if (right > left && bottom > top) {
            (right - left) * (bottom - top)
        } else {
            0f
        }
    }
}
