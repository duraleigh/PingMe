// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/** "2 people appear on more than one network · Review" at the top of the inbox (owner, Phase 7). */
@Composable
fun SuggestionsCard(
    count: Int,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onReview,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(UiR.drawable.ic_call_merge), null, Modifier.padding(end = 12.dp))
            Text(
                pluralStringResource(R.plurals.merge_suggestions_card, count, count),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.merge_suggestions_review),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
