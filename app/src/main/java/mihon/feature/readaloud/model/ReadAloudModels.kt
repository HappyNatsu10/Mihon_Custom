package mihon.feature.readaloud.model

enum class TextSide(val title: String) {
    AUTO("Auto (Full Page)"),
    LEFT("Left Half"),
    RIGHT("Right Half"),
    ;

    companion object {
        fun fromCode(code: String?): TextSide = entries.find { it.name.equals(code, ignoreCase = true) } ?: AUTO
    }
}

enum class VoiceEngine(val title: String) {
    ELEVENLABS("ElevenLabs (Expressive AI)"),
    ANDROID_BUILTIN("Android Built-in TTS"),
    ;

    companion object {
        fun fromCode(code: String?): VoiceEngine =
            entries.find { it.name.equals(code, ignoreCase = true) } ?: ELEVENLABS
    }
}

enum class DirectorEngine(val title: String) {
    RULE_BASED("Rule-Based (Offline)"),
    ANTHROPIC("Anthropic Claude API"),
    OFF("Off (Narrator Only)"),
    ;

    companion object {
        fun fromCode(code: String?): DirectorEngine =
            entries.find { it.name.equals(code, ignoreCase = true) } ?: RULE_BASED
    }
}

data class ReadAloudBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float? = null,
)

data class Paragraph(
    val text: String,
)

data class Turn(
    val speaker: String,
    val text: String,
    val tags: List<String> = emptyList(),
)

data class ElevenLabsVoice(
    val id: String,
    val name: String,
    val category: String = "",
    val previewUrl: String? = null,
)

enum class ElevenLabsModel(
    val id: String,
    val title: String,
    val description: String,
    val requiresPaid: Boolean = false,
) {
    TURBO_V2_5(
        id = "eleven_turbo_v2_5",
        title = "Turbo v2.5 (Free & Fast)",
        description = "High quality, 50% credit cost (0.5 credits/char). Free tier supported.",
        requiresPaid = false,
    ),
    MULTILINGUAL_V2(
        id = "eleven_multilingual_v2",
        title = "Multilingual v2 (Natural)",
        description = "Expressive & rich cadence, 29 languages. Free tier supported.",
        requiresPaid = false,
    ),
    FLASH_V2_5(
        id = "eleven_flash_v2_5",
        title = "Flash v2.5 (Ultra-Fast)",
        description = "Ultra-low latency (~75ms), 50% credit cost. Free tier supported.",
        requiresPaid = false,
    ),
    ELEVEN_V3(
        id = "eleven_v3",
        title = "Eleven v3 (Dialogue Studio)",
        description = "Conversational dialogue. Requires a paid ElevenLabs subscription.",
        requiresPaid = true,
    ),
    ;

    companion object {
        fun fromId(id: String?): ElevenLabsModel =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: TURBO_V2_5
    }
}

data class ElevenLabsSubscription(
    val tier: String,
    val characterCount: Long,
    val characterLimit: Long,
    val status: String,
    val nextResetUnix: Long? = null,
) {
    val remainingCharacters: Long
        get() = (characterLimit - characterCount).coerceAtLeast(0L)

    val isFreeTier: Boolean
        get() = tier.equals("free", ignoreCase = true)

    val isQuotaExhausted: Boolean
        get() = characterLimit > 0 && characterCount >= characterLimit
}

data class ReadAloudSessionState(
    val isReading: Boolean = false,
    val isPaused: Boolean = false,
    val isPreparing: Boolean = false,
    val currentPage: Int = 0,
    val currentSpeaker: String = "",
    val currentText: String = "",
    val turns: List<Turn> = emptyList(),
    val currentTurnIndex: Int = 0,
    val error: String? = null,
)
