package eu.kanade.tachiyomi.source.tagfilter

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional interface for sources that support artist-level / manga-level tag filtering.
 *
 * Contract Version: v1 (Frozen).
 */
interface TagFilterSource : Source {

    /**
     * Content types the tag list can be scoped to (e.g. All / Illustration / Manga / Novel).
     * Return only scopes that apply to this entry. Empty list = feature unavailable.
     */
    suspend fun getTagScopes(manga: SManga): List<TagScope>

    /**
     * All tags for the entry within a scope, with counts.
     */
    suspend fun getTags(manga: SManga, scope: TagScope): List<SourceTag>

    /**
     * One page (page starts at 1) of works matching a tag within a scope.
     * Returns chapter URLs EXACTLY as the source produces them in SChapter.url,
     * so the app can match them by string equality.
     */
    suspend fun getTaggedChapterUrls(manga: SManga, scope: TagScope, tag: SourceTag, page: Int): TaggedPage
}

/** Stable scope id, e.g. "all", "illust", "manga", "novel". */
data class TagScope(val id: String, val name: String)

/** `name` is the canonical tag text and is what gets passed back to the source. */
data class SourceTag(val name: String, val count: Int?, val translatedName: String? = null)

data class TaggedPage(val chapterUrls: List<String>, val hasNextPage: Boolean, val total: Int? = null)

/** Thrown by sources for expected failures so the app can show a clear message. */
class TagLookupException(val reason: Reason, message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Reason { LOGIN_REQUIRED, RATE_LIMITED, NOT_SUPPORTED, OTHER }
}
