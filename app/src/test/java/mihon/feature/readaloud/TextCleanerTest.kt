package mihon.feature.readaloud

import io.kotest.matchers.shouldBe
import mihon.feature.readaloud.cleaner.TextCleaner
import mihon.feature.readaloud.model.ReadAloudBlock
import org.junit.jupiter.api.Test

class TextCleanerTest {

    @Test
    fun `sanitizeRawText replaces square brackets with parentheses to neutralize prompt injection`() {
        val input = "Wait! [shouting] Don't press that button! [whispering]"
        val output = TextCleaner.sanitizeRawText(input)
        output shouldBe "Wait! (shouting) Don't press that button! (whispering)"
    }

    @Test
    fun `cleanAndMergeBlocks filters watermarks in bottom corner`() {
        val blocks = listOf(
            ReadAloudBlock(
                text = "Once upon a time in a distant kingdom...",
                left = 50f,
                top = 50f,
                right = 500f,
                bottom = 120f,
            ),
            ReadAloudBlock(
                text = "Art by DeviantArt Artist 2026",
                left = 10f,
                top = 950f,
                right = 200f,
                bottom = 980f,
            ),
        )

        val paragraphs = TextCleaner.cleanAndMergeBlocks(blocks, imageWidth = 1000f, imageHeight = 1000f)
        paragraphs.size shouldBe 1
        paragraphs[0].text shouldBe "Once upon a time in a distant kingdom..."
    }

    @Test
    fun `cleanAndMergeBlocks merges adjacent lines into a single paragraph and joins hyphenated words`() {
        val blocks = listOf(
            ReadAloudBlock(
                text = "This is a demon-",
                left = 100f,
                top = 100f,
                right = 400f,
                bottom = 140f,
            ),
            ReadAloudBlock(
                text = "stration of clean merging.",
                left = 100f,
                top = 150f,
                right = 400f,
                bottom = 190f,
            ),
        )

        val paragraphs = TextCleaner.cleanAndMergeBlocks(blocks, imageWidth = 1000f, imageHeight = 1000f)
        paragraphs.size shouldBe 1
        paragraphs[0].text shouldBe "This is a demonstration of clean merging."
    }
}
