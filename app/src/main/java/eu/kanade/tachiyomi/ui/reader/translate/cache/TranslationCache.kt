package eu.kanade.tachiyomi.ui.reader.translate.cache

import android.content.Context
import android.graphics.Bitmap
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class TranslationCache(
    private val cacheDir: File,
) {
    constructor(context: Context) : this(File(context.cacheDir, "reader_translate"))

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    companion object {
        const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
        const val TARGET_CACHE_SIZE_BYTES = 400L * 1024 * 1024 // 400 MB
        const val RENDERER_VERSION = 1

        fun computeKey(
            pageIdentity: String,
            sourceLang: String,
            targetLang: String,
            provider: String,
            model: String = "",
            rendererVersion: Int = RENDERER_VERSION,
        ): String {
            val raw = "$pageIdentity|$sourceLang|$targetLang|$provider|$model|v$rendererVersion"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    @Synchronized
    fun get(key: String): File? {
        val file = File(cacheDir, "$key.jpg")
        return if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else {
            null
        }
    }

    @Synchronized
    fun put(key: String, bitmap: Bitmap): File {
        pruneIfNeeded()
        val targetFile = File(cacheDir, "$key.jpg")
        val tempFile = File(cacheDir, "$key.tmp")
        try {
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.flush()
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile.setLastModified(System.currentTimeMillis())
            return targetFile
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to cache translated bitmap: ${e.message}" }
            tempFile.delete()
            throw e
        }
    }

    @Synchronized
    fun clearCache(): Long {
        var freed = 0L
        cacheDir.listFiles()?.forEach { file ->
            freed += file.length()
            file.delete()
        }
        return freed
    }

    fun getCacheSizeBytes(): Long {
        var size = 0L
        cacheDir.listFiles()?.forEach { file ->
            size += file.length()
        }
        return size
    }

    @Synchronized
    private fun pruneIfNeeded(
        maxSize: Long = MAX_CACHE_SIZE_BYTES,
        targetSize: Long = TARGET_CACHE_SIZE_BYTES,
    ) {
        val files = cacheDir.listFiles() ?: return
        var currentSize = files.sumOf { it.length() }
        if (currentSize <= maxSize) return

        val sortedFiles = files.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            if (currentSize <= targetSize) break
            val length = file.length()
            if (file.delete()) {
                currentSize -= length
            }
        }
    }
}
