package eu.kanade.tachiyomi.ui.player.detector

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.player.model.VideoDetectionResult
import tachiyomi.domain.chapter.model.Chapter

/**
 * Interface for detecting whether an online chapter contains video streams instead of images.
 */
interface VideoChapterDetector {
    /**
     * Returns true if chapter is cached as a video, false if cached as not video, or null if uncached.
     */
    fun isCachedVideo(chapterId: Long): Boolean?

    /**
     * Inspects the given chapter and its pages to determine if it is a video chapter.
     */
    suspend fun detect(
        source: HttpSource,
        chapter: Chapter,
        rawPages: List<Page>? = null,
    ): VideoDetectionResult

    /**
     * Clears in-memory session verdict cache.
     */
    fun clearSessionCache()
}
