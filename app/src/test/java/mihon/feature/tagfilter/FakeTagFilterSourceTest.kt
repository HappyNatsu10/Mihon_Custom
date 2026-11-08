package mihon.feature.tagfilter

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.tagfilter.SourceTag
import eu.kanade.tachiyomi.source.tagfilter.TagLookupException
import eu.kanade.tachiyomi.source.tagfilter.TagScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import mihon.feature.tagfilter.fake.FakeTagFilterSource
import org.junit.jupiter.api.Test

class FakeTagFilterSourceTest {

    private val source = FakeTagFilterSource()
    private val fakeManga = SManga.create().apply {
        url = "/artist/12345"
        title = "Artist Profile"
    }

    @Test
    fun `getTagScopes returns standard scopes`() = runTest {
        val scopes = source.getTagScopes(fakeManga)
        scopes.size shouldBe 4
        scopes.map { it.id } shouldBe listOf("all", "illust", "manga", "novel")
    }

    @Test
    fun `getTags returns populated tag list for scope`() = runTest {
        val scope = TagScope("all", "All")
        val tags = source.getTags(fakeManga, scope)
        tags.isNotEmpty() shouldBe true
        tags.first().name shouldBe "オリジナル"
        tags.first().translatedName shouldBe "Original"
    }

    @Test
    fun `getTaggedChapterUrls pages through works starting at page 1`() = runTest {
        val scope = TagScope("illust", "Illustration")
        val tag = SourceTag("Fantasy", count = 35)

        val page1 = source.getTaggedChapterUrls(fakeManga, scope, tag, 1)
        page1.chapterUrls.size shouldBe 20
        page1.hasNextPage shouldBe true

        val page2 = source.getTaggedChapterUrls(fakeManga, scope, tag, 2)
        page2.chapterUrls.size shouldBe 15
        page2.hasNextPage shouldBe false

        val page3 = source.getTaggedChapterUrls(fakeManga, scope, tag, 3)
        page3.chapterUrls.isEmpty() shouldBe true
        page3.hasNextPage shouldBe false
    }

    @Test
    fun `simulating login required throws TagLookupException LOGIN_REQUIRED`() = runTest {
        source.simulateLoginRequired = true
        val ex = shouldThrow<TagLookupException> {
            source.getTags(fakeManga, TagScope("all", "All"))
        }
        ex.reason shouldBe TagLookupException.Reason.LOGIN_REQUIRED
    }

    @Test
    fun `simulating rate limited throws TagLookupException RATE_LIMITED`() = runTest {
        source.simulateRateLimited = true
        val ex = shouldThrow<TagLookupException> {
            source.getTags(fakeManga, TagScope("all", "All"))
        }
        ex.reason shouldBe TagLookupException.Reason.RATE_LIMITED
    }

    @Test
    fun `simulating not supported throws TagLookupException NOT_SUPPORTED`() = runTest {
        source.simulateNotSupported = true
        val ex = shouldThrow<TagLookupException> {
            source.getTagScopes(fakeManga)
        }
        ex.reason shouldBe TagLookupException.Reason.NOT_SUPPORTED
    }
}
