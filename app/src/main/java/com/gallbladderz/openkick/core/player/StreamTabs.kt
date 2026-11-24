/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.core.player

import kotlinx.serialization.Serializable

/**
 * One open stream tab. Only the slug is needed to play it again; avatar and title are
 * remembered from the last time the tab was active so the tab strip can show them without
 * any network or player work while the tab is in the background.
 */
@Serializable
data class StreamTab(
    val slug: String,
    val avatarUrl: String = "",
    val title: String = "",
    val lastActive: Long = 0L
)

/**
 * Immutable list of open stream tabs plus which one is active.
 *
 * Battery note: tabs are only bookkeeping. The app keeps a single player and a single chat
 * connection, which always belong to [activeSlug]; switching tabs re-points them.
 */
@Serializable
data class StreamTabsState(
    val tabs: List<StreamTab> = emptyList(),
    val activeSlug: String? = null,
    val tick: Long = 0L
) {
    val activeTab: StreamTab?
        get() = activeSlug?.let(::find)

    fun find(slug: String): StreamTab? = tabs.firstOrNull { it.slug.equals(slug, ignoreCase = true) }

    /**
     * Opens [slug] as a tab and makes it active. If it is already open it is just focused.
     * When [maxTabs] would be exceeded, the least recently used tab is dropped.
     */
    fun open(slug: String, maxTabs: Int = MAX_TABS): StreamTabsState {
        val cleanSlug = slug.trim()
        if (cleanSlug.isEmpty()) return this
        if (find(cleanSlug) != null) return select(cleanSlug)

        val nextTick = tick + 1
        return copy(
            tabs = makeRoom(maxTabs, protectSlug = null) + StreamTab(slug = cleanSlug, lastActive = nextTick),
            activeSlug = cleanSlug,
            tick = nextTick
        )
    }

    /**
     * Adds [slug] as a tab without switching to it, so the stream that is playing keeps
     * playing and the new tab loads nothing until it is opened. If it is already open, nothing
     * changes. With no active stream, the new tab becomes the active one.
     */
    fun openInBackground(slug: String, maxTabs: Int = MAX_TABS): StreamTabsState {
        val cleanSlug = slug.trim()
        if (cleanSlug.isEmpty() || find(cleanSlug) != null) return this
        if (activeTab == null) return open(cleanSlug, maxTabs)

        val nextTick = tick + 1
        return copy(
            tabs = makeRoom(maxTabs, protectSlug = activeSlug) + StreamTab(slug = cleanSlug, lastActive = nextTick),
            tick = nextTick
        )
    }

    /** Drops least recently used tabs until one more fits, never dropping [protectSlug]. */
    private fun makeRoom(maxTabs: Int, protectSlug: String?): List<StreamTab> {
        var kept = tabs
        val limit = maxTabs.coerceAtLeast(1)
        while (kept.size >= limit) {
            val oldest = kept
                .filterNot { protectSlug != null && it.slug.equals(protectSlug, ignoreCase = true) }
                .minByOrNull { it.lastActive } ?: break
            kept = kept - oldest
        }
        return kept
    }

    fun select(slug: String): StreamTabsState {
        val target = find(slug) ?: return this
        val nextTick = tick + 1
        return copy(
            tabs = tabs.map { if (it === target) it.copy(lastActive = nextTick) else it },
            activeSlug = target.slug,
            tick = nextTick
        )
    }

    /**
     * Closes [slug]. If it was the active tab, the neighbour to its right (or left, if it was
     * last) becomes active, which is how browser tabs behave.
     */
    fun close(slug: String): StreamTabsState {
        val index = tabs.indexOfFirst { it.slug.equals(slug, ignoreCase = true) }
        if (index < 0) return this
        val remaining = tabs.toMutableList().apply { removeAt(index) }
        val wasActive = activeSlug?.equals(slug, ignoreCase = true) == true
        if (!wasActive) return copy(tabs = remaining)
        if (remaining.isEmpty()) return copy(tabs = remaining, activeSlug = null)

        val neighbour = remaining[index.coerceAtMost(remaining.lastIndex)]
        return copy(tabs = remaining).select(neighbour.slug)
    }

    fun updateInfo(slug: String, avatarUrl: String, title: String): StreamTabsState {
        val target = find(slug) ?: return this
        if (target.avatarUrl == avatarUrl && target.title == title) return this
        return copy(
            tabs = tabs.map {
                if (it === target) it.copy(avatarUrl = avatarUrl, title = title) else it
            }
        )
    }

    companion object {
        const val MAX_TABS = 5
    }
}
