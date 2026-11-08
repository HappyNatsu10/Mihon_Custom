package mihon.feature.tagfilter

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.tagfilter.TagLookupException
import eu.kanade.tachiyomi.source.tagfilter.TagScope
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import mihon.feature.tagfilter.cache.TagFilterCache
import mihon.feature.tagfilter.domain.TagFilterLoader
import mihon.feature.tagfilter.fake.FakeTagFilterSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.TagMatchMode
import java.io.File

class TagFilterLoaderTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var cache: TagFilterCache
    private lateinit var fakeSource: FakeTagFilterSource
    private lateinit var loader: TagFilterLoader
    private val manga = SManga.create().apply { url = "/users/12345" }
    private val mangaId = 12345L

    @BeforeEach
    fun setup() {
        cache = TagFilterCache(tempDir)
        fakeSource = FakeTagFilterSource()
        loader = TagFilterLoader(cache)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `getScopes returns scopes from source`() = runTest {
        val scopes = loader.getScopes(fakeSource, manga)
        assertEquals(4, scopes.size)
        assertEquals("all", scopes[0].id)
    }

    @Test
    fun `getTags caches source tags on first load and uses cache on subsequent load`() = runTest {
        val scope = TagScope("all", "All")
        val tags = loader.getTags(mangaId, fakeSource, manga, scope)
        assertTrue(tags.isNotEmpty())

        // Verify cached
        val cached = cache.getTagList(mangaId, scope.id)
        assertEquals(tags.size, cached?.size)

        // Make fake source fail, loader should still succeed using cached tags
        fakeSource.simulateLoginRequired = true
        val tagsAfterFail = loader.getTags(mangaId, fakeSource, manga, scope, forceRefresh = false)
        assertEquals(tags.size, tagsAfterFail.size)

        // If force refresh, it should throw
        shouldThrow<TagLookupException> {
            loader.getTags(mangaId, fakeSource, manga, scope, forceRefresh = true)
        }
    }

    @Test
    fun `loadMatchingUrls computes intersection in ALL mode and union in ANY mode`() = runTest {
        val scope = TagScope("all", "All")
        val selectedTags = setOf("Original", "Fantasy")

        val allUrls = loader.loadMatchingUrls(mangaId, fakeSource, manga, scope, selectedTags, TagMatchMode.ALL)
        val anyUrls = loader.loadMatchingUrls(mangaId, fakeSource, manga, scope, selectedTags, TagMatchMode.ANY)

        assertFalse(allUrls.isEmpty())
        assertTrue(anyUrls.size >= allUrls.size)
        assertTrue(anyUrls.containsAll(allUrls))
    }
}
