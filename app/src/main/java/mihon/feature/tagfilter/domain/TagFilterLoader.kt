package mihon.feature.tagfilter.domain

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.tagfilter.SourceTag
import eu.kanade.tachiyomi.source.tagfilter.TagFilterSource
import eu.kanade.tachiyomi.source.tagfilter.TagScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.feature.tagfilter.cache.TagFilterCache
import tachiyomi.domain.manga.model.TagMatchMode

@Inject
@SingleIn(AppScope::class)
class TagFilterLoader(
    private val cache: TagFilterCache,
) {
    suspend fun getScopes(source: TagFilterSource, manga: SManga): List<TagScope> {
        return try {
            source.getTagScopes(manga)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getTags(
        mangaId: Long,
        source: TagFilterSource,
        manga: SManga,
        scope: TagScope,
        forceRefresh: Boolean = false,
    ): List<SourceTag> {
        if (!forceRefresh) {
            val cached = cache.getTagList(mangaId, scope.id)
            if (cached != null) return cached
        }
        return try {
            val fresh = source.getTags(manga, scope)
            cache.putTagList(mangaId, scope.id, fresh)
            fresh
        } catch (e: Exception) {
            if (forceRefresh) throw e
            val cachedFallback = cache.getTagList(mangaId, scope.id)
            cachedFallback ?: throw e
        }
    }

    suspend fun loadTaggedUrlsForTag(
        mangaId: Long,
        source: TagFilterSource,
        manga: SManga,
        scope: TagScope,
        tagName: String,
        forceRefresh: Boolean = false,
        semaphore: Semaphore = Semaphore(2),
    ): Set<String> {
        if (!forceRefresh) {
            val cached = cache.getTaggedUrls(mangaId, scope.id, tagName)
            if (cached != null) return cached
        }

        return semaphore.withPermit {
            val allUrls = mutableListOf<String>()
            var page = 1
            val tag = SourceTag(name = tagName, count = null)
            val maxPages = 100

            while (page <= maxPages) {
                val taggedPage = source.getTaggedChapterUrls(manga, scope, tag, page)
                allUrls.addAll(taggedPage.chapterUrls)
                if (!taggedPage.hasNextPage) break
                page++
            }

            val urlSet = allUrls.toSet()
            cache.putTaggedUrls(mangaId, scope.id, tagName, urlSet)
            urlSet
        }
    }

    suspend fun loadMatchingUrls(
        mangaId: Long,
        source: TagFilterSource,
        manga: SManga,
        scope: TagScope,
        tagNames: Set<String>,
        matchMode: TagMatchMode,
        forceRefresh: Boolean = false,
    ): Set<String> {
        if (tagNames.isEmpty()) return emptySet()
        val semaphore = Semaphore(2)

        val resultsByTag: Map<String, Set<String>> = coroutineScope {
            tagNames.map { tagName ->
                async {
                    val urls = loadTaggedUrlsForTag(mangaId, source, manga, scope, tagName, forceRefresh, semaphore)
                    tagName to urls
                }
            }.map { it.await() }.toMap()
        }

        return TagFilterLogic.computeMatchingUrls(resultsByTag, matchMode)
    }
}
