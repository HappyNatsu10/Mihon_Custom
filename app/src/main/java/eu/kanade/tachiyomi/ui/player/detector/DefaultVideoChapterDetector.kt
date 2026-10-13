package eu.kanade.tachiyomi.ui.player.detector

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.player.model.VideoDetectionResult
import eu.kanade.tachiyomi.ui.player.model.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.chapter.model.Chapter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class DefaultVideoChapterDetector(
    private val networkHelper: NetworkHelper,
    private val chapterCache: ChapterCache,
) : VideoChapterDetector {

    private val sessionCache = ConcurrentHashMap<Long, VideoDetectionResult>()

    override fun isCachedVideo(chapterId: Long): Boolean? {
        val cached = sessionCache[chapterId] ?: return null
        return cached is VideoDetectionResult.Video
    }

    override fun clearSessionCache() {
        sessionCache.clear()
    }

    override suspend fun detect(
        source: HttpSource,
        chapter: Chapter,
        rawPages: List<Page>?,
    ): VideoDetectionResult = withContext(Dispatchers.IO) {
        // Return session-cached verdict if available
        sessionCache[chapter.id]?.let { return@withContext it }

        val pages = rawPages ?: try {
            chapterCache.getPageListFromCache(chapter)
        } catch (_: Throwable) {
            try {
                source.getPageList(chapter.toSChapter())
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR) { "Failed to get page list for video detection: ${e.message}" }
                emptyList()
            }
        }

        if (pages.isEmpty()) {
            val result = VideoDetectionResult.NotVideo
            sessionCache[chapter.id] = result
            return@withContext result
        }

        val firstPage = pages.first()
        val firstUrl = resolvePageUrl(source, firstPage)
        if (firstUrl == null) {
            val result = VideoDetectionResult.NotVideo
            sessionCache[chapter.id] = result
            return@withContext result
        }

        val firstHeaders = source.headers

        val isFirstPageVideo = hasVideoExtension(firstUrl) ||
            probeIsVideo(firstUrl, firstHeaders, networkHelper.client)

        if (!isFirstPageVideo) {
            val result = VideoDetectionResult.NotVideo
            sessionCache[chapter.id] = result
            return@withContext result
        }

        // First page is video: collect all video pages in order into playlist
        val playlist = mutableListOf<VideoTrack>()
        pages.forEachIndexed { index, page ->
            val url = resolvePageUrl(source, page)
            if (url == null) {
                logcat(LogPriority.WARN) { "Skipping unresolvable page in video chapter at index $index" }
                return@forEachIndexed
            }
            val headers = source.headers

            // If subsequent pages have video extension or match probe
            if (hasVideoExtension(url) || probeIsVideo(url, headers, networkHelper.client)) {
                val headersMap = headers.names().associateWith { headers[it].orEmpty() }
                playlist.add(VideoTrack(index, url, headersMap))
            } else {
                logcat(LogPriority.WARN) { "Skipping non-video page in video chapter at index $index: $url" }
            }
        }

        val result = if (playlist.isNotEmpty()) {
            VideoDetectionResult.Video(playlist)
        } else {
            VideoDetectionResult.NotVideo
        }

        sessionCache[chapter.id] = result
        result
    }

    private suspend fun resolvePageUrl(source: HttpSource, page: Page): String? {
        val existing = page.imageUrl
        if (!existing.isNullOrBlank()) return existing

        return try {
            source.getImageUrl(page)
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG) { "Failed to resolve image URL for page: ${e.message}" }
            page.url.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mkv", "mov", "m3u8", "mpd",
        )

        val PROBE_CONTENT_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/dash+xml",
        )

        fun hasVideoExtension(url: String): Boolean {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val ext = cleanUrl.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }

        fun probeIsVideo(url: String, headers: Headers, client: OkHttpClient): Boolean {
            val probeClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            // 1. Probe with HEAD request
            try {
                val headReq = Request.Builder()
                    .url(url)
                    .headers(headers)
                    .head()
                    .build()
                probeClient.newCall(headReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val contentType = response.header("Content-Type")?.lowercase()?.substringBefore(';')?.trim().orEmpty()
                        if (contentType.startsWith("video/") || contentType in PROBE_CONTENT_TYPES) {
                            return true
                        }
                        if (contentType.startsWith("image/")) {
                            return false
                        }
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.DEBUG) { "HEAD probe failed for $url: ${e.message}" }
            }

            // 2. Fall back to Range: bytes=0-0 GET request
            try {
                val rangeReq = Request.Builder()
                    .url(url)
                    .headers(headers)
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                probeClient.newCall(rangeReq).execute().use { response ->
                    val contentType = response.header("Content-Type")?.lowercase()?.substringBefore(';')?.trim().orEmpty()
                    if (contentType.startsWith("video/") || contentType in PROBE_CONTENT_TYPES) {
                        return true
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.DEBUG) { "Range GET probe failed for $url: ${e.message}" }
            }

            return false
        }
    }
}
