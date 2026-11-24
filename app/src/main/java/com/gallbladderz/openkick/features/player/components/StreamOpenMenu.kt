/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */



package com.gallbladderz.openkick.features.player.components


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gallbladderz.openkick.R

/**
 * Long-press menu for a live channel: watch it now in a new tab, or park it in a background
 * tab that loads nothing until it is opened.
 */
@Composable
fun StreamOpenMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onOpenInBackground: (() -> Unit)?
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.stream_open_new_tab)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenInNewTab()
            }
        )
        if (onOpenInBackground != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.stream_open_background_tab)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onOpenInBackground()
                }
            )
        }
    }
}
