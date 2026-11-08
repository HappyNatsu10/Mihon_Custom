package mihon.feature.tagfilter.cache

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.tagfilter.SourceTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours

@SingleIn(AppScope::class)
class TagFilterCache(
    private val cacheDir: File,
) {
    @Inject
    constructor(context: Context) : this(File(context.cacheDir, "tag_filter_cache"))

    init {
        cacheDir.mkdirs()
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val TAG_LIST_TTL_MS = 24.hours.inWholeMilliseconds
        val TAGGED_URLS_TTL_MS = 1.hours.inWholeMilliseconds
    }

    @Serializable
    private data class CachedTagList(
        val timestamp: Long,
        val tags: List<CachedSourceTag>,
    )

    @Serializable
    private data class CachedSourceTag(
        val name: String,
        val count: Int?,
        val translatedName: String? = null,
    )

    @Serializable
    private data class CachedTaggedUrls(
        val timestamp: Long,
        val urls: List<String>,
    )

    fun getTagList(
        mangaId: Long,
        scopeId: String,
        maxAgeMs: Long = TAG_LIST_TTL_MS,
    ): List<SourceTag>? {
        val file = File(cacheDir, "tags_${mangaId}_$scopeId.json")
        if (!file.exists()) return null
        return try {
            val cached = json.decodeFromString<CachedTagList>(file.readText())
            if (System.currentTimeMillis() - cached.timestamp > maxAgeMs) {
                file.delete()
                null
            } else {
                cached.tags.map { SourceTag(it.name, it.count, it.translatedName) }
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun putTagList(mangaId: Long, scopeId: String, tags: List<SourceTag>) {
        val file = File(cacheDir, "tags_${mangaId}_$scopeId.json")
        try {
            val cached = CachedTagList(
                timestamp = System.currentTimeMillis(),
                tags = tags.map { CachedSourceTag(it.name, it.count, it.translatedName) },
            )
            file.writeText(json.encodeToString(cached))
        } catch (_: Exception) {}
    }

    fun getTaggedUrls(
        mangaId: Long,
        scopeId: String,
        tagName: String,
        maxAgeMs: Long = TAGGED_URLS_TTL_MS,
    ): Set<String>? {
        val tagHash = hashString(tagName)
        val file = File(cacheDir, "urls_${mangaId}_${scopeId}_$tagHash.json")
        if (!file.exists()) return null
        return try {
            val cached = json.decodeFromString<CachedTaggedUrls>(file.readText())
            if (System.currentTimeMillis() - cached.timestamp > maxAgeMs) {
                file.delete()
                null
            } else {
                cached.urls.toSet()
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun putTaggedUrls(mangaId: Long, scopeId: String, tagName: String, urls: Set<String>) {
        val tagHash = hashString(tagName)
        val file = File(cacheDir, "urls_${mangaId}_${scopeId}_$tagHash.json")
        try {
            val cached = CachedTaggedUrls(
                timestamp = System.currentTimeMillis(),
                urls = urls.toList(),
            )
            file.writeText(json.encodeToString(cached))
        } catch (_: Exception) {}
    }

    fun invalidateManga(mangaId: Long) {
        cacheDir.listFiles { file ->
            file.name.startsWith("tags_${mangaId}_") ||
                file.name.startsWith("urls_${mangaId}_")
        }
            ?.forEach { it.delete() }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
