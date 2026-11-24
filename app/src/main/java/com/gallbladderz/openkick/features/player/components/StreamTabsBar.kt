/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.features.player.components


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gallbladderz.openkick.R
import com.gallbladderz.openkick.core.player.StreamTab
import com.gallbladderz.openkick.core.ui.components.KickAvatar

/**
 * A row of open streams, like browser tabs. Tapping a tab switches the player to it; only that
 * stream plays, the others stay paused until you come back to them.
 *
 * The first tab is a pinned "Following" tab: it swaps the chat area for your follow list
 * while the current stream keeps playing.
 */
@Composable
fun StreamTabsBar(
    tabs: List<StreamTab>,
    activeSlug: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
    isFollowingSelected: Boolean = false,
    onFollowingClick: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.slug.equals(activeSlug, ignoreCase = true) }

    LaunchedEffect(activeIndex, tabs.size, isFollowingSelected) {
        // +1 because the pinned Following tab is the first item.
        if (isFollowingSelected) listState.animateScrollToItem(0)
        else if (activeIndex >= 0) listState.animateScrollToItem(activeIndex + 1)
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item(key = "pinned_following") {
            FollowingTabChip(isSelected = isFollowingSelected, onClick = onFollowingClick)
        }
        items(tabs, key = { it.slug.lowercase() }) { tab ->
            StreamTabChip(
                tab = tab,
                isActive = tab.slug.equals(activeSlug, ignoreCase = true),
                isHighlighted = !isFollowingSelected,
                onClick = { onSelectTab(tab.slug) },
                onClose = { onCloseTab(tab.slug) }
            )
        }
        item(key = "new_tab") {
            val newTabDesc = stringResource(R.string.stream_tabs_new)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNewTab)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = newTabDesc,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun FollowingTabChip(isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.followers),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StreamTabChip(
    tab: StreamTab,
    isActive: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    // While the Following tab is showing, the playing stream gets a softer highlight.
    val containerColor = when {
        isActive && isHighlighted -> MaterialTheme.colorScheme.primaryContainer
        isActive -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isActive && isHighlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        isActive -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val closeDesc = stringResource(R.string.stream_tabs_close, tab.slug)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 2.dp)
        ) {
            KickAvatar(
                avatarUrl = tab.avatarUrl,
                streamerName = tab.slug,
                size = 24.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = tab.slug,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isActive && isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = closeDesc,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(6.dp)
            )
        }
    }
}
