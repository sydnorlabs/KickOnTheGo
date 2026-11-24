/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallbladderz.openkick.core.player.StreamTabsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Saves the open stream tabs so they survive the app being closed by the system. */
class StreamTabsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val tabsFlow: Flow<StreamTabsState> = context.dataStore.data.map { preferences ->
        decode(preferences[OPEN_STREAM_TABS])
    }

    suspend fun save(state: StreamTabsState) {
        context.dataStore.edit { preferences ->
            if (state.tabs.isEmpty()) {
                preferences.remove(OPEN_STREAM_TABS)
            } else {
                preferences[OPEN_STREAM_TABS] = json.encodeToString(StreamTabsState.serializer(), state)
            }
        }
    }

    private fun decode(raw: String?): StreamTabsState {
        if (raw.isNullOrBlank()) return StreamTabsState()
        return runCatching { json.decodeFromString(StreamTabsState.serializer(), raw) }
            .getOrDefault(StreamTabsState())
            .let { state ->
                val tabs = state.tabs
                    .filter { it.slug.isNotBlank() }
                    .distinctBy { it.slug.lowercase() }
                    .takeLast(StreamTabsState.MAX_TABS)
                val active = state.activeSlug?.takeIf { slug ->
                    tabs.any { it.slug.equals(slug, ignoreCase = true) }
                } ?: tabs.maxByOrNull { it.lastActive }?.slug
                state.copy(tabs = tabs, activeSlug = active)
            }
    }

    private companion object {
        val OPEN_STREAM_TABS = stringPreferencesKey("open_stream_tabs")
    }
}
