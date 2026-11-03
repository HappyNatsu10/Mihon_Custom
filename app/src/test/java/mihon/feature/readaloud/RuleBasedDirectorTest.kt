package mihon.feature.readaloud

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.feature.readaloud.director.RuleBasedDirector
import mihon.feature.readaloud.model.Paragraph
import org.junit.jupiter.api.Test

class RuleBasedDirectorTest {

    private val director = RuleBasedDirector()

    @Test
    fun `directs plain text with no quotes to Narrator`() = runTest {
        val paragraphs = listOf(
            Paragraph("The wind howled through the silent streets at midnight."),
        )

        val turns = director.direct(paragraphs, enableEmotions = true, enableMultipleVoices = true)
        turns.size shouldBe 1
        turns[0].speaker shouldBe "Narrator"
        turns[0].text shouldBe "The wind howled through the silent streets at midnight."
    }

    @Test
    fun `directs quotes with alternating speakers and separates narration`() = runTest {
        val paragraphs = listOf(
            Paragraph("\"Hello there!\" she greeted. \"Welcome home!\" he replied."),
        )

        val turns = director.direct(paragraphs, enableEmotions = false, enableMultipleVoices = true)
        turns.size shouldBe 4
        turns[0].speaker shouldBe "Speaker 1"
        turns[0].text shouldBe "Hello there!"

        turns[1].speaker shouldBe "Narrator"
        turns[1].text shouldBe "she greeted."

        turns[2].speaker shouldBe "Speaker 2"
        turns[2].text shouldBe "Welcome home!"

        turns[3].speaker shouldBe "Narrator"
        turns[3].text shouldBe "he replied."
    }

    @Test
    fun `detects excited emotion for exclamation marks`() = runTest {
        val paragraphs = listOf(
            Paragraph("\"Watch out!\""),
        )

        val turns = director.direct(paragraphs, enableEmotions = true, enableMultipleVoices = true)
        turns.size shouldBe 1
        turns[0].tags shouldContain "excited"
    }

    @Test
    fun `detects shouting emotion for multiple exclamation marks`() = runTest {
        val paragraphs = listOf(
            Paragraph("\"WATCH OUT!!\""),
        )

        val turns = director.direct(paragraphs, enableEmotions = true, enableMultipleVoices = true)
        turns.size shouldBe 1
        turns[0].tags shouldContain "shouting"
    }
}
