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

            var left = (origBox.left * scaleX).coerceIn(0f, output.width.toFloat())
            var right = (origBox.right * scaleX).coerceIn(0f, output.width.toFloat())
            val top = (origBox.top * scaleY).coerceIn(0f, output.height.toFloat())
            val bottom = (origBox.bottom * scaleY).coerceIn(0f, output.height.toFloat())

            var boxWidth = right - left
            val boxHeight = bottom - top
            if (boxWidth < 8f || boxHeight < 8f) continue

            // Google Lens technique: If original text was vertical (tall & narrow),
            // speech bubbles are usually oval/round. Expand width so horizontal English text can fit naturally.
            if (boxHeight > boxWidth * 1.25f) {
                val expandedWidth = max(boxWidth, min(output.width * 0.45f, boxHeight * 0.8f))
                val centerX = (left + right) / 2f
                left = (centerX - expandedWidth / 2f).coerceIn(0f, output.width.toFloat())
                right = (centerX + expandedWidth / 2f).coerceIn(0f, output.width.toFloat())
                boxWidth = right - left
            }

            // 1. Sample interior bubble color to determine light vs dark bubble
            val isLightBackground = isLightBackground(page, origBox)
            val alpha = (style.backgroundOpacity.coerceIn(0.2f, 1.0f) * 255).toInt()

            if (isLightBackground) {
                bgPaint.color = Color.argb(alpha, 255, 255, 255)
                textPaint.color = Color.BLACK
            } else {
                bgPaint.color = Color.argb(alpha, 24, 24, 24)
                textPaint.color = Color.WHITE
            }

            // 2. Draw speech bubble inpainting cover with outward padding to hide original glyphs
            val padOutward = 3f * density
            val coverLeft = (left - padOutward).coerceAtLeast(0f)
            val coverRight = (right + padOutward).coerceAtMost(output.width.toFloat())
            val coverTop = (top - padOutward).coerceAtLeast(0f)
            val coverBottom = (bottom + padOutward).coerceAtMost(output.height.toFloat())
            val radius = min(coverRight - coverLeft, coverBottom - coverTop).coerceIn(6f, 24f) * 0.25f
            canvas.drawRoundRect(coverLeft, coverTop, coverRight, coverBottom, radius, radius, bgPaint)

            // 3. Auto-fit and wrap translated text centered inside bubble
            val innerPadding = 4f * density
            val availWidth = max(10f, (coverRight - coverLeft) - innerPadding * 2f).toInt()
            val availHeight = max(10f, (coverBottom - coverTop) - innerPadding * 2f)

            val layout = fitText(text, textPaint, availWidth, availHeight, style)
            if (layout != null) {
                canvas.save()
                val textX = coverLeft + innerPadding + (availWidth - layout.width) / 2f
                val textY = coverTop + innerPadding + (availHeight - layout.height) / 2f
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

        val cx = box.centerX()
        val cy = box.centerY()
        val w = box.width()
        val h = box.height()

        val samplePoints = listOf(
            Pair(cx, cy),
            Pair(cx - w * 0.2f, cy - h * 0.2f),
            Pair(cx + w * 0.2f, cy + h * 0.2f),
            Pair(cx - w * 0.2f, cy + h * 0.2f),
            Pair(cx + w * 0.2f, cy - h * 0.2f),
        )

        for ((x, y) in samplePoints) {
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
        return avgLuminance >= 130.0
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
