// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** The list in a container curved at the top, under the bar's own layer, as in Google Messages (owner, 2026-11-01). */
@Composable
internal fun ListContainer(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())
            .clip(
                RoundedCornerShape(topStart = LIST_CORNER, topEnd = LIST_CORNER),
            ).background(MaterialTheme.colorScheme.surface),
    ) { content() }
}

private val LIST_CORNER = 28.dp
