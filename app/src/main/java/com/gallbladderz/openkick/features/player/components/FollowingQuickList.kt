/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.features.player.components


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallbladderz.openkick.R
import com.gallbladderz.openkick.core.ui.components.KickAvatar
import com.gallbladderz.openkick.core.ui.components.ViewerCountBadge
import com.gallbladderz.openkick.features.following.FollowedStreamerUi
import com.gallbladderz.openkick.features.following.FollowingUiState

/**
 * Your follows, shown inside the player (as the pinned Following tab or the pop-up) while the
 * current stream keeps playing. Live channels come first.
 *
 * The list itself is loaded and kept fresh by the player (see PlayerRoute), so it is
 * already filled in when the tab is tapped.
 */
@Composable
fun FollowingQuickList(
    openTabSlugs: List<String>,
    onOpenInNewTab: (String) -> Unit,
    onOpenInBackground: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    state: FollowingUiState,
    modifier: Modifier = Modifier
) {

    // A Surface (not a plain background) so text and icons default to the theme's light
    // content color, like the rest of the app, instead of a dark one.
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box {
            when (val uiState = state) {
                is FollowingUiState.Loading -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )

                is FollowingUiState.Error -> Text(
                    text = uiState.message.asString(),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                is FollowingUiState.Success -> {
                    val streamers = uiState.liveStreamers + uiState.offlineStreamers
                    if (streamers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.empty_follow_someone),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(streamers, key = { it.slug }) { streamer ->
                                val isOpen = openTabSlugs.any { it.equals(streamer.slug, ignoreCase = true) }
                                FollowingQuickRow(
                                    streamer = streamer,
                                    isOpen = isOpen,
                                    onOpenInNewTab = { onOpenInNewTab(streamer.slug) },
                                    onOpenInBackground = { onOpenInBackground(streamer.slug) },
                                    onProfileClick = { onProfileClick(streamer.slug) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowingQuickRow(
    streamer: FollowedStreamerUi,
    isOpen: Boolean,
    onOpenInNewTab: () -> Unit,
    onOpenInBackground: () -> Unit,
    onProfileClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (streamer.isLive) onOpenInNewTab() else onProfileClick() },
                    onLongClick = { if (streamer.isLive) showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .alpha(if (streamer.isLive) 1f else 0.55f)
        ) {
            KickAvatar(avatarUrl = streamer.avatarUrl, streamerName = streamer.username, size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Same look as the app's Following screen: white bold names, grey titles.
                Text(
                    text = streamer.username,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (streamer.isLive) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (streamer.isLive) streamer.streamTitle else stringResource(R.string.offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (streamer.isLive) {
                ViewerCountBadge(
                    viewers = streamer.viewers,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isOpen) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.stream_tab_already_open),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    IconButton(onClick = onOpenInBackground) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.stream_open_background_tab),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        StreamOpenMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onOpenInNewTab = onOpenInNewTab,
            onOpenInBackground = if (isOpen) null else onOpenInBackground
        )
    }
}
