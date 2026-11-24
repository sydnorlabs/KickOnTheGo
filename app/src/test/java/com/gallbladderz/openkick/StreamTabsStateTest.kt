/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gallbladderz.openkick

import com.gallbladderz.openkick.core.player.StreamTabsState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamTabsStateTest {

    private fun StreamTabsState.slugs() = tabs.map { it.slug }

    @Test fun openingAddsTabAndMakesItActive() {
        val state = StreamTabsState().open("xqc").open("adin")
        assertEquals(listOf("xqc", "adin"), state.slugs())
        assertEquals("adin", state.activeSlug)
    }

    @Test fun openingAnOpenStreamOnlyFocusesIt() {
        val state = StreamTabsState().open("xqc").open("adin").open("XQC")
        assertEquals(listOf("xqc", "adin"), state.slugs())
        assertEquals("xqc", state.activeSlug)
    }

    @Test fun blankSlugIsIgnored() {
        val state = StreamTabsState().open("xqc")
        assertSame(state, state.open("  "))
    }

    @Test fun capDropsLeastRecentlyUsedTab() {
        val state = StreamTabsState()
            .open("a").open("b").open("c")
            .select("a")
            .open("d", maxTabs = 3)
        assertEquals(listOf("a", "c", "d"), state.slugs())
        assertEquals("d", state.activeSlug)
    }

    @Test fun defaultCapIsFive() {
        var state = StreamTabsState()
        listOf("a", "b", "c", "d", "e", "f").forEach { state = state.open(it) }
        assertEquals(StreamTabsState.MAX_TABS, state.tabs.size)
        assertEquals(listOf("b", "c", "d", "e", "f"), state.slugs())
    }

    @Test fun closingActiveTabMovesToRightNeighbour() {
        val state = StreamTabsState().open("a").open("b").open("c").select("b").close("b")
        assertEquals(listOf("a", "c"), state.slugs())
        assertEquals("c", state.activeSlug)
    }

    @Test fun closingLastPositionActiveTabMovesLeft() {
        val state = StreamTabsState().open("a").open("b").open("c").close("c")
        assertEquals("b", state.activeSlug)
    }

    @Test fun closingInactiveTabKeepsActive() {
        val state = StreamTabsState().open("a").open("b").close("a")
        assertEquals(listOf("b"), state.slugs())
        assertEquals("b", state.activeSlug)
    }

    @Test fun closingOnlyTabLeavesNoActiveTab() {
        val state = StreamTabsState().open("a").close("a")
        assertEquals(emptyList<String>(), state.slugs())
        assertNull(state.activeSlug)
    }

    @Test fun updateInfoStoresAvatarAndTitle() {
        val state = StreamTabsState().open("a").open("b").updateInfo("A", "https://img/a.png", "Hello")
        assertEquals("https://img/a.png", state.find("a")?.avatarUrl)
        assertEquals("Hello", state.find("a")?.title)
        assertEquals("b", state.activeSlug)
    }

    @Test fun survivesSavingAndLoading() {
        val state = StreamTabsState().open("a").open("b").updateInfo("b", "u", "t")
        val json = Json.encodeToString(StreamTabsState.serializer(), state)
        assertEquals(state, Json.decodeFromString(StreamTabsState.serializer(), json))
    }

    @Test fun backgroundTabDoesNotSwitchAway() {
        val state = StreamTabsState().open("a").openInBackground("b")
        assertEquals(listOf("a", "b"), state.slugs())
        assertEquals("a", state.activeSlug)
    }

    @Test fun backgroundTabForOpenStreamChangesNothing() {
        val state = StreamTabsState().open("a").open("b")
        assertSame(state, state.openInBackground("A"))
    }

    @Test fun backgroundTabWithNothingPlayingBecomesActive() {
        val state = StreamTabsState().openInBackground("a")
        assertEquals("a", state.activeSlug)
    }

    @Test fun backgroundTabNeverDropsThePlayingStream() {
        // "a" is playing but is the least recently used by tick order after background adds.
        var state = StreamTabsState().open("a")
        listOf("b", "c", "d").forEach { state = state.openInBackground(it, maxTabs = 3) }
        assertEquals("a", state.activeSlug)
        assertEquals(listOf("a", "c", "d"), state.slugs())
    }
}
