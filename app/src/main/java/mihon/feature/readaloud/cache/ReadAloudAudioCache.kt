package mihon.feature.readaloud.cache

import android.content.Context
import mihon.feature.readaloud.model.Turn
import java.io.File
import java.security.MessageDigest

class ReadAloudAudioCache(
    private val context: Context,
) {
    private val cacheDir = File(context.cacheDir, "readaloud").apply { mkdirs() }

    companion object {
        fun computeKey(
            turns: List<Turn>,
            narratorVoiceId: String,
            secondaryVoiceId: String,
            modelId: String,
            languageCode: String,
        ): String {
            val content = buildString {
                append("narrator=").append(narratorVoiceId).append(";")
                append("secondary=").append(secondaryVoiceId).append(";")
                append("model=").append(modelId).append(";")
                append("lang=").append(languageCode).append(";")
                for (turn in turns) {
                    append(turn.speaker).append(":")
                    append(turn.tags.joinToString(",")).append(":")
                    append(turn.text).append(";")
                }
            }
            return sha256(content)
        }

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    fun getAudioFile(key: String): File? {
        val file = File(cacheDir, "$key.mp3")
        return if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else {
            null
        }
    }

    fun getTargetAudioFile(key: String): File {
        return File(cacheDir, "$key.mp3")
    }

    fun getCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearCache(): Long {
        val size = getCacheSizeBytes()
        cacheDir.listFiles()?.forEach { it.delete() }
        return size
    }

    fun trimToSize(maxSizeBytes: Long) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var currentSize = files.sumOf { it.length() }

        for (file in files) {
            if (currentSize <= maxSizeBytes) break
            val length = file.length()
            if (file.delete()) {
                currentSize -= length
            }
        }
    }
}
