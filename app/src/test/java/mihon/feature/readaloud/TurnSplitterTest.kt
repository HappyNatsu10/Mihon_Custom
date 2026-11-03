package mihon.feature.readaloud

import io.kotest.matchers.shouldBe
import mihon.feature.readaloud.model.Turn
import mihon.feature.readaloud.splitter.TurnSplitter
import org.junit.jupiter.api.Test

class TurnSplitterTest {

    @Test
    fun `leaves turns smaller than limit intact`() {
        val turns = listOf(
            Turn(speaker = "Speaker 1", text = "Short line of dialogue.", tags = listOf("calm")),
            Turn(speaker = "Narrator", text = "Another brief paragraph of description.", tags = emptyList()),
        )

        val split = TurnSplitter.splitTurns(turns, maxCharsPerTurn = 1800)
        split.size shouldBe 2
        split[0].text shouldBe "Short line of dialogue."
        split[0].tags shouldBe listOf("calm")
        split[1].text shouldBe "Another brief paragraph of description."
    }

    @Test
    fun `splits long turn into chunks on sentence boundaries and preserves tags on first chunk only`() {
        val sentence1 = "A".repeat(50) + "."
        val sentence2 = "B".repeat(50) + "."
        val longText = "$sentence1 $sentence2"

        val turns = listOf(
            Turn(speaker = "Narrator", text = longText, tags = listOf("excited")),
        )

        // Split with limit smaller than combined text
        val split = TurnSplitter.splitTurns(turns, maxCharsPerTurn = 60)
        split.size shouldBe 2
        split[0].text shouldBe sentence1
        split[0].tags shouldBe listOf("excited")
        split[0].speaker shouldBe "Narrator"

        split[1].text shouldBe sentence2
        split[1].tags shouldBe emptyList()
        split[1].speaker shouldBe "Narrator"
    }
}
