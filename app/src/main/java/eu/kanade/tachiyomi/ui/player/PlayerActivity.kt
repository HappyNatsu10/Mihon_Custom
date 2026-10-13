package eu.kanade.tachiyomi.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.helper.VideoProgressHelper
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import logcat.LogPriority
import logcat.logcat
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SkipPrevious
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class PlayerActivity : AppCompatActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    @Inject lateinit var readerPreferences: ReaderPreferences
    @Inject lateinit var preferences: BasePreferences

    val viewModel by viewModels<PlayerViewModel> { graph.viewModelFactory }

    private var exoPlayer: ExoPlayer? = null
    private var isPlayerReleased = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                exoPlayer?.pause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        // Lock landscape by default if setting is enabled
        if (readerPreferences.videoLockLandscape.get()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // Register noisy receiver (headphones unplugged)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)

        // Observe events from ViewModel
        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    is PlayerViewModel.Event.NavigateToReader -> {
                        startActivity(ReaderActivity.newIntent(this, event.mangaId, event.chapterId))
                        finish()
                    }
                    PlayerViewModel.Event.Close -> finish()
                }
            }
            .launchIn(lifecycleScope)

        setComposeContent {
            PlayerScreen(
                viewModel = viewModel,
                onGetPlayer = { getOrCreatePlayer() },
                onReleasePlayer = { releasePlayer() },
                onFinish = { finish() },
            )
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val networkHelper = viewModel.networkHelper
        val dataSourceFactory = OkHttpDataSource.Factory(networkHelper.client)
            .setUserAgent(networkHelper.defaultUserAgentProvider())

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val skipSeconds = readerPreferences.videoSkipInterval.get()
        val skipMs = skipSeconds * 1000L

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(skipMs)
            .setSeekForwardIncrementMs(skipMs)
            .build()
            .apply {
                playbackParameters = PlaybackParameters(readerPreferences.videoPlaybackSpeed.get())
                playWhenReady = true
            }

        exoPlayer = player
        isPlayerReleased = false
        return player
    }

    private fun releasePlayer() {
        if (!isPlayerReleased) {
            exoPlayer?.let { player ->
                val currentSeconds = (player.currentPosition / 1000L).coerceAtLeast(0L)
                viewModel.saveProgress(currentSeconds)
                viewModel.saveWatchHistory()
                player.release()
            }
            exoPlayer = null
            isPlayerReleased = true
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        exoPlayer?.let { player ->
            val currentSeconds = (player.currentPosition / 1000L).coerceAtLeast(0L)
            viewModel.saveProgress(currentSeconds)
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Throwable) {}
        releasePlayer()
    }

    companion object {
        fun newIntent(context: Context, mangaId: Long, chapterId: Long, forceVideo: Boolean = false): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                putExtra("force_video", forceVideo)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onGetPlayer: () -> ExoPlayer,
    onReleasePlayer: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    val skipSeconds = viewModel.readerPreferences.videoSkipInterval.get()

    // Initialize ExoPlayer and bind listeners
    DisposableEffect(Unit) {
        val p = onGetPlayer()
        player = p

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = p.duration.coerceAtLeast(0L)
                }
                if (playbackState == Player.STATE_ENDED) {
                    viewModel.markChapterAsRead()
                    if (state.currentTrackIndex < state.playlist.lastIndex) {
                        viewModel.onTrackChanged(state.currentTrackIndex + 1)
                    } else if (viewModel.readerPreferences.videoAutoPlayNext.get()) {
                        viewModel.loadNextChapter()
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.message ?: "Playback error"
                logcat(LogPriority.ERROR) { "ExoPlayer error: ${error.message}" }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                p.currentMediaItemIndex.let { idx ->
                    if (idx in state.playlist.indices) {
                        viewModel.onTrackChanged(idx)
                    }
                }
            }
        }

        p.addListener(listener)

        onDispose {
            p.removeListener(listener)
            onReleasePlayer()
        }
    }

    // Load media items into player when playlist changes
    val context = LocalContext.current
    LaunchedEffect(state.playlist, state.currentChapter?.id) {
        val p = player ?: return@LaunchedEffect
        if (state.playlist.isNotEmpty()) {
            playbackError = null
            val mediaSources = state.playlist.map { track ->
                val trackHeaders = track.headers.toMutableMap()
                val uri = Uri.parse(track.url)
                val host = uri.host.orEmpty().lowercase()
                if (!trackHeaders.keys.any { it.equals("referer", ignoreCase = true) }) {
                    when {
                        host.contains("pximg.net") || host.contains("pixiv") -> {
                            trackHeaders["Referer"] = "https://www.pixiv.net/"
                        }
                        host.contains("wixmp.com") || host.contains("deviantart") -> {
                            trackHeaders["Referer"] = "https://www.deviantart.com/"
                        }
                        else -> {
                            viewModel.source?.let { src ->
                                runCatching { src.baseUrl }.getOrNull()?.let { baseUrl ->
                                    if (baseUrl.isNotBlank()) trackHeaders["Referer"] = baseUrl
                                }
                            }
                        }
                    }
                }
                if (!trackHeaders.keys.any { it.equals("user-agent", ignoreCase = true) }) {
                    trackHeaders["User-Agent"] = viewModel.networkHelper.defaultUserAgentProvider()
                }

                val dataSourceFactory = OkHttpDataSource.Factory(viewModel.networkHelper.client)
                    .setUserAgent(viewModel.networkHelper.defaultUserAgentProvider())
                    .setDefaultRequestProperties(trackHeaders)

                val itemMediaSourceFactory = DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(track.index.toString())
                    .build()

                itemMediaSourceFactory.createMediaSource(mediaItem)
            }
            p.setMediaSources(mediaSources, state.currentTrackIndex, state.initialPositionSeconds * 1000L)
            p.prepare()
            p.play()
        }
    }

    // Periodic progress ticker (updates position, saves progress every 10s, checks 90% read)
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        var counter = 0
        while (isActive) {
            delay(1000)
            if (p.isPlaying) {
                currentPositionMs = p.currentPosition.coerceAtLeast(0L)
                durationMs = p.duration.coerceAtLeast(0L)

                // Check 90% threshold to mark as read
                if (VideoProgressHelper.isOverReadThreshold(currentPositionMs, durationMs)) {
                    viewModel.markChapterAsRead()
                }

                counter++
                if (counter >= 10) {
                    counter = 0
                    viewModel.saveProgress(currentPositionMs / 1000L)
                }
            }
        }
    }

    // Auto-hide controls after 3 seconds of playing
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Media3 Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            update = { view ->
                view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture Detection Overlay (Tap to show/hide, double-tap left/right to skip)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            val isLeft = offset.x < size.width / 2
                            val delta = if (isLeft) -skipSeconds * 1000L else skipSeconds * 1000L
                            player?.let { p ->
                                val target = (p.currentPosition + delta).coerceIn(0L, p.duration.coerceAtLeast(0L))
                                p.seekTo(target)
                                currentPositionMs = target
                            }
                        },
                    )
                },
        )

        // Buffering Indicator
        if (isBuffering || state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Error with Retry Card
        val errorMessage = playbackError ?: state.error
        if (errorMessage != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(MR.strings.video_player_error, errorMessage),
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                playbackError = null
                                viewModel.retry()
                            },
                        ) {
                            Text(text = stringResource(MR.strings.video_player_retry))
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.openInReader()
                            },
                        ) {
                            Text(
                                text = "Open in Reader",
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onFinish) {
                        Icon(
                            imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.manga?.title.orEmpty(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.currentChapter?.name.orEmpty(),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (state.playlist.size > 1) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    MR.strings.video_part_indicator,
                                    state.currentTrackIndex + 1,
                                    state.playlist.size,
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // Center Play/Pause & Skip Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    IconButton(
                        onClick = {
                            player?.let { p ->
                                val target = (p.currentPosition - skipSeconds * 1000L).coerceAtLeast(0L)
                                p.seekTo(target)
                                currentPositionMs = target
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_replay_10),
                            contentDescription = "Rewind",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = CircleShape,
                        modifier = Modifier.size(68.dp),
                    ) {
                        IconButton(
                            onClick = {
                                player?.let { p ->
                                    if (p.isPlaying) p.pause() else p.play()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            player?.let { p ->
                                val target = (p.currentPosition + skipSeconds * 1000L).coerceAtMost(p.duration.coerceAtLeast(0L))
                                p.seekTo(target)
                                currentPositionMs = target
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_forward_10),
                            contentDescription = "Forward",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // Bottom Controls Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .align(Alignment.BottomCenter),
                ) {
                    // Time and Duration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = VideoProgressHelper.formatDuration(currentPositionMs / 1000L),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = VideoProgressHelper.formatDuration(durationMs / 1000L),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }

                    // Seek Slider
                    val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
                    val currentSlider = currentPositionMs.coerceIn(0L, durationMs).toFloat()
                    Slider(
                        value = currentSlider,
                        valueRange = 0f..maxSlider,
                        onValueChange = { newPos ->
                            currentPositionMs = newPos.toLong()
                        },
                        onValueChangeFinished = {
                            player?.seekTo(currentPositionMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Bottom Navigation and Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Previous Chapter
                        IconButton(
                            onClick = { viewModel.loadPreviousChapter() },
                            enabled = state.hasPreviousChapter,
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.SkipPrevious,
                                contentDescription = "Previous Chapter",
                                tint = if (state.hasPreviousChapter) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }

                        // Playback Speed Selector
                        Box {
                            IconButton(onClick = { showSpeedDialog = !showSpeedDialog }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_speed),
                                    contentDescription = stringResource(MR.strings.video_playback_speed_title),
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = showSpeedDialog,
                                onDismissRequest = { showSpeedDialog = false },
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = { Text("${speed}x") },
                                        onClick = {
                                            player?.playbackParameters = PlaybackParameters(speed)
                                            showSpeedDialog = false
                                        },
                                    )
                                }
                            }
                        }

                        // Next Chapter
                        IconButton(
                            onClick = { viewModel.loadNextChapter() },
                            enabled = state.hasNextChapter,
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.SkipNext,
                                contentDescription = "Next Chapter",
                                tint = if (state.hasNextChapter) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }
}
