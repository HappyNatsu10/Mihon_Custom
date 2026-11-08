package mihon.feature.tagfilter.fake

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.tagfilter.SourceTag
import eu.kanade.tachiyomi.source.tagfilter.TagFilterSource
import eu.kanade.tachiyomi.source.tagfilter.TagLookupException
import eu.kanade.tachiyomi.source.tagfilter.TagScope
import eu.kanade.tachiyomi.source.tagfilter.TaggedPage
import kotlinx.coroutines.delay
import okhttp3.Response
import kotlin.math.min

/**
 * Fake source implementing [TagFilterSource] with realistic data and configurable
 * simulation switches for testing and development.
 */
@Suppress("DEPRECATION")
class FakeTagFilterSource(
    override val id: Long = FAKE_SOURCE_ID,
    override val name: String = "Fake Tag Filter Source",
) : HttpSource(), TagFilterSource {

    override val baseUrl: String = "https://fake.tagfilter.internal"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true

    // Configurable simulation flags
    var simulatedDelayMs: Long = 0L
    var simulateLoginRequired: Boolean = false
    var simulateRateLimited: Boolean = false
    var simulateNotSupported: Boolean = false
    var simulateError: Boolean = false
    var emptyTags: Boolean = false
    var emptyTaggedUrls: Boolean = false

    companion object {
        const val FAKE_SOURCE_ID = 999999999L

        val DEFAULT_SCOPES = listOf(
            TagScope("all", "All"),
            TagScope("illust", "Illustration"),
            TagScope("manga", "Manga"),
            TagScope("novel", "Novel"),
        )
    }

    private fun checkSimulationFlags() {
        if (simulateLoginRequired) {
            throw TagLookupException(
                TagLookupException.Reason.LOGIN_REQUIRED,
                "Please log in to your account to view tags.",
            )
        }
        if (simulateRateLimited) {
            throw TagLookupException(
                TagLookupException.Reason.RATE_LIMITED,
                "Rate limit exceeded. Please wait a moment and try again.",
            )
        }
        if (simulateNotSupported) {
            throw TagLookupException(
                TagLookupException.Reason.NOT_SUPPORTED,
                "Tag filtering is not supported for this entry.",
            )
        }
        if (simulateError) {
            throw TagLookupException(
                TagLookupException.Reason.OTHER,
                "Simulated source failure.",
            )
        }
    }

    override suspend fun getTagScopes(manga: SManga): List<TagScope> {
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        checkSimulationFlags()
        return DEFAULT_SCOPES
    }

    override suspend fun getTags(manga: SManga, scope: TagScope): List<SourceTag> {
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        checkSimulationFlags()
        if (emptyTags) return emptyList()

        return generateTagsForScope(scope.id)
    }

    override suspend fun getTaggedChapterUrls(
        manga: SManga,
        scope: TagScope,
        tag: SourceTag,
        page: Int,
    ): TaggedPage {
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        checkSimulationFlags()
        if (emptyTaggedUrls) return TaggedPage(emptyList(), hasNextPage = false, total = 0)

        val pageSize = 20
        val totalCount = tag.count ?: 45
        val startIndex = (page - 1) * pageSize

        if (startIndex >= totalCount) {
            return TaggedPage(emptyList(), hasNextPage = false, total = totalCount)
        }

        val endIndex = min(startIndex + pageSize, totalCount)
        val urls = (startIndex until endIndex).map { index ->
            if (index < 5) {
                // Shared works across tags for realistic multi-tag overlap and ALL intersection testing
                "/works/shared_item_$index"
            } else {
                // Predictable deterministic URL that test chapters can match
                "/works/${tag.name.hashCode().toUInt()}_item_$index"
            }
        }

        val hasNext = endIndex < totalCount
        return TaggedPage(
            chapterUrls = urls,
            hasNextPage = hasNext,
            total = totalCount,
        )
    }

    private fun generateTagsForScope(scopeId: String): List<SourceTag> {
        val baseMultiplier = when (scopeId) {
            "illust" -> 3
            "manga" -> 2
            "novel" -> 1
            else -> 4
        }

        return listOf(
            SourceTag("オリジナル", 120 * baseMultiplier, "Original"),
            SourceTag("女の子", 95 * baseMultiplier, "Girl"),
            SourceTag("VOCALOID", 80 * baseMultiplier, "Vocaloid"),
            SourceTag("初音ミク", 65 * baseMultiplier, "Hatsune Miku"),
            SourceTag("東方Project", 50 * baseMultiplier, "Touhou Project"),
            SourceTag("SPY×FAMILY", 45 * baseMultiplier, "Spy x Family"),
            SourceTag("Fantasy", 40 * baseMultiplier),
            SourceTag("Sci-Fi & Cyberpunk", 35 * baseMultiplier),
            SourceTag("Action!", 30 * baseMultiplier),
            SourceTag("Comedy ~4koma~", 25 * baseMultiplier, "4-koma Comedy"),
            SourceTag("Romance", 22 * baseMultiplier),
            SourceTag("Daily Life", 18 * baseMultiplier, "Slice of Life"),
            SourceTag("Landscape", 15 * baseMultiplier),
            SourceTag("Dark / Horror", 10 * baseMultiplier),
            SourceTag("R-18", 8 * baseMultiplier, "Mature"),
            SourceTag("Unusual Tag (100% Match)", 5 * baseMultiplier),
            SourceTag("Rare Character #1", 3 * baseMultiplier),
            SourceTag("Single Work Only", 1),
        )
    }

    // Boilerplate HttpSource implementation (unused for tag filtering)
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList) =
        throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
