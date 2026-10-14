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

        // Fast check on all pages: if any page URL already has a video extension
        val hasAnyVideoExt = pages.any { page ->
            val url = page.imageUrl ?: page.url
            url.isNotBlank() && hasVideoExtension(url)
        }

        val firstPage = pages.first()
        val firstUrl = resolvePageUrl(source, firstPage)
        val firstHeaders = if (firstUrl != null) buildEffectiveHeaders(source, firstUrl) else source.headers

        val isVideoChapter = hasAnyVideoExt || (firstUrl != null && (
            hasVideoExtension(firstUrl) || probeIsVideo(firstUrl, firstHeaders, networkHelper.client)
        )) || (pages.size <= 3 && pages.any { page ->
            val u = resolvePageUrl(source, page)
            u != null && (hasVideoExtension(u) || probeIsVideo(u, buildEffectiveHeaders(source, u), networkHelper.client))
        })

        if (!isVideoChapter) {
            val result = VideoDetectionResult.NotVideo
            sessionCache[chapter.id] = result
            return@withContext result
        }

        // First page or chapter contains video: collect all video pages in order into playlist
        val playlist = mutableListOf<VideoTrack>()
        pages.forEachIndexed { index, page ->
            val url = resolvePageUrl(source, page)
            if (url == null) {
                logcat(LogPriority.WARN) { "Skipping unresolvable page in video chapter at index $index" }
                return@forEachIndexed
            }
            val effectiveHeaders = buildEffectiveHeaders(source, url)

            // If page has video extension or matches probe
            if (hasVideoExtension(url) || probeIsVideo(url, effectiveHeaders, networkHelper.client)) {
                val headersMap = effectiveHeaders.names().associateWith { effectiveHeaders[it].orEmpty() }
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
            val resolved = source.getImageUrl(page)
            if (!resolved.isNullOrBlank()) {
                page.imageUrl = resolved
            }
            resolved
        } catch (e: Throwable) {
            logcat(LogPriority.DEBUG) { "Failed to resolve image URL for page: ${e.message}" }
            page.url.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mkv", "mov", "m3u8", "mpd", "ts", "flv", "avi", "wmv", "3gp",
        )

        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "heic",
        )

        val PROBE_CONTENT_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/dash+xml",
        )

        fun hasVideoExtension(url: String): Boolean {
            if (url.isBlank()) return false
            val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
            val ext = cleanUrl.substringAfterLast('.', "")

            // Definitive image format check: images are never video streams
            if (ext in IMAGE_EXTENSIONS) return false

            if (ext in VIDEO_EXTENSIONS) return true

            // URL path pattern checks for known video endpoints (e.g. DeviantArt /v/mp4/)
            if (cleanUrl.contains("/v/mp4/")) {
                return true
            }

            // Query parameter or embedded format checks
            val lowerFullUrl = url.lowercase()
            for (videoExt in VIDEO_EXTENSIONS) {
                if (lowerFullUrl.contains("format=$videoExt") ||
                    lowerFullUrl.contains("ext=$videoExt") ||
                    lowerFullUrl.endsWith(".$videoExt")
                ) {
                    return true
                }
            }
            return false
        }

        fun buildEffectiveHeaders(source: HttpSource, url: String): Headers {
            val builder = source.headers.newBuilder()
            val hasReferer = source.headers.names().any { it.equals("referer", ignoreCase = true) }
            if (!hasReferer) {
                val lowerUrl = url.lowercase()
                when {
                    lowerUrl.contains("pximg.net") || lowerUrl.contains("pixiv") -> {
                        builder.set("Referer", "https://www.pixiv.net/")
                    }
                    lowerUrl.contains("wixmp.com") || lowerUrl.contains("deviantart") -> {
                        builder.set("Referer", "https://www.deviantart.com/")
                    }
                    else -> {
                        val baseUrl = runCatching { source.baseUrl }.getOrNull()
                        if (!baseUrl.isNullOrBlank()) {
                            builder.set("Referer", baseUrl)
                        }
                    }
                }
            }
            return builder.build()
        }

        fun probeIsVideo(url: String, headers: Headers, client: OkHttpClient): Boolean {
            val probeClient = client.newBuilder()
                .callTimeout(6, TimeUnit.SECONDS)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
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
                        val contentDisposition = response.header("Content-Disposition")?.lowercase().orEmpty()
                        if (VIDEO_EXTENSIONS.any { contentDisposition.contains(".$it") }) {
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

            // 2. Fall back to Range: bytes=0-1024 GET request
            try {
                val rangeReq = Request.Builder()
                    .url(url)
                    .headers(headers)
                    .header("Range", "bytes=0-1024")
                    .get()
                    .build()
                probeClient.newCall(rangeReq).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        val contentType = response.header("Content-Type")?.lowercase()?.substringBefore(';')?.trim().orEmpty()
                        if (contentType.startsWith("video/") || contentType in PROBE_CONTENT_TYPES) {
                            return true
                        }
                        val contentDisposition = response.header("Content-Disposition")?.lowercase().orEmpty()
                        if (VIDEO_EXTENSIONS.any { contentDisposition.contains(".$it") }) {
                            return true
                        }
                        if (contentType.startsWith("image/")) {
                            return false
                        }
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.DEBUG) { "Range GET probe failed for $url: ${e.message}" }
            }

            return false
        }
    }
}
