package com.amazon.ivs.optimizations.ui.precaching

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import com.amazon.ivs.optimizations.cache.PreferenceProvider
import com.amazon.ivs.optimizations.common.init
import com.amazon.ivs.optimizations.common.launchIO
import com.amazon.ivs.optimizations.common.toDecimalSeconds
import com.amazon.ivs.optimizations.playerview.PlayerView
import com.amazon.ivs.optimizations.ui.models.Error
import com.amazon.ivs.optimizations.ui.models.InfoUpdate
import com.amazon.ivs.optimizations.ui.models.MAX_QUALITY
import com.amazon.ivs.optimizations.ui.models.STREAM_URI
import com.amazon.ivs.optimizations.ui.models.UPDATE_DELAY
import com.amazonaws.ivs.player.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class PreCachingViewModel @Inject constructor(
    private val preferences: PreferenceProvider,
) : ViewModel() {
    private lateinit var playerListener: Player.Listener
    private var timeToVideo: Int? = null
    private val _onSizeChanged = MutableSharedFlow<Size>(replay = 1)
    private val _onInfoUpdate = Channel<InfoUpdate>()
    private val _onBuffering = Channel<Boolean>()
    private val _onError = Channel<Error>()

    val onSizeChanged = _onSizeChanged.asSharedFlow()
    val onInfoUpdate = _onInfoUpdate.receiveAsFlow()
    val onBuffering = _onBuffering.receiveAsFlow()
    val onError = _onError.receiveAsFlow()
    val currentSize get() = _onSizeChanged.replayCache.lastOrNull()

    @SuppressLint("StaticFieldLeak")
    var playerView: PlayerView? = null

    fun initPlayer(context: Context) {
        if (playerView != null) {
            playerView?.player?.load((preferences.playbackUrl ?: STREAM_URI).toUri())
            return
        }
        _onBuffering.trySend(true)

        playerView = PlayerView(context).apply {
            Timber.d("Initializing player and calling load()")
            player.load((preferences.playbackUrl ?: STREAM_URI).toUri())
            setControlsEnabled(false)
            playerListener = player.init(
                { videoSizeState ->
                    _onSizeChanged.tryEmit(videoSizeState)
                },
                { state ->
                    when (state) {
                        Player.State.BUFFERING -> {
                            _onBuffering.trySend(true)
                        }
                        Player.State.READY -> {
                            player.qualities.firstOrNull { it.name == MAX_QUALITY }?.let { quality ->
                                player.setAutoMaxQuality(quality)
                            }
                            _onBuffering.trySend(false)
                        }
                        Player.State.PLAYING -> {
                            _onBuffering.trySend(false)
                            if (timeToVideo == null) {
                                timeToVideo = (Date().time - preferences.capturedClickTime).toInt()
                            }
                        }
                        else -> { /* Ignored */ }
                    }
                },
                { exception ->
                    _onError.trySend(exception)
                }
            )
        }
        launchUpdates()
    }

    private fun launchUpdates() {
        if (playerView == null) return
        launchIO {
            playerView?.let { playerView ->
                _onInfoUpdate.trySend(
                    InfoUpdate(
                        playerView.player.version,
                        (playerView.player.bufferedPosition - playerView.player.position).toDecimalSeconds(),
                        playerView.player.liveLatency.toDecimalSeconds(),
                        timeToVideo = timeToVideo ?: 0,
                        preCached = true
                    )
                )
            }
            delay(UPDATE_DELAY)
            launchUpdates()
        }
    }

    fun play() {
        playerView?.player?.play()
    }

    fun release() {
        Timber.d("Releasing player instance")
        playerView?.player?.removeListener(playerListener)
        playerView?.player?.release()
        playerView = null
        timeToVideo = null
    }

    fun captureClickTime() {
        preferences.capturedClickTime = Date().time
    }
}
