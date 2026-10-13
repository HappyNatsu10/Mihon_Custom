package eu.kanade.tachiyomi.ui.reader.translate.model

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF

data class TextBlock(
    val text: String,
    val boundingBox: RectF,
    val cornerPoints: List<PointF>? = null,
)

data class OverlayStyle(
    val autoFitText: Boolean = true,
    val backgroundOpacity: Float = 0.95f,
    val textColor: Int = Color.BLACK,
    val minTextSizeSp: Float = 8f,
    val maxTextSizeSp: Float = 28f,
)

enum class SourceLanguage(val code: String, val displayName: String, val mlKitLang: String?) {
    AUTO("auto", "Auto-detect", null),
    JAPANESE("ja", "Japanese", "ja"),
    KOREAN("ko", "Korean", "ko"),
    CHINESE("zh", "Chinese", "zh"),
    ENGLISH("en", "English", "en");

    companion object {
        fun fromCode(code: String): SourceLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class TranslationProviderType(val id: String, val displayName: String) {
    ON_DEVICE_MLKIT("mlkit", "On-device (ML Kit)"),
    LLM_GEMINI("gemini", "LLM Vision (Gemini)");

    companion object {
        fun fromId(id: String): TranslationProviderType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ON_DEVICE_MLKIT
        }
    }
}

sealed class TranslationResult {
    data class Success(val originalPageKey: String, val translatedFile: java.io.File) : TranslationResult()
    data class Error(val message: String, val throwable: Throwable? = null) : TranslationResult()
}
