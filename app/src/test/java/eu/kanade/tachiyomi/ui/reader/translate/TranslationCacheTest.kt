package eu.kanade.tachiyomi.ui.reader.translate

import eu.kanade.tachiyomi.ui.reader.translate.cache.TranslationCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TranslationCacheTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `computeKey is deterministic for identical parameters`() {
        val key1 = TranslationCache.computeKey("manga:1/ch:2/p:0", "ja", "en", "mlkit")
        val key2 = TranslationCache.computeKey("manga:1/ch:2/p:0", "ja", "en", "mlkit")
        assertEquals(key1, key2)
        assertEquals(64, key1.length) // SHA-256 hex string length
    }

    @Test
    fun `computeKey produces distinct keys when target language or provider changes`() {
        val keyEn = TranslationCache.computeKey("manga:1/ch:2/p:0", "ja", "en", "mlkit")
        val keyEs = TranslationCache.computeKey("manga:1/ch:2/p:0", "ja", "es", "mlkit")
        val keyGemini = TranslationCache.computeKey("manga:1/ch:2/p:0", "ja", "en", "gemini", model = "gemini-1.5-flash")

        assertNotEquals(keyEn, keyEs)
        assertNotEquals(keyEn, keyGemini)
    }

    @Test
    fun `cache get returns null when entry does not exist`() {
        val cache = TranslationCache(tempDir)
        assertNull(cache.get("non_existent_key"))
    }

    @Test
    fun `cache get returns existing file and clearCache deletes it`() {
        val cache = TranslationCache(tempDir)
        val key = TranslationCache.computeKey("test_page", "ja", "en", "mlkit")

        // Manually place a test cached file
        val file = File(tempDir, "$key.jpg")
        file.writeBytes(ByteArray(1024) { 1 })

        val retrieved = cache.get(key)
        assertNotNull(retrieved)
        assertTrue(retrieved!!.exists())
        assertEquals(1024L, cache.getCacheSizeBytes())

        val freed = cache.clearCache()
        assertEquals(1024L, freed)
        assertEquals(0L, cache.getCacheSizeBytes())
        assertNull(cache.get(key))
    }
}
