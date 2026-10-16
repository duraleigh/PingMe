// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Every drawer that slides up from the bottom. It opens fully rather than stopping
 * halfway, and its content keeps clear of the keyboard, so a picker with a search box
 * stays in view when the keyboard comes up (owner, 2026-10-05: the keyboard covered the
 * GIF, emoji, and colour pickers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingMeSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.imePadding(), content = content)
    }
}
