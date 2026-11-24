/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.core.player


import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PlayerExpandedState {
    HIDDEN, MINI, EXPANDED
}

sealed interface PlayerMedia {
    data class Live(val streamerName: String) : PlayerMedia

    sealed interface Recording : PlayerMedia {
        data class Clip(val id: String) : Recording
        data class Vod(val id: String, val channelSlug: String?) : Recording
        data class Download(val id: String) : Recording
    }
}

@Stable
class GlobalPlayerController {
    var playerState by mutableStateOf(PlayerExpandedState.HIDDEN)
        private set

    var currentMedia by mutableStateOf<PlayerMedia?>(null)
        private set

    /** The live stream that is open, if any (a clip or VOD is not one). */
    val currentStreamerName: String?
        get() = (currentMedia as? PlayerMedia.Live)?.streamerName

    /** Goes up each time something asks for the player with chat (the media notification). */
    var chatViewRequests by mutableIntStateOf(0)
        private set

    /**
     * Open stream tabs. Only the active tab plays video and holds a chat connection; the
     * others are just a slug, avatar and title until you switch to them. Tabs are for live
     * streams; a clip or VOD opened in the player leaves them in place for the next live one.
     */
    var tabsState by mutableStateOf(StreamTabsState())
        private set

    /** Opens [streamerName] in a tab (or focuses it if already open) and expands the player. */
    fun expandPlayer(streamerName: String) {
        tabsState = tabsState.open(streamerName)
        openPlayer(PlayerMedia.Live(tabsState.activeSlug ?: streamerName))
    }

    fun openRecording(recording: PlayerMedia.Recording) = openPlayer(recording)

    private fun openPlayer(media: PlayerMedia) {
        currentMedia = media
        playerState = PlayerExpandedState.EXPANDED
    }

    /**
     * Adds [streamerName] as a tab without switching to it: the stream that is playing keeps
     * playing and the new tab loads nothing until it is tapped. With no stream playing, it
     * opens the player on it instead.
     */
    fun openInBackgroundTab(streamerName: String) {
        if (currentStreamerName == null || playerState == PlayerExpandedState.HIDDEN) {
            expandPlayer(streamerName)
            return
        }
        tabsState = tabsState.openInBackground(streamerName)
    }

    /** Switches the player to an already open tab, keeping the player's size as it is. */
    fun selectTab(streamerName: String) {
        tabsState = tabsState.select(streamerName)
        tabsState.activeSlug?.let { currentMedia = PlayerMedia.Live(it) }
    }

    /** Closes one tab. Closing the last tab closes the player. */
    fun closeTab(streamerName: String) {
        tabsState = tabsState.close(streamerName)
        if (tabsState.tabs.isEmpty()) {
            hidePlayer()
        } else if (currentMedia is PlayerMedia.Live) {
            tabsState.activeSlug?.let { currentMedia = PlayerMedia.Live(it) }
        }
    }

    /** Remembers the avatar and title of a tab so the tab strip can show them later. */
    fun updateTabInfo(streamerName: String, avatarUrl: String, title: String) {
        tabsState = tabsState.updateInfo(streamerName, avatarUrl, title)
    }

    /**
     * Puts back tabs saved from an earlier app session without starting playback. They show
     * up in the tab strip the next time a stream is opened.
     */
    fun restoreTabs(saved: StreamTabsState) {
        if (playerState == PlayerExpandedState.HIDDEN && tabsState.tabs.isEmpty()) {
            tabsState = saved
        }
    }

    /**
     * Opens the player with its chat (not full screen) if a stream is open. Used by the media
     * notification.
     */
    fun showPlayerWithChat() {
        if (currentStreamerName != null && playerState != PlayerExpandedState.HIDDEN) {
            playerState = PlayerExpandedState.EXPANDED
            chatViewRequests++
        }
    }

    fun minimizePlayer() {
        if (playerState == PlayerExpandedState.EXPANDED) {
            playerState = PlayerExpandedState.MINI
        }
    }

    /**
     * Closes the player. By default its tabs are closed too; pass [keepTabs] when something
     * else (a clip or a past broadcast) takes over the screen, so the tabs come back the next
     * time a live stream is opened.
     */
    fun hidePlayer(keepTabs: Boolean = false) {
        playerState = PlayerExpandedState.HIDDEN
        currentMedia = null
        if (!keepTabs) tabsState = StreamTabsState()
    }

    fun updatePlayerState(state: PlayerExpandedState) {
        if (state == PlayerExpandedState.HIDDEN) hidePlayer() else playerState = state
    }
}

val LocalGlobalPlayerController = compositionLocalOf<GlobalPlayerController> {
    error("No GlobalPlayerController provided")
}
