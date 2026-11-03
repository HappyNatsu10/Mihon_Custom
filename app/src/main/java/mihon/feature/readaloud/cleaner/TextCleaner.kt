package mihon.feature.readaloud.cleaner

import mihon.feature.readaloud.model.Paragraph
import mihon.feature.readaloud.model.ReadAloudBlock
import kotlin.math.abs

object TextCleaner {

    private val WATERMARK_KEYWORDS = listOf(
        "deviantart",
        "patreon",
        "twitter.com",
        "pixiv",
        "instagram.com",
        "commissioned by",
        "art by",
        "drawn by",
    )

    fun cleanAndMergeBlocks(
        blocks: List<ReadAloudBlock>,
        imageWidth: Float,
        imageHeight: Float,
    ): List<Paragraph> {
        if (blocks.isEmpty()) return emptyList()

        val minHeight = imageHeight * 0.007f // Ignore tiny noise specks (< 0.7% height)

        // 1. Filter noise and watermarks
        val validBlocks = blocks.filter { block ->
            val height = block.bottom - block.top
            val width = block.right - block.left
            if (height < minHeight || width < 8f) return@filter false

            val text = block.text.trim()
            if (text.isBlank()) return@filter false

            // Check watermarks / signatures near corners
            val isBottomCorner =
                block.bottom > imageHeight * 0.90f &&
                    (block.left < imageWidth * 0.35f || block.right > imageWidth * 0.65f)
            val lower = text.lowercase()
            if (isBottomCorner && WATERMARK_KEYWORDS.any { lower.contains(it) }) {
                return@filter false
            }

            // Low confidence check if present
            if (block.confidence != null && block.confidence < 0.40f) {
                return@filter false
            }

            true
        }

        if (validBlocks.isEmpty()) return emptyList()

        // 2. Sort blocks top-to-bottom, reading order
        val sorted = validBlocks.sortedWith(
            compareBy<ReadAloudBlock> { it.top }
                .thenBy { it.left },
        )

        // 3. Merge adjacent lines into paragraphs
        val paragraphs = mutableListOf<String>()
        var currentParagraph = StringBuilder()
        var lastBottom = -1f
        var lastLeft = -1f
        var lastHeight = -1f

        for (block in sorted) {
            val text = sanitizeRawText(block.text)
            if (text.isBlank()) continue

            val height = block.bottom - block.top
            val isSameParagraph = if (lastBottom > 0f) {
                val verticalGap = block.top - lastBottom
                val lineSpacingThreshold = (lastHeight.takeIf { it > 0f } ?: height) * 1.5f
                val indentDiff = abs(block.left - lastLeft)
                verticalGap in -5f..lineSpacingThreshold && indentDiff < imageWidth * 0.20f
            } else {
                false
            }

            if (isSameParagraph) {
                // Join hyphenated line ends or space
                val currentStr = currentParagraph.toString()
                if (currentStr.endsWith("-")) {
                    currentParagraph.setLength(currentParagraph.length - 1)
                    currentParagraph.append(text)
                } else {
                    currentParagraph.append(" ").append(text)
                }
            } else {
                if (currentParagraph.isNotBlank()) {
                    paragraphs.add(currentParagraph.toString().trim())
                }
                currentParagraph = StringBuilder(text)
            }

            lastBottom = block.bottom
            lastLeft = block.left
            lastHeight = height
        }

        if (currentParagraph.isNotBlank()) {
            paragraphs.add(currentParagraph.toString().trim())
        }

        return paragraphs.map { Paragraph(it) }
    }

    /**
     * Fixes common OCR artifacts and NEUTRALIZES any brackets to prevent tag injection.
     */
    fun sanitizeRawText(raw: String): String {
        return raw
            // 1. NEUTRALIZE BRACKETS: Replace '[' and ']' with parentheses '(' and ')'
            // so text inside an image cannot act as an ElevenLabs audio emotion tag!
            .replace('[', '(')
            .replace(']', ')')
            // 2. Normalize pipes '|' read as 'I'
            .replace(Regex("""(?<=\s|^)\|(?=\s|$)"""), "I")
            // 3. Normalize multiple whitespace and tabs
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
