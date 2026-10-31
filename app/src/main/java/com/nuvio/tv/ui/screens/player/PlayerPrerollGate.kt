@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.nuvio.tv.core.localaddon.LocalAddonManager
import com.nuvio.tv.core.localaddon.PrerollClip
import com.nuvio.tv.core.localaddon.PrerollRequest
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class PlayerPrerollViewModel @Inject constructor(
    private val localAddonManager: LocalAddonManager,
    private val watchProgressRepository: WatchProgressRepository,
) : ViewModel() {

    /** Only series episodes get a pre-roll, and only when an installed addon asks to provide one. */
    internal fun isEligible(args: PlayerNavigationArgs): Boolean =
        args.contentType.equals("series", ignoreCase = true) &&
            args.initialSeason != null && args.initialEpisode != null &&
            localAddonManager.hasPrerollAddon()

    /** The clip to play first, or null when the episode should start right away (also when it will resume). */
    internal suspend fun lookup(args: PlayerNavigationArgs): PrerollClip? {
        val season = args.initialSeason ?: return null
        val episode = args.initialEpisode ?: return null
        val contentId = args.contentId
        if (contentId != null && !args.startFromBeginning) {
            val saved = withTimeoutOrNull(SAVED_PROGRESS_TIMEOUT_MS) {
                watchProgressRepository.getEpisodeProgress(contentId, season, episode).firstOrNull()
            }
            if (saved?.isInProgress() == true) return null // resuming: no bump mid-episode
        }
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            localAddonManager.preroll(
                PrerollRequest(
                    contentType = args.contentType,
                    title = args.contentName ?: args.title,
                    seasonNumber = season,
                    episodeNumber = episode,
                    episodeTitle = args.initialEpisodeTitle,
                    videoId = args.videoId,
                    parentMetaId = contentId.orEmpty(),
                ),
            )
        }
    }

    private companion object {
        const val SAVED_PROGRESS_TIMEOUT_MS = 2_000L
        const val LOOKUP_TIMEOUT_MS = 6_000L
    }
}

private const val PHASE_LOOKUP = 0
private const val PHASE_PLAYING = 1
private const val PHASE_DONE = 2

/**
 * Shows a short clip from an addon (a "bump") before the episode starts. The real player is only created
 * once the clip has ended, been skipped (OK or Back) or failed, so nothing plays underneath it.
 */
@Composable
internal fun PlayerPrerollGate(savedStateHandle: SavedStateHandle, content: @Composable () -> Unit) {
    val viewModel: PlayerPrerollViewModel = hiltViewModel()
    val args = remember(savedStateHandle) { PlayerNavigationArgs.from(savedStateHandle) }
    var phase by rememberSaveable { mutableIntStateOf(if (viewModel.isEligible(args)) PHASE_LOOKUP else PHASE_DONE) }
    var clip by remember { mutableStateOf<PrerollClip?>(null) }

    if (phase == PHASE_DONE) {
        content()
        return
    }

    BackHandler { phase = PHASE_DONE }

    LaunchedEffect(Unit) {
        if (phase == PHASE_LOOKUP) {
            val found = viewModel.lookup(args)
            if (found == null) {
                phase = PHASE_DONE
            } else {
                clip = found
                phase = PHASE_PLAYING
            }
        } else if (clip == null) {
            phase = PHASE_DONE // recreated mid-clip: just play the episode
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        clip?.let { current -> PrerollOverlay(current) { phase = PHASE_DONE } }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PrerollOverlay(clip: PrerollClip, onFinished: () -> Unit) {
    val context: Context = LocalContext.current
    var started by remember(clip.url) { mutableStateOf(false) }
    var finished by remember(clip.url) { mutableStateOf(false) }
    var canSkip by remember(clip.url) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val finish = {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    val exo = remember(clip.url) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(clip.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) = finish()
        }
        exo.addListener(listener)
        exo.setMediaItem(MediaItem.fromUri(clip.url))
        exo.playWhenReady = true
        exo.prepare()
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // Skip appears after a moment, and a clip that never starts must not hold the episode back.
    LaunchedEffect(clip.url) {
        delay(SKIP_DELAY_MS)
        canSkip = true
    }
    LaunchedEffect(clip.url) {
        delay(START_TIMEOUT_MS)
        if (!started) finish()
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp && canSkip) {
                    finish()
                    true
                } else {
                    false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = exo
                }
            },
        )
        if (canSkip) {
            Text(
                text = "Press OK to skip",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp, bottom = 32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

private const val SKIP_DELAY_MS = 1_500L
private const val START_TIMEOUT_MS = 12_000L
private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
