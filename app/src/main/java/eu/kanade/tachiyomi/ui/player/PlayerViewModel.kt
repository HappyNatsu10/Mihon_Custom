package eu.kanade.tachiyomi.ui.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.player.detector.DefaultVideoChapterDetector
import eu.kanade.tachiyomi.ui.player.helper.VideoProgressHelper
import eu.kanade.tachiyomi.ui.player.model.VideoDetectionResult
import eu.kanade.tachiyomi.ui.player.model.VideoTrack
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock
import kotlin.time.Instant

@AssistedInject
class PlayerViewModel(
    @Assisted private val savedState: SavedStateHandle,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val getIncognitoState: GetIncognitoState,
    private val trackChapter: TrackChapter,
    private val sourceManager: SourceManager,
    val readerPreferences: ReaderPreferences,
    private val trackPreferences: TrackPreferences,
    private val videoChapterDetector: DefaultVideoChapterDetector,
    private val chapterCache: ChapterCache,
    val networkHelper: NetworkHelper,
) : ViewModel() {

    val mangaId: Long = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId: Long = savedState.get<Long>("chapter") ?: -1L
    private val forceVideo: Boolean = savedState.get<Boolean>("force_video") ?: false

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    private var allChapters: List<Chapter> = emptyList()
    var source: HttpSource? = null
        private set
    private var currentChapterId: Long = initialChapterId
    private var sessionStartTime: Instant = Clock.System.now()
    private var incognitoMode: Boolean = false

    init {
        if (mangaId != -1L && initialChapterId != -1L) {
            viewModelScope.launch {
                init()
            }
        }
    }

    private suspend fun init() = withContext(Dispatchers.IO) {
        try {
            val manga = getManga.await(mangaId) ?: error("Manga not found")
            source = sourceManager.getOrStub(manga.source) as? HttpSource
            incognitoMode = getIncognitoState.await(manga.source)

            val rawChapters = getChaptersByMangaId.await(mangaId)
            allChapters = rawChapters.sortedWith(getChapterSort(manga, sortDescending = false))

            mutableState.update { it.copy(manga = manga) }

            loadVideoChapter(currentChapterId)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR) { "Failed to initialize player: ${e.message}" }
            mutableState.update { it.copy(isLoading = false, error = e.message ?: "Failed to initialize") }
        }
    }

    suspend fun loadVideoChapter(chapterId: Long) = withContext(Dispatchers.IO) {
        mutableState.update { it.copy(isLoading = true, error = null) }
        currentChapterId = chapterId
        sessionStartTime = Clock.System.now()

        val chapter = allChapters.find { it.id == chapterId }
        if (chapter == null) {
            mutableState.update { it.copy(isLoading = false, error = "Chapter not found") }
            return@withContext
        }

        val httpSource = source
        if (httpSource == null) {
            mutableState.update { it.copy(isLoading = false, error = "Source is not available for video streaming") }
            return@withContext
        }

        val detection = videoChapterDetector.detect(httpSource, chapter)
        when (detection) {
            is VideoDetectionResult.Video -> {
                applyVideoPlaylist(chapter, detection.playlist)
            }
            VideoDetectionResult.NotVideo -> {
                if (forceVideo && chapterId == initialChapterId) {
                    val rawPages = try {
                        chapterCache.getPageListFromCache(chapter).ifEmpty {
                            httpSource.getPageList(chapter.toSChapter())
                        }
                    } catch (e: Throwable) {
                        emptyList()
                    }
                    val playlist = rawPages.mapIndexedNotNull { index, page ->
                        val url = page.imageUrl ?: page.url
                        if (url.isNotBlank()) {
                            val effHeaders = DefaultVideoChapterDetector.buildEffectiveHeaders(httpSource, url)
                            val headersMap = effHeaders.names().associateWith { effHeaders[it].orEmpty() }
                            VideoTrack(index, url, headersMap)
                        } else null
                    }
                    if (playlist.isNotEmpty()) {
                        applyVideoPlaylist(chapter, playlist)
                    } else {
                        eventChannel.send(Event.NavigateToReader(mangaId, chapterId))
                    }
                } else {
                    // Not a video chapter - navigate back to page reader
                    eventChannel.send(Event.NavigateToReader(mangaId, chapterId))
                }
            }
        }
    }

    private fun applyVideoPlaylist(chapter: Chapter, playlist: List<VideoTrack>) {
        val currentIndex = allChapters.indexOf(chapter)
        val hasNext = currentIndex < allChapters.lastIndex
        val hasPrev = currentIndex > 0

        val (savedTrack, savedSeconds) = VideoProgressHelper.decode(chapter.lastPageRead.toLong())
        val resumeSeconds = if (readerPreferences.videoResumePosition.get()) {
            VideoProgressHelper.calculateResumePosition(savedSeconds)
        } else {
            0L
        }

        mutableState.update {
            it.copy(
                isLoading = false,
                currentChapter = chapter,
                playlist = playlist,
                currentTrackIndex = savedTrack.coerceIn(0, playlist.lastIndex.coerceAtLeast(0)),
                initialPositionSeconds = resumeSeconds,
                hasNextChapter = hasNext,
                hasPreviousChapter = hasPrev,
                error = null,
            )
        }
    }

    fun openInReader() {
        viewModelScope.launch {
            eventChannel.send(Event.NavigateToReader(mangaId, currentChapterId))
        }
    }

    fun onTrackChanged(newTrackIndex: Int) {
        mutableState.update { it.copy(currentTrackIndex = newTrackIndex) }
    }

    fun saveProgress(positionSeconds: Long) {
        val chapter = state.value.currentChapter ?: return
        val trackIndex = state.value.currentTrackIndex
        val encoded = VideoProgressHelper.encode(trackIndex, positionSeconds)

        appScope.launch(Dispatchers.IO) {
            updateChapter.await(
                ChapterUpdate(chapter.id) {
                    lastPageRead = encoded
                },
            )
        }
    }

    fun markChapterAsRead() {
        val chapter = state.value.currentChapter ?: return
        val manga = state.value.manga ?: return

        appScope.launch(Dispatchers.IO) {
            updateChapter.await(
                ChapterUpdate(chapter.id) {
                    read = true
                },
            )

            if (!incognitoMode && trackPreferences.autoUpdateTrack.get()) {
                trackChapter.await(context, manga.id, chapter.chapterNumber)
            }
        }
    }

    fun saveWatchHistory() {
        if (incognitoMode) return
        val chapter = state.value.currentChapter ?: return
        val now = Clock.System.now()
        val durationMs = (now - sessionStartTime).inWholeMilliseconds

        appScope.launch(Dispatchers.IO) {
            upsertHistory.await(HistoryUpdate(chapter.id, now, durationMs))
        }
    }

    fun loadNextChapter() {
        val currentChapter = state.value.currentChapter ?: return
        val currentIndex = allChapters.indexOf(currentChapter)
        if (currentIndex < allChapters.lastIndex) {
            val nextChapter = allChapters[currentIndex + 1]
            viewModelScope.launch {
                loadVideoChapter(nextChapter.id)
            }
        }
    }

    fun loadPreviousChapter() {
        val currentChapter = state.value.currentChapter ?: return
        val currentIndex = allChapters.indexOf(currentChapter)
        if (currentIndex > 0) {
            val prevChapter = allChapters[currentIndex - 1]
            viewModelScope.launch {
                loadVideoChapter(prevChapter.id)
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            loadVideoChapter(currentChapterId)
        }
    }

    data class State(
        val isLoading: Boolean = true,
        val manga: Manga? = null,
        val currentChapter: Chapter? = null,
        val playlist: List<VideoTrack> = emptyList(),
        val currentTrackIndex: Int = 0,
        val initialPositionSeconds: Long = 0L,
        val error: String? = null,
        val hasNextChapter: Boolean = false,
        val hasPreviousChapter: Boolean = false,
    )

    sealed interface Event {
        data class NavigateToReader(val mangaId: Long, val chapterId: Long) : Event
        data object Close : Event
    }

    @AssistedFactory
    @ViewModelAssistedFactoryKey(PlayerViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): PlayerViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): PlayerViewModel
    }
}
