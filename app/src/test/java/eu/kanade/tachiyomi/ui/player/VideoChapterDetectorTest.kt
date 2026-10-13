package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.player.detector.DefaultVideoChapterDetector
import eu.kanade.tachiyomi.ui.player.model.VideoDetectionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class VideoChapterDetectorTest {

    private lateinit var networkHelper: NetworkHelper
    private lateinit var chapterCache: ChapterCache
    private lateinit var detector: DefaultVideoChapterDetector
    private lateinit var source: HttpSource

    @BeforeEach
    fun setUp() {
        val client = OkHttpClient.Builder().build()
        networkHelper = mockk {
            every { this@mockk.client } returns client
        }
        chapterCache = mockk(relaxed = true)
        source = mockk {
            every { headers } returns Headers.headersOf()
        }
        detector = DefaultVideoChapterDetector(networkHelper, chapterCache)
    }

    @Test
    fun `hasVideoExtension recognizes standard video and streaming extensions`() {
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/video.mp4") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/clip.m4v") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/stream.webm") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/movie.mkv") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/video.mov") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/master.m3u8") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/manifest.mpd") shouldBe true
    }

    @Test
    fun `hasVideoExtension ignores query parameters and fragments`() {
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/video.mp4?token=abc123xyz&expire=999") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/live.m3u8?session=1#track1") shouldBe true
    }

    @Test
    fun `hasVideoExtension recognizes DeviantArt and Pixiv video paths and query parameters`() {
        DefaultVideoChapterDetector.hasVideoExtension("https://images-wixmp.com/v/mp4/custom_animation.mp4?token=xyz") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://images-wixmp.com/v/mp4/deviant_video") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://i.pximg.net/video/sample.mp4?lang=en") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://i.pximg.net/ugoira/sample.webm") shouldBe true
        DefaultVideoChapterDetector.hasVideoExtension("https://stream.example.com/watch?format=mp4") shouldBe true
    }

    @Test
    fun `buildEffectiveHeaders injects Pixiv and DeviantArt referers when missing`() {
        val pixivHeaders = DefaultVideoChapterDetector.buildEffectiveHeaders(source, "https://i.pximg.net/img-original/img/sample.mp4")
        pixivHeaders["Referer"] shouldBe "https://www.pixiv.net/"

        val daHeaders = DefaultVideoChapterDetector.buildEffectiveHeaders(source, "https://images-wixmp.com/v/mp4/sample.mp4")
        daHeaders["Referer"] shouldBe "https://www.deviantart.com/"
    }

    @Test
    fun `hasVideoExtension returns false for image formats`() {
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/001.jpg") shouldBe false
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/002.png") shouldBe false
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/003.webp") shouldBe false
        DefaultVideoChapterDetector.hasVideoExtension("https://cdn.example.com/cover.avif") shouldBe false
    }

    @Test
    fun `detect returns Video result with playlist when pages have video URLs`() = runTest {
        val chapter = Chapter.create().copy(id = 101L)
        val pages = listOf(
            Page(0, "https://example.com/page0", "https://example.com/stream1.mp4"),
            Page(1, "https://example.com/page1", "https://example.com/stream2.mp4"),
        )
        coEvery { source.getPageList(any()) } returns pages

        val result = detector.detect(source, chapter, rawPages = pages)

        result.shouldBeInstanceOf<VideoDetectionResult.Video>()
        result.playlist.size shouldBe 2
        result.playlist[0].url shouldBe "https://example.com/stream1.mp4"
        result.playlist[1].url shouldBe "https://example.com/stream2.mp4"

        // Verify session cache returns verdict
        detector.isCachedVideo(chapter.id) shouldBe true
    }

    @Test
    fun `detect returns NotVideo when first page is an image URL`() = runTest {
        val chapter = Chapter.create().copy(id = 102L)
        val pages = listOf(
            Page(0, "https://example.com/page0", "https://example.com/001.jpg"),
            Page(1, "https://example.com/page1", "https://example.com/002.jpg"),
        )
        coEvery { source.getPageList(any()) } returns pages

        val result = detector.detect(source, chapter, rawPages = pages)

        result shouldBe VideoDetectionResult.NotVideo
        detector.isCachedVideo(chapter.id) shouldBe false
    }

    @Test
    fun `clearSessionCache clears cached verdicts`() = runTest {
        val chapter = Chapter.create().copy(id = 103L)
        val pages = listOf(
            Page(0, "https://example.com/page0", "https://example.com/stream.mp4"),
        )
        detector.detect(source, chapter, rawPages = pages)
        detector.isCachedVideo(chapter.id) shouldBe true

        detector.clearSessionCache()
        detector.isCachedVideo(chapter.id) shouldBe null
    }
}
