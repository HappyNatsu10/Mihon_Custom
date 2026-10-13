package eu.kanade.tachiyomi.ui.reader.translate.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.tachiyomi.ui.reader.translate.model.OverlayStyle
import eu.kanade.tachiyomi.ui.reader.translate.model.TextBlock
import logcat.LogPriority
import logcat.logcat
import kotlin.math.max
import kotlin.math.min

class DefaultPageRenderer(
    private val density: Float = 2.0f,
) : PageRenderer {

    override fun render(
        page: Bitmap,
        blocks: List<TextBlock>,
        translated: List<String>,
        style: OverlayStyle,
    ): Bitmap {
        if (blocks.isEmpty() || translated.isEmpty()) {
            return page
        }

        val output = try {
            page.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            logcat(LogPriority.WARN) { "OOM allocating full ARGB_8888 copy, attempting scaled rendering: ${e.message}" }
            val scaled = Bitmap.createScaledBitmap(page, (page.width * 0.75f).toInt(), (page.height * 0.75f).toInt(), true)
            scaled.copy(Bitmap.Config.ARGB_8888, true)
        }

        val canvas = Canvas(output)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val scaleX = output.width.toFloat() / page.width.toFloat()
        val scaleY = output.height.toFloat() / page.height.toFloat()

        for (i in blocks.indices) {
            val text = translated.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
            val origBox = blocks[i].boundingBox

            val left = (origBox.left * scaleX).coerceIn(0f, output.width.toFloat())
            val right = (origBox.right * scaleX).coerceIn(0f, output.width.toFloat())
            val top = (origBox.top * scaleY).coerceIn(0f, output.height.toFloat())
            val bottom = (origBox.bottom * scaleY).coerceIn(0f, output.height.toFloat())

            val boxWidth = right - left
            val boxHeight = bottom - top
            if (boxWidth < 10f || boxHeight < 10f) continue

            // 1. Sample surrounding/background color to determine light vs dark bubble
            val isLightBackground = isLightBackground(page, origBox)
            val alpha = (style.backgroundOpacity.coerceIn(0.1f, 1.0f) * 255).toInt()

            if (isLightBackground) {
                bgPaint.color = Color.argb(alpha, 255, 255, 255)
                textPaint.color = Color.BLACK
            } else {
                bgPaint.color = Color.argb(alpha, 24, 24, 24)
                textPaint.color = Color.WHITE
            }

            // 2. Draw rounded speech-bubble background cover
            val radius = min(boxWidth, boxHeight).coerceIn(4f, 16f) * 0.2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)

            // 3. Auto-fit and wrap translated text
            val padding = 4f * density
            val availWidth = max(10f, boxWidth - padding * 2f).toInt()
            val availHeight = max(10f, boxHeight - padding * 2f)

            val layout = fitText(text, textPaint, availWidth, availHeight, style)
            if (layout != null) {
                canvas.save()
                val textX = left + padding + (availWidth - layout.width) / 2f
                val textY = top + padding + (availHeight - layout.height) / 2f
                canvas.translate(textX, textY)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        return output
    }

    private fun isLightBackground(bitmap: Bitmap, box: android.graphics.RectF): Boolean {
        var totalLuminance = 0.0
        var count = 0

        val points = listOf(
            Pair(box.left, box.top),
            Pair(box.right, box.top),
            Pair(box.left, box.bottom),
            Pair(box.right, box.bottom),
            Pair((box.left + box.right) / 2f, box.top),
            Pair((box.left + box.right) / 2f, box.bottom),
        )

        for ((x, y) in points) {
            val px = x.toInt().coerceIn(0, bitmap.width - 1)
            val py = y.toInt().coerceIn(0, bitmap.height - 1)
            try {
                val color = bitmap.getPixel(px, py)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b)
                count++
            } catch (_: Exception) {}
        }

        val avgLuminance = if (count > 0) totalLuminance / count else 255.0
        return avgLuminance >= 120.0
    }

    private fun fitText(
        text: String,
        paint: TextPaint,
        availWidth: Int,
        availHeight: Float,
        style: OverlayStyle,
    ): StaticLayout? {
        val minSize = style.minTextSizeSp * density
        val maxSize = min(style.maxTextSizeSp * density, availHeight)

        var low = minSize
        var high = max(minSize, maxSize)
        var bestLayout: StaticLayout? = null

        while (high - low > 0.5f) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val layout = createStaticLayout(text, paint, availWidth)
            if (layout.height <= availHeight) {
                bestLayout = layout
                low = mid
            } else {
                high = mid
            }
        }

        if (bestLayout == null) {
            paint.textSize = minSize
            bestLayout = createStaticLayout(text, paint, availWidth)
        }
        return bestLayout
    }

    private fun createStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        val safeWidth = max(1, width)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                safeWidth,
                Layout.Alignment.ALIGN_CENTER,
                1.0f,
                0.0f,
                false,
            )
        }
    }
}
