package mihon.feature.readaloud.splitter

import mihon.feature.readaloud.model.Turn

object TurnSplitter {

    /**
     * Splits turns that exceed [maxCharsPerTurn] at sentence boundaries,
     * ensuring tags remain attached to the first chunk and ordering is strictly maintained.
     */
    fun splitTurns(
        turns: List<Turn>,
        maxCharsPerTurn: Int = 1800,
    ): List<Turn> {
        if (turns.isEmpty()) return emptyList()

        val result = mutableListOf<Turn>()

        for (turn in turns) {
            val text = turn.text.trim()
            if (text.length <= maxCharsPerTurn) {
                result.add(turn)
                continue
            }

            // Split into sentences (.!?)
            val sentenceRegex = Regex("""(?<=[.!?])\s+""")
            val sentences = text.split(sentenceRegex).filter { it.isNotBlank() }

            var currentChunk = StringBuilder()
            var isFirstChunk = true

            for (sentence in sentences) {
                if (currentChunk.length + sentence.length + 1 > maxCharsPerTurn) {
                    if (currentChunk.isNotEmpty()) {
                        result.add(
                            Turn(
                                speaker = turn.speaker,
                                text = currentChunk.toString().trim(),
                                tags = if (isFirstChunk) turn.tags else emptyList(),
                            ),
                        )
                        isFirstChunk = false
                        currentChunk = StringBuilder()
                    }

                    // If a single sentence is huge, split it on words
                    if (sentence.length > maxCharsPerTurn) {
                        val words = sentence.split(" ")
                        for (word in words) {
                            if (currentChunk.length + word.length + 1 > maxCharsPerTurn) {
                                result.add(
                                    Turn(
                                        speaker = turn.speaker,
                                        text = currentChunk.toString().trim(),
                                        tags = if (isFirstChunk) turn.tags else emptyList(),
                                    ),
                                )
                                isFirstChunk = false
                                currentChunk = StringBuilder()
                            }
                            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                            currentChunk.append(word)
                        }
                    } else {
                        currentChunk.append(sentence)
                    }
                } else {
                    if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                    currentChunk.append(sentence)
                }
            }

            if (currentChunk.isNotEmpty()) {
                result.add(
                    Turn(
                        speaker = turn.speaker,
                        text = currentChunk.toString().trim(),
                        tags = if (isFirstChunk) turn.tags else emptyList(),
                    ),
                )
            }
        }

        return result
    }
}
