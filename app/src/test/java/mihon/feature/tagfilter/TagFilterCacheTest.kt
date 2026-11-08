package mihon.feature.tagfilter

import eu.kanade.tachiyomi.source.tagfilter.SourceTag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import mihon.feature.tagfilter.cache.TagFilterCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TagFilterCacheTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `putTagList and getTagList stores and retrieves valid cache`() {
        val cache = TagFilterCache(tempDir)
        val tags = listOf(
            SourceTag("Action", 10),
            SourceTag("Fantasy", 20, "ファンタジー"),
        )

        cache.getTagList(1L, "all") shouldBe null
        cache.putTagList(1L, "all", tags)

        val retrieved = cache.getTagList(1L, "all")
        retrieved shouldNotBe null
        retrieved!!.size shouldBe 2
        retrieved[0].name shouldBe "Action"
        retrieved[1].translatedName shouldBe "ファンタジー"
    }

    @Test
    fun `getTagList respects maxAgeMs expiry`() {
        val cache = TagFilterCache(tempDir)
        val tags = listOf(SourceTag("Comedy", 5))
        cache.putTagList(1L, "all", tags)

        // Request with 0ms max age -> expired immediately
        cache.getTagList(1L, "all", maxAgeMs = -1L) shouldBe null
    }

    @Test
    fun `putTaggedUrls and getTaggedUrls stores and retrieves valid cache`() {
        val cache = TagFilterCache(tempDir)
        val urls = setOf("/work/1", "/work/2", "/work/3")

        cache.getTaggedUrls(1L, "illust", "Original") shouldBe null
        cache.putTaggedUrls(1L, "illust", "Original", urls)

        val retrieved = cache.getTaggedUrls(1L, "illust", "Original")
        retrieved shouldBe urls
    }

    @Test
    fun `clearCache removes all cached files`() {
        val cache = TagFilterCache(tempDir)
        cache.putTagList(1L, "all", listOf(SourceTag("Tag1", 1)))
        cache.putTaggedUrls(1L, "all", "Tag1", setOf("/work/1"))

        cache.clearCache()
        cache.getTagList(1L, "all") shouldBe null
        cache.getTaggedUrls(1L, "all", "Tag1") shouldBe null
    }
}
