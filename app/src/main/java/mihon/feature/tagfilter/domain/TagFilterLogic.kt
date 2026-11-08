package mihon.feature.tagfilter.domain

import tachiyomi.domain.manga.model.TagMatchMode

/**
 * Pure, deterministic set operations for tag filtering.
 * Strictly decoupled from UI and framework classes for unit testing.
 */
object TagFilterLogic {

    /**
     * Computes the matching chapter URLs based on the [matchMode].
     *
     * - [TagMatchMode.ALL]: Intersection of URLs across all selected tags (narrows the list).
     * - [TagMatchMode.ANY]: Union of URLs across all selected tags.
     *
     * If [tagUrls] is empty, returns an empty set.
     */
    fun computeMatchingUrls(
        tagUrls: Map<String, Set<String>>,
        matchMode: TagMatchMode,
    ): Set<String> {
        if (tagUrls.isEmpty()) return emptySet()

        return when (matchMode) {
            TagMatchMode.ALL -> {
                var intersection: Set<String>? = null
                for (urls in tagUrls.values) {
                    intersection = if (intersection == null) {
                        urls
                    } else {
                        intersection.intersect(urls)
                    }
                    if (intersection.isEmpty()) break
                }
                intersection ?: emptySet()
            }
            TagMatchMode.ANY -> {
                val union = mutableSetOf<String>()
                for (urls in tagUrls.values) {
                    union.addAll(urls)
                }
                union
            }
        }
    }

    /**
     * Counts how many works reported by the source matching the tag filter
     * have not been synced into the user's saved chapter list yet.
     */
    fun countUnsyncedWorks(
        matchingUrls: Set<String>,
        savedChapterUrls: Set<String>,
    ): Int {
        if (matchingUrls.isEmpty()) return 0
        return matchingUrls.count { it !in savedChapterUrls }
    }
}
