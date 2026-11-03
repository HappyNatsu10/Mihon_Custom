package mihon.feature.readaloud.director

import mihon.feature.readaloud.model.Paragraph
import mihon.feature.readaloud.model.Turn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

interface TextDirector {
    suspend fun direct(
        paragraphs: List<Paragraph>,
        enableEmotions: Boolean = true,
        enableMultipleVoices: Boolean = true,
    ): List<Turn>
}

object TagAllowlist {
    val ALLOWED_TAGS = setOf(
        "calm",
        "excited",
        "sad",
        "angry",
        "whispering",
        "laughing",
        "sigh",
        "nervous",
        "sarcastic",
        "curious",
        "shouting",
        "surprised",
        "hesitates",
    )

    fun filterTags(tags: List<String>): List<String> {
        return tags.map { it.lowercase().trim() }.filter { it in ALLOWED_TAGS }
    }
}

class RuleBasedDirector : TextDirector {

    override suspend fun direct(
        paragraphs: List<Paragraph>,
        enableEmotions: Boolean,
        enableMultipleVoices: Boolean,
    ): List<Turn> {
        if (paragraphs.isEmpty()) return emptyList()

        val turns = mutableListOf<Turn>()
        var activeSpeakerIndex = 1

        for (paragraph in paragraphs) {
            val pText = paragraph.text.trim()
            if (pText.isBlank()) continue

            // Split dialogue vs narration using quotes: "...", “...”, '...'
            val quoteRegex = Regex("""["“]([^"”]+)["”]|'([^']+)'""")
            var lastIndex = 0
            val matches = quoteRegex.findAll(pText).toList()

            if (matches.isEmpty()) {
                // Entire paragraph is narration
                val tags = if (enableEmotions) detectEmotions(pText) else emptyList()
                turns.add(Turn(speaker = "Narrator", text = pText, tags = tags))
                continue
            }

            for (match in matches) {
                // Preceding narration
                if (match.range.first > lastIndex) {
                    val narrationText = pText.substring(lastIndex, match.range.first).trim()
                    if (narrationText.isNotBlank()) {
                        val tags = if (enableEmotions) detectEmotions(narrationText) else emptyList()
                        turns.add(Turn(speaker = "Narrator", text = narrationText, tags = tags))
                    }
                }

                // Dialogue turn
                val dialogueText = (match.groups[1]?.value ?: match.groups[2]?.value ?: "").trim()
                if (dialogueText.isNotBlank()) {
                    val speakerName = if (enableMultipleVoices) "Speaker $activeSpeakerIndex" else "Narrator"
                    val tags = if (enableEmotions) detectEmotions(dialogueText) else emptyList()
                    turns.add(Turn(speaker = speakerName, text = dialogueText, tags = tags))

                    // Alternate between Speaker 1 and Speaker 2
                    if (enableMultipleVoices) {
                        activeSpeakerIndex = if (activeSpeakerIndex == 1) 2 else 1
                    }
                }

                lastIndex = match.range.last + 1
            }

            // Trailing narration
            if (lastIndex < pText.length) {
                val trailingNarration = pText.substring(lastIndex).trim()
                if (trailingNarration.isNotBlank()) {
                    val tags = if (enableEmotions) detectEmotions(trailingNarration) else emptyList()
                    turns.add(Turn(speaker = "Narrator", text = trailingNarration, tags = tags))
                }
            }
        }

        return turns
    }

    private fun detectEmotions(text: String): List<String> {
        val detected = mutableListOf<String>()

        when {
            text.contains("?!") || text.contains("!?") -> detected.add("surprised")
            text.contains("...") -> detected.add("hesitates")
            text.contains("!!") -> {
                detected.add("shouting")
                detected.add("excited")
            }
            text.contains("!") -> detected.add("excited")
            text.contains("?") -> detected.add("curious")
        }

        val lower = text.lowercase()
        if (lower.contains("haha") || lower.contains("giggle") || lower.contains("hehe")) {
            detected.add("laughing")
        }
        if (lower.contains("whisper") || (text.startsWith("*") && text.endsWith("*"))) {
            detected.add("whispering")
        }
        if (lower.contains("sigh")) {
            detected.add("sigh")
        }

        // Check shouting: multiple consecutive uppercase words
        val words = text.split(Regex("""\s+""")).filter { it.length >= 2 }
        val uppercaseWords = words.count { it.all { ch -> ch.isUpperCase() || !ch.isLetter() } }
        if (words.size >= 2 && uppercaseWords.toFloat() / words.size > 0.5f) {
            detected.add("shouting")
        }

        return TagAllowlist.filterTags(detected)
    }
}

class AnthropicDirector(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val fallbackDirector: RuleBasedDirector = RuleBasedDirector(),
) : TextDirector {

    override suspend fun direct(
        paragraphs: List<Paragraph>,
        enableEmotions: Boolean,
        enableMultipleVoices: Boolean,
    ): List<Turn> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            return fallbackDirector.direct(paragraphs, enableEmotions, enableMultipleVoices)
        }

        val fullText = paragraphs.joinToString("\n\n") { it.text }
        val prompt = """
            You are a voice director for text extracted from artwork.
            Analyze the following text and split it into conversational dialogue and narration turns.
            For each turn, assign:
            - speaker: "Narrator" for general text, or distinct names like "Speaker 1", "Speaker 2", or character names if mentioned.
            - text: The exact text to speak (do not change or add words).
            - tags: zero or more emotion tags from this exact allow-list ONLY: [calm, excited, sad, angry, whispering, laughing, sigh, nervous, sarcastic, curious, shouting, surprised].

            Return JSON ONLY matching this schema:
            {"turns": [{"speaker": "...", "text": "...", "tags": ["..."]}]}

            Text:
            $fullText
        """.trimIndent()

        return try {
            val jsonBody = JSONObject().apply {
                put("model", "claude-3-haiku-20240307")
                put("max_tokens", 2048)
                put(
                    "messages",
                    org.json.JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            },
                        )
                    },
                )
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return fallbackDirector.direct(paragraphs, enableEmotions, enableMultipleVoices)
                }
                val respBody =
                    response.body?.string()
                        ?: return fallbackDirector.direct(paragraphs, enableEmotions, enableMultipleVoices)
                val respJson = JSONObject(respBody)
                val contentArray = respJson.getJSONArray("content")
                val textContent = contentArray.getJSONObject(0).getString("text")

                // Extract json object from response
                val jsonStart = textContent.indexOf('{')
                val jsonEnd = textContent.lastIndexOf('}')
                if (jsonStart < 0 || jsonEnd <= jsonStart) {
                    return fallbackDirector.direct(paragraphs, enableEmotions, enableMultipleVoices)
                }

                val parsed = JSONObject(textContent.substring(jsonStart, jsonEnd + 1))
                val turnsArray = parsed.getJSONArray("turns")
                val result = mutableListOf<Turn>()

                for (i in 0 until turnsArray.length()) {
                    val turnObj = turnsArray.getJSONObject(i)
                    val rawSpeaker = if (enableMultipleVoices) turnObj.optString("speaker", "Narrator") else "Narrator"
                    val turnText = turnObj.getString("text").trim()
                    val tags = if (enableEmotions) {
                        val tagsArr = turnObj.optJSONArray("tags")
                        val list = mutableListOf<String>()
                        if (tagsArr != null) {
                            for (j in 0 until tagsArr.length()) {
                                list.add(tagsArr.getString(j))
                            }
                        }
                        TagAllowlist.filterTags(list)
                    } else {
                        emptyList()
                    }
                    if (turnText.isNotBlank()) {
                        result.add(Turn(speaker = rawSpeaker, text = turnText, tags = tags))
                    }
                }

                result.ifEmpty { fallbackDirector.direct(paragraphs, enableEmotions, enableMultipleVoices) }
            }
        } catch (_: Exception) {
            fallbackDirector.direct(paragraphs, enableEmotions, enableMultipleVoices)
        }
    }
}
