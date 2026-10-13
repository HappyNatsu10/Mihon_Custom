package eu.kanade.tachiyomi.ui.player.model

/**
 * Model representing an individual video track/stream in a video chapter.
 */
data class VideoTrack(
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Result of detecting whether a chapter's pages are video streams.
 */
sealed interface VideoDetectionResult {
    data class Video(val playlist: List<VideoTrack>) : VideoDetectionResult
    data object NotVideo : VideoDetectionResult
}
