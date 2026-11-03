package mihon.feature.readaloud.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import mihon.feature.readaloud.model.ReadAloudBlock
import mihon.feature.readaloud.model.TextSide

class ReadAloudOcr {

    suspend fun recognize(
        image: Bitmap,
        side: TextSide = TextSide.AUTO,
    ): List<ReadAloudBlock> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // Apply TextSide crop: e.g. for DeviantArt (character on left, text on right)
            val (croppedBitmap, offsetX) = when (side) {
                TextSide.AUTO -> Pair(image, 0f)
                TextSide.LEFT -> {
                    val cropW = (image.width * 0.55f).toInt().coerceIn(1, image.width)
                    Pair(Bitmap.createBitmap(image, 0, 0, cropW, image.height), 0f)
                }
                TextSide.RIGHT -> {
                    val startX = (image.width * 0.45f).toInt().coerceIn(0, image.width - 1)
                    val cropW = (image.width - startX).coerceIn(1, image.width)
                    Pair(Bitmap.createBitmap(image, startX, 0, cropW, image.height), startX.toFloat())
                }
            }

            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
            val visionText = Tasks.await(recognizer.process(inputImage))

            val blocks = mutableListOf<ReadAloudBlock>()
            for (textBlock in visionText.textBlocks) {
                val box = textBlock.boundingBox ?: continue
                blocks.add(
                    ReadAloudBlock(
                        text = textBlock.text,
                        left = box.left.toFloat() + offsetX,
                        top = box.top.toFloat(),
                        right = box.right.toFloat() + offsetX,
                        bottom = box.bottom.toFloat(),
                    ),
                )
            }

            if (croppedBitmap != image && !croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }

            blocks
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "ReadAloud OCR failed: ${e.message}" }
            emptyList()
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {}
        }
    }
}
