package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.helper.VideoProgressHelper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VideoProgressHelperTest {

    @Test
    fun `encode and decode preserves track index and seconds`() {
        val track = 2
        val seconds = 1530L

        val encoded = VideoProgressHelper.encode(track, seconds)
        val (decodedTrack, decodedSeconds) = VideoProgressHelper.decode(encoded.toLong())

        decodedTrack shouldBe track
        decodedSeconds shouldBe seconds
    }

    @Test
    fun `encode and decode handles single track with zero seconds`() {
        val encoded = VideoProgressHelper.encode(0, 0L)
        val (decodedTrack, decodedSeconds) = VideoProgressHelper.decode(encoded.toLong())

        decodedTrack shouldBe 0
        decodedSeconds shouldBe 0L
    }

    @Test
    fun `decode handles negative or zero value gracefully`() {
        val (decodedTrack, decodedSeconds) = VideoProgressHelper.decode(0L)
        decodedTrack shouldBe 0
        decodedSeconds shouldBe 0L

        val (negTrack, negSeconds) = VideoProgressHelper.decode(-10L)
        negTrack shouldBe 0
        negSeconds shouldBe 0L
    }

    @Test
    fun `calculateResumePosition subtracts 3 seconds and floors at 0`() {
        VideoProgressHelper.calculateResumePosition(100L) shouldBe 97L
        VideoProgressHelper.calculateResumePosition(3L) shouldBe 0L
        VideoProgressHelper.calculateResumePosition(2L) shouldBe 0L
        VideoProgressHelper.calculateResumePosition(0L) shouldBe 0L
    }

    @Test
    fun `isOverReadThreshold triggers at 90 percent duration`() {
        val totalMs = 100_000L

        VideoProgressHelper.isOverReadThreshold(89_999L, totalMs) shouldBe false
        VideoProgressHelper.isOverReadThreshold(90_000L, totalMs) shouldBe true
        VideoProgressHelper.isOverReadThreshold(95_000L, totalMs) shouldBe true
        VideoProgressHelper.isOverReadThreshold(100_000L, totalMs) shouldBe true
        VideoProgressHelper.isOverReadThreshold(50_000L, 0L) shouldBe false
    }

    @Test
    fun `formatDuration formats correctly for mm ss and hh mm ss`() {
        VideoProgressHelper.formatDuration(45L) shouldBe "00:45"
        VideoProgressHelper.formatDuration(65L) shouldBe "01:05"
        VideoProgressHelper.formatDuration(3600L) shouldBe "1:00:00"
        VideoProgressHelper.formatDuration(3665L) shouldBe "1:01:05"
    }
}
