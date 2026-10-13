# Mihon Custom - Feature D: Video Chapter Player Architecture & Integration Notes

## 1. Overview
Feature D introduces native video streaming playback for chapters returned by Mihon extensions where chapter "pages" are video URLs or adaptive streaming manifests instead of static images.

When a chapter is classified as a video stream, Mihon Custom routes the chapter to **`PlayerActivity`** powered by **AndroidX Media3 (ExoPlayer)**. For image-based chapters, behavior remains 100% identical to upstream Mihon.

---

## 2. Detection Flow & Preloading Prevention

### 2.1 The Critical Constraint
Upstream Mihon's `HttpPageLoader` immediately begins preloading the first pages in `getPages()` via `loadByRollingChunked(0, 1, 1)`. If a video stream were fetched through this pipeline, the app would attempt to download and decode full video files into Bitmaps, causing high network usage, memory spikes, and decoding errors.

### 2.2 Pre-loading Detection Architecture
1. **Detection Entrypoint:** Detection is intercepted in `ReaderViewModel.loadChapter(...)` and `HttpPageLoader.getPages()`.
2. **URL Extension Check:** The URL of the first page (after resolving `page.imageUrl ?: source.getImageUrl(page)`) is tested against known video and streaming extensions:
   - `.mp4`, `.m4v`, `.webm`, `.mkv`, `.mov`
   - `.m3u8` (HLS playlist)
   - `.mpd` (DASH manifest)
   *(Query parameters such as authentication tokens and fragments are stripped prior to extension evaluation).*
3. **Lightweight Network Probe:** If the URL lacks a recognizable video extension, a probe is executed with a strict 5-second timeout using the app's `OkHttpClient` and the source's headers:
   - **HEAD Request:** Checks `Content-Type` for `video/*`, `application/vnd.apple.mpegurl`, `application/x-mpegURL`, or `application/dash+xml`. If content type starts with `image/*`, probe returns non-video immediately.
   - **Range GET Fallback:** If HEAD is rejected by the server (e.g. HTTP 403/405), issues `GET` with header `Range: bytes=0-0` to inspect response content type.
4. **Session Caching:** Once a chapter verdict is determined, it is cached in `ConcurrentHashMap<Long, VideoDetectionResult>` in memory so neither the detector nor the probe runs again during the user's session.
5. **Image Preload Guard:** In `HttpPageLoader.getPages()`, `loadByRollingChunked(...)` is explicitly guarded to never execute when a video extension is detected.

---

## 3. Media3 (ExoPlayer) Pipeline

### 3.1 Network Data Source
Streaming video files requires preserving the source's authentication tokens, referer headers, and cookies. `PlayerActivity` uses `OkHttpDataSource.Factory` built on `networkHelper.client`:
- Passes `source.headers` (including `Referer` and custom headers) to both playlist manifest requests and media segment requests.
- Applies Mihon's default User-Agent and DNS-over-HTTPS settings.

### 3.2 Media Formats Supported
- Progressive video containers: MP4, WebM, MKV, M4V, MOV.
- Adaptive streaming: HLS (`.m3u8`) and DASH (`.mpd`).

---

## 4. UI Controls & Gestures

`PlayerActivity` features a modern Compose overlay on top of Media3's `PlayerView`:
- **Controls Visibility:** Tapping the screen toggles controls; controls auto-hide after 3 seconds of active playback.
- **Gesture Scrubbing:**
  - Double-tap left half: skips backward by the configured skip interval (default: 10s).
  - Double-tap right half: skips forward by the configured skip interval (default: 10s).
- **Top Bar:** Back navigation button, manga title, chapter title, and playlist part badge (`Part 1 of 3`).
- **Center Controls:** Play/Pause button, buffering indicator, and error retry prompt.
- **Bottom Bar:**
  - Elapsed time and total duration display.
  - Smooth seek bar slider.
  - Previous and Next Chapter buttons.
  - Playback speed selector (`0.5x`, `0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`).
- **Lifecycle & Audio:**
  - Backgrounding pauses playback.
  - Headphone disconnection (`ACTION_AUDIO_BECOMING_NOISY`) pauses playback.
  - Landscape orientation is locked by default (`videoLockLandscape`).
  - Screen is kept awake during active playback (`FLAG_KEEP_SCREEN_ON`).

---

## 5. Progress, Read State & History Tracking

### 5.1 No Database Schema Modifications
To maintain seamless upstream rebase and database compatibility, video progress is encoded directly into the existing `last_page_read` integer column:
$$\text{encodedValue} = \text{trackIndex} \times 1,000,000 + \text{positionSeconds}$$

This is managed by the pure, unit-tested `VideoProgressHelper`:
- Single video at 15m 30s (930s): stored as `930`.
- Part 2 (index 1) at 4m 12s (252s): stored as `1,000,252`.

### 5.2 Resume Behavior
When reopening a video chapter with "Resume from last position" enabled:
$$\text{resumeSeconds} = \max(0, \text{savedPositionSeconds} - 3)$$
The player seeks to 3 seconds before the saved timestamp for smooth resumption.

### 5.3 90% Read Threshold & Trackers
When video playback reaches $\ge 90\%$ of total duration or reaches the end of the video:
- Chapter is marked as read (`read = true`).
- Tracking services (AniList, MyAnimeList, etc.) are notified via `trackChapter.await(...)`.
- Watch duration is recorded in Mihon's history database through `UpsertHistory`.

---

## 6. Chapter Navigation Matrix
The user can seamlessly traverse between chapters regardless of media type:
- **Video $\to$ Video:** Handled within `PlayerActivity`, updating the player playlist.
- **Video $\to$ Image:** `PlayerActivity` starts `ReaderActivity` and finishes.
- **Image $\to$ Video:** `ReaderActivity` starts `PlayerActivity` and finishes.
