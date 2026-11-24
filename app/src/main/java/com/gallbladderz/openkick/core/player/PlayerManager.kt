/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.core.player


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.gallbladderz.openkick.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VideoQuality(
    val name: String,
    val trackGroup: TrackGroup?,
    val trackIndex: Int?,
    val isAudioOnly: Boolean = false
)

/**
 * Keeps retrying a download that failed because the connection dropped, instead of letting the
 * player fail. In a dead zone the player then just stays "loading" (spinner) and picks up again
 * on its own as soon as there is signal. While it is loading, Android still treats the app as
 * playing media, so it isn't frozen or cut off from data the way an errored, idle player is
 * after ten minutes in the background.
 *
 * Error pages from the server (the stream ended, access denied) still fail after a few tries.
 */
@UnstableApi
private class KeepTryingLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(Int.MAX_VALUE) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val defaultDelayMs = super.getRetryDelayMsFor(loadErrorInfo)
        if (defaultDelayMs == C.TIME_UNSET) return C.TIME_UNSET
        val isErrorPage = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }
            .any { it is HttpDataSource.InvalidResponseCodeException }
        if (isErrorPage && loadErrorInfo.errorCount > ERROR_PAGE_LOAD_RETRIES) return C.TIME_UNSET
        // 1 s, 2 s, ... then every 5 s. A failed try costs a few KB at most.
        return (loadErrorInfo.errorCount * 1_000L).coerceAtMost(MAX_LOAD_RETRY_DELAY_MS)
    }

    private companion object {
        const val ERROR_PAGE_LOAD_RETRIES = 6
        const val MAX_LOAD_RETRY_DELAY_MS = 5_000L
    }
}

@UnstableApi
class PlayerManager(
    context: Context,
    private val dataSourceFactory: DataSource.Factory
) {

    private val appContext = context.applicationContext

    var player: ExoPlayer? = null
        private set

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState = _playbackState.asStateFlow()
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady = _playWhenReady.asStateFlow()

    private val _availableQualities = MutableStateFlow<List<VideoQuality>>(emptyList())
    val availableQualities = _availableQualities.asStateFlow()

    private val _selectedQuality = MutableStateFlow<VideoQuality?>(null)
    val selectedQuality = _selectedQuality.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.update { isPlaying }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                recoveryAttempts = 0
                isRecovering = false
                releaseRecoveryWakeLock()
                onReadyForLiveCheck()
            } else if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) {
                // A rebuffer, a reload or a reconnect: check how far behind live we are once it
                // plays again.
                checkLiveAfterReady = true
            }
            // While we are about to reconnect, keep showing the spinner instead of a dead player.
            val shown = if (state == Player.STATE_IDLE && isRecovering) Player.STATE_BUFFERING else state
            _playbackState.update { shown }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRecovery(error)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _playWhenReady.update { playWhenReady }
        }

        override fun onTracksChanged(tracks: Tracks) {
            maybeFinishAudioOnly(tracks)
            _availableQualities.value = tracks.toVideoQualityOptions(appContext)
        }
    }

    private var isExplicitlyPausedByUser = false

    // --- Recovering from network drops (LTE/5G hand-offs, tunnels, dead spots) ---
    //
    // A short hiccup is absorbed by the load retries below. A longer one makes ExoPlayer give
    // up with an error and go idle; before this, nothing ever called prepare() again, so the
    // stream stayed dead until the user reopened it. Now we reconnect at the live edge by
    // ourselves: right away when Android reports a working connection, otherwise with backoff.

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private var networkCallbackRegistered = false
    private var currentIsLive = true
    private var isRecovering = false
    private var recoveryAttempts = 0
    private var recoveryStartedAtMs = 0L
    private var quickRetryPending = false

    // Set when we stopped trying after a long outage. The next time Android reports a working
    // connection we still give it one more go, so the stream comes back by itself.
    private var retryWhenSignalReturns = false
    private val recoveryRunnable = Runnable { attemptRecovery() }

    // ExoPlayer drops its own wake lock once it errors out. With the screen off (phone in a
    // car mount) the CPU could then sleep and our retry timer would never fire, so hold a
    // short partial wake lock of our own while we are reconnecting.
    private val recoveryWakeLock: PowerManager.WakeLock? =
        appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenKick:streamRecovery")
            ?.apply { setReferenceCounted(false) }

    private fun holdRecoveryWakeLock() {
        runCatching { recoveryWakeLock?.acquire(RECOVERY_WAKE_LOCK_MS) }
    }

    private fun releaseRecoveryWakeLock() {
        runCatching { if (recoveryWakeLock?.isHeld == true) recoveryWakeLock.release() }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Only a change to "working" counts as signal coming back; capability updates also
        // arrive for every small bandwidth change while the connection stays the same.
        private var wasValidated = false

        override fun onAvailable(network: Network) {
            mainHandler.post { onConnectionBack() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated && !wasValidated) mainHandler.post { onConnectionBack() }
            wasValidated = validated
        }

        override fun onLost(network: Network) {
            wasValidated = false
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val cm = connectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isRecoverable(error: PlaybackException): Boolean {
        val code = error.errorCode
        if (code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) return false
        // A 404/403 that keeps coming back usually means the stream ended; stop sooner.
        if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
            recoveryAttempts >= MAX_BAD_STATUS_ATTEMPTS
        ) return false
        return code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            code == PlaybackException.ERROR_CODE_TIMEOUT ||
            code in 2000..2999 || // network / IO errors
            code in 3000..3999    // a playlist cut off mid-download
    }

    /** True for a network error this class reconnects by itself (not a fall behind the window). */
    fun reconnectsAfter(error: PlaybackException): Boolean =
        error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && isRecoverable(error)

    private fun scheduleRecovery(error: PlaybackException) {
        val currentPlayer = player ?: return
        if (isExplicitlyPausedByUser || !currentPlayer.playWhenReady) return
        val now = SystemClock.elapsedRealtime()
        if (!isRecovering) recoveryStartedAtMs = now
        // ExoPlayer reports "stuck" after STUCK_BUFFERING_TIMEOUT_MS of loading with nothing
        // coming in: that is the hour without a stream, so stop here too.
        val outageTooLong = error.cause is StuckPlayerException ||
            now - recoveryStartedAtMs >= MAX_RECOVERY_MS
        if (!isRecoverable(error) || outageTooLong) {
            // Give up and show the real (stopped) state; pressing play tries again.
            cancelRecovery()
            retryWhenSignalReturns = outageTooLong
            _playbackState.update { currentPlayer.playbackState }
            return
        }
        isRecovering = true
        holdRecoveryWakeLock()
        _playbackState.update { Player.STATE_BUFFERING }
        mainHandler.removeCallbacks(recoveryRunnable)
        val delayMs = if (!hasUsableNetwork()) {
            // No signal at all: the network callback brings us back as soon as there is some.
            // Check now and then anyway in case that callback never comes.
            NO_NETWORK_CHECK_MS
        } else {
            RECOVERY_DELAYS_MS[recoveryAttempts.coerceAtMost(RECOVERY_DELAYS_MS.lastIndex)]
        }
        mainHandler.postDelayed(recoveryRunnable, delayMs)
    }

    private fun onConnectionBack() {
        val currentPlayer = player ?: return
        if (!isRecovering) {
            if (!retryWhenSignalReturns || isExplicitlyPausedByUser ||
                !currentPlayer.playWhenReady || currentPlayer.playerError == null
            ) return
            // We had stopped after a long outage; signal is back, so try once more.
            retryWhenSignalReturns = false
            isRecovering = true
            // A short window this time; if it still fails we wait for the next time signal returns.
            recoveryStartedAtMs = SystemClock.elapsedRealtime() - MAX_RECOVERY_MS + SIGNAL_BACK_RETRY_MS
            holdRecoveryWakeLock()
            _playbackState.update { Player.STATE_BUFFERING }
        }
        if (currentPlayer.playerError == null || quickRetryPending) return
        // Signal is back: reconnect now instead of waiting out the backoff.
        quickRetryPending = true
        mainHandler.removeCallbacks(recoveryRunnable)
        mainHandler.postDelayed(recoveryRunnable, NETWORK_SETTLE_MS)
    }

    private fun attemptRecovery() {
        quickRetryPending = false
        val currentPlayer = player ?: return cancelRecovery()
        if (!isRecovering || isExplicitlyPausedByUser || currentPlayer.playerError == null) {
            return cancelRecovery()
        }
        if (!hasUsableNetwork()) {
            // Still no signal: keep waiting without spending a try.
            mainHandler.postDelayed(recoveryRunnable, NO_NETWORK_CHECK_MS)
            return
        }
        recoveryAttempts++
        // Rejoin at the live edge: the seconds missed while offline are gone from a live
        // playlist anyway, and waiting for them is what causes "behind live window" errors.
        // While the user has rewound (live window or the stream's recording), reconnect where
        // they were instead.
        if (currentIsLive && !liveRewindActive) {
            // A reconnect always goes back to live.
            userChoseToBeBehind = false
            currentPlayer.seekToDefaultPosition()
        }
        currentPlayer.prepare()
        reapplyAudioOnlyAfterPrepare()
        currentPlayer.playWhenReady = true
    }

    private fun cancelRecovery() {
        mainHandler.removeCallbacks(recoveryRunnable)
        isRecovering = false
        quickRetryPending = false
        recoveryAttempts = 0
        retryWhenSignalReturns = false
        releaseRecoveryWakeLock()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        networkCallbackRegistered =
            runCatching { cm.registerDefaultNetworkCallback(networkCallback) }.isSuccess
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    // --- Screen lock: the only time the stream switches to audio only on its own ---

    private var screenOffReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenLocked()
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        screenOffReceiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(screenOffReceiver, filter)
            }
        }.isSuccess
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(screenOffReceiver) }
        screenOffReceiverRegistered = false
    }

    // --- Staying live: after a rebuffer or a return to the app, jump back to live ---
    //
    // A rebuffer resumes where the buffer ran out, which is behind live, and the player only
    // catches up slowly (a few percent faster than normal). When the user did not pause on
    // purpose, jump straight back to live instead.

    /** How far behind live the stream normally plays; the smallest offset seen while playing. */
    private var liveBaselineOffsetMs = C.TIME_UNSET
    private var checkLiveAfterReady = false
    /** The user paused (and so chose to be behind live) since the last jump to live. */
    private var userChoseToBeBehind = false
    private var lastLiveJumpAtMs = 0L
    /** The user rewound the live stream (live window or its recording); see LiveStreamPlayback. */
    private var liveRewindActive = false

    /** Called by the live rewind feature whenever the user leaves or returns to live. */
    fun setLiveRewindActive(active: Boolean) {
        liveRewindActive = active
        if (active) {
            userChoseToBeBehind = true
            checkLiveAfterReady = false
        }
    }

    private fun onReadyForLiveCheck() {
        val currentPlayer = player ?: return
        val offset = currentPlayer.currentLiveOffset
        if (!currentIsLive || !currentPlayer.isCurrentMediaItemLive || offset == C.TIME_UNSET) return
        if (!userChoseToBeBehind &&
            (liveBaselineOffsetMs == C.TIME_UNSET || offset < liveBaselineOffsetMs)
        ) {
            liveBaselineOffsetMs = offset
        }
        if (!checkLiveAfterReady) return
        checkLiveAfterReady = false
        jumpToLiveIfBehind()
    }

    private fun jumpToLiveIfBehind() {
        val currentPlayer = player ?: return
        if (!currentIsLive || liveRewindActive || userChoseToBeBehind || isExplicitlyPausedByUser) return
        if (!currentPlayer.playWhenReady || !currentPlayer.isCurrentMediaItemLive) return
        if (currentPlayer.playbackState != Player.STATE_READY) {
            // Still loading: check once it plays.
            checkLiveAfterReady = true
            return
        }
        val baseline = liveBaselineOffsetMs
        val offset = currentPlayer.currentLiveOffset
        if (baseline == C.TIME_UNSET || offset == C.TIME_UNSET) return
        if (offset <= baseline + BEHIND_LIVE_JUMP_MS) return
        // If the stream has no newer media than this, jumping again and again would loop.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveJumpAtMs < LIVE_JUMP_COOLDOWN_MS) return
        lastLiveJumpAtMs = now
        currentPlayer.seekToDefaultPosition()
    }

    private fun resetLiveTracking() {
        liveBaselineOffsetMs = C.TIME_UNSET
        checkLiveAfterReady = false
        userChoseToBeBehind = false
        lastLiveJumpAtMs = 0L
        liveRewindActive = false
    }

    // --- Audio only (the quality menu's option, and automatically while the screen is locked) ---
    //
    // Kick's HLS variants carry sound and picture in the same files, and ExoPlayer drops any
    // sound-only variant when video variants exist. Just disabling the video track also leaves
    // the HLS loader on whichever variant it was using (often 1080p), so "audio only" kept
    // downloading the full video. So audio only is two steps: first force the lowest variant
    // with video still on, and once that is in use, turn video off. The loader then stays on
    // the smallest variant and nothing is decoded for the picture.

    private var audioOnlyActive = false
    private var finishAudioOnlyPending = false
    private val finishAudioOnlyFallback = Runnable { onAudioOnlyFallback() }

    /** Saved when the app was hidden, so the previous quality comes back exactly. */
    private var stateBeforeHidden: Pair<TrackSelectionParameters, VideoQuality?>? = null

    private fun startAudioOnly() {
        val currentPlayer = player ?: return
        audioOnlyActive = true
        finishAudioOnlyPending = true
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setForceLowestBitrate(true)
            .build()
        mainHandler.removeCallbacks(finishAudioOnlyFallback)
        mainHandler.postDelayed(finishAudioOnlyFallback, AUDIO_ONLY_SWITCH_TIMEOUT_MS)
    }

    /** Step two: called from onTracksChanged once the lowest variant is the one selected. */
    private fun maybeFinishAudioOnly(tracks: Tracks) {
        if (!finishAudioOnlyPending) return
        val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val selected = (0 until video.length).filter { video.isTrackSelected(it) }
        if (selected.size != 1) return
        val lowest = (0 until video.length).minByOrNull {
            video.getTrackFormat(it).bitrate.takeIf { b -> b != Format.NO_VALUE } ?: Int.MAX_VALUE
        }
        if (lowest != null && video.getTrackFormat(lowest).bitrate != Format.NO_VALUE &&
            selected.first() != lowest
        ) return
        disableVideoForAudioOnly()
    }

    private fun onAudioOnlyFallback() {
        if (!finishAudioOnlyPending) return
        val currentPlayer = player ?: return
        // Not loaded yet: turning video off now would leave the loader on its first variant.
        if (currentPlayer.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) {
            mainHandler.postDelayed(finishAudioOnlyFallback, AUDIO_ONLY_SWITCH_TIMEOUT_MS)
            return
        }
        disableVideoForAudioOnly()
    }

    private fun disableVideoForAudioOnly() {
        finishAudioOnlyPending = false
        mainHandler.removeCallbacks(finishAudioOnlyFallback)
        val currentPlayer = player ?: return
        if (!audioOnlyActive) return
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
    }

    /** Back to video with [params] (forceLowestBitrate off unless [params] says otherwise). */
    private fun endAudioOnly(params: TrackSelectionParameters) {
        audioOnlyActive = false
        finishAudioOnlyPending = false
        mainHandler.removeCallbacks(finishAudioOnlyFallback)
        player?.trackSelectionParameters = params
    }

    /** After a fresh prepare the loader starts on its first variant again: redo the switch. */
    private fun reapplyAudioOnlyAfterPrepare() {
        if (audioOnlyActive) startAudioOnly()
    }

    /**
     * Called when the screen turns off. Only then does the stream switch to audio only:
     * leaving the app for another one keeps the video, so coming back needs no reload.
     */
    fun onScreenLocked() {
        val currentPlayer = player ?: return
        if (stateBeforeHidden != null || currentPlayer.currentMediaItem == null) return
        // Already on the user's own "Audio only": nothing to switch, nothing to restore.
        if (audioOnlyActive) return
        stateBeforeHidden = currentPlayer.trackSelectionParameters to _selectedQuality.value
        startAudioOnly()
    }

    /**
     * Called when the app is on screen again: back to the video quality picked before the
     * screen was locked, and back to live if the stream fell behind meanwhile.
     */
    fun onAppVisible() {
        stateBeforeHidden?.let { (params, quality) ->
            stateBeforeHidden = null
            _selectedQuality.update { quality }
            // Turning video back on reloads the stream; the jump to live follows once it plays.
            checkLiveAfterReady = true
            endAudioOnly(params)
            return
        }
        jumpToLiveIfBehind()
    }

    fun initializePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(appContext)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                // Loading is retried for as long as the connection is down (see
                // KeepTryingLoadErrorHandlingPolicy); only call it stuck after an hour of that.
                .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_TIMEOUT_MS)
                .build().apply {
                    addListener(playerListener)
                    registerNetworkCallback()
                    registerScreenOffReceiver()
                    if (!isExplicitlyPausedByUser) {
                        playWhenReady = true
                    }
                }
        }
    }

    fun setQuality(quality: VideoQuality) {
        val currentPlayer = player ?: return

        _selectedQuality.update { quality }
        // A choice made in the quality menu replaces whatever was saved when the app was hidden.
        stateBeforeHidden = null

        if (quality.isAudioOnly) {
            startAudioOnly()
            if (!isExplicitlyPausedByUser) currentPlayer.play()
            return
        }

        audioOnlyActive = false
        finishAudioOnlyPending = false
        mainHandler.removeCallbacks(finishAudioOnlyFallback)

        val builder = currentPlayer.trackSelectionParameters.buildUpon()
            .setForceLowestBitrate(false)

        builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)

        if (quality.trackGroup == null || quality.trackIndex == null) {

            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {

            builder.setOverrideForType(
                TrackSelectionOverride(
                    quality.trackGroup,
                    listOf(quality.trackIndex)
                )
            )
        }

        currentPlayer.trackSelectionParameters = builder.build()

        if (!isExplicitlyPausedByUser) {
            currentPlayer.play()
        }
    }

    fun setAudioOnly() {
        player ?: return
        val audioOnly = VideoQuality(
            appContext.getString(R.string.audio_only),
            null,
            null,
            isAudioOnly = true
        )
        _selectedQuality.update { audioOnly }
        stateBeforeHidden = null
        startAudioOnly()
    }

    fun seekToLiveEdge() {
        userChoseToBeBehind = false
        player?.seekToDefaultPosition()
    }

    fun replaceLivePlaybackSource(url: String, positionMs: Long = C.TIME_UNSET) {
        val currentPlayer = player ?: return
        val item = currentPlayer.currentMediaItem ?: return
        cancelRecovery()
        val source = HlsMediaSource.Factory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(KeepTryingLoadErrorHandlingPolicy())
            .createMediaSource(item.buildUpon().setUri(url).build())
        currentPlayer.setMediaSource(source, positionMs)
        currentPlayer.prepare()
        reapplyAudioOnlyAfterPrepare()
    }

    fun play(
        videoUrl: String,
        title: String,
        streamerName: String,
        avatarUrl: String,
        isLive: Boolean = true,
        startPositionMs: Long = C.TIME_UNSET
    ) {
        val currentPlayer = player ?: return
        isExplicitlyPausedByUser = false
        cancelRecovery()
        currentIsLive = isLive
        resetLiveTracking()
        _selectedQuality.update { null }
        _availableQualities.update { emptyList() }
        val mediaItem = MediaItem.Builder().setUri(videoUrl).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title)
                .setArtist(streamerName).setArtworkUri(android.net.Uri.parse(avatarUrl))
                .build()
        ).build()
        val mediaSource = if (isLive || videoUrl.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(KeepTryingLoadErrorHandlingPolicy())
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(KeepTryingLoadErrorHandlingPolicy())
                .createMediaSource(mediaItem)
        }
        if (audioOnlyActive && stateBeforeHidden == null) {
            // The quality menu resets to Auto for a new stream, so show video again too.
            endAudioOnly(
                currentPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .setForceLowestBitrate(false)
                    .build()
            )
        }
        currentPlayer.setMediaSource(mediaSource, startPositionMs)
        currentPlayer.prepare()
        reapplyAudioOnlyAfterPrepare()
        if (!isExplicitlyPausedByUser) {
            currentPlayer.playWhenReady = true
        }
        // ExoPlayer only reports playWhenReady when it changes, so publish the real value here.
        // Otherwise the UI can think a playing stream is paused (play icon, no PiP, "behind live").
        _playWhenReady.update { currentPlayer.playWhenReady }
    }

    fun pause() {
        isExplicitlyPausedByUser = true
        userChoseToBeBehind = true
        cancelRecovery()
        player?.pause()
    }

    fun resume() {
        isExplicitlyPausedByUser = false
        val current = player
        if (current != null && current.playerError != null) {
            // Pressing play on a stream that died reconnects it, at live.
            if (currentIsLive) {
                userChoseToBeBehind = false
                current.seekToDefaultPosition()
            }
            current.prepare()
            reapplyAudioOnlyAfterPrepare()
        }
        player?.play()
        player?.let { current -> _playWhenReady.update { current.playWhenReady } }
    }

    fun release() {
        stop()
        stateBeforeHidden = null
        audioOnlyActive = false
        finishAudioOnlyPending = false
        mainHandler.removeCallbacks(finishAudioOnlyFallback)
        unregisterNetworkCallback()
        unregisterScreenOffReceiver()
        resetLiveTracking()
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    fun stop() {
        isExplicitlyPausedByUser = true
        cancelRecovery()
        // Keep the player's own flag in step with _playWhenReady below. player.stop() leaves
        // playWhenReady untouched, so the next play() would set it to true without any event.
        player?.playWhenReady = false
        player?.stop()
        player?.clearMediaItems()
        _isPlaying.update { false }
        _playbackState.update { Player.STATE_IDLE }
        _playWhenReady.update { false }
        _availableQualities.update { emptyList() }
        _selectedQuality.update { null }
    }

    private companion object {
        /** How long to let a new connection settle before reconnecting the stream. */
        const val NETWORK_SETTLE_MS = 750L

        /** Wait before each reconnect while there is signal but loads keep failing. */
        val RECOVERY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        /** Stop reconnecting after an hour without a stream (unless signal comes back later). */
        const val MAX_RECOVERY_MS = 60 * 60 * 1000L

        /** Same hour, for a player that keeps loading with nothing coming in. */
        const val STUCK_BUFFERING_TIMEOUT_MS = 60 * 60 * 1000

        /** How long to keep trying after signal returns, once we had stopped. */
        const val SIGNAL_BACK_RETRY_MS = 2 * 60 * 1000L

        /** How often to look again while there is no network at all. */
        const val NO_NETWORK_CHECK_MS = 30_000L

        /** Fewer tries when the server answers with an error page instead of the stream. */
        const val MAX_BAD_STATUS_ATTEMPTS = 6

        /** Upper bound on the wake lock above, in case something goes wrong. */
        const val RECOVERY_WAKE_LOCK_MS = MAX_RECOVERY_MS + 60 * 1000L

        /** Longest wait for the lowest variant before turning video off anyway. */
        const val AUDIO_ONLY_SWITCH_TIMEOUT_MS = 3_000L

        /** Jump back to live when a resumed stream is this much further behind than usual. */
        const val BEHIND_LIVE_JUMP_MS = 5_000L

        /** At most one automatic jump to live in this long, so a stream with gaps can't loop. */
        const val LIVE_JUMP_COOLDOWN_MS = 15_000L
    }
}
