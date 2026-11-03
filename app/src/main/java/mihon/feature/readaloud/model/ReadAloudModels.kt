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
