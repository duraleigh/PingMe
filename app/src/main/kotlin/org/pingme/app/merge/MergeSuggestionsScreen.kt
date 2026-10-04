// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.app.inbox.NetworkBadge
import org.pingme.core.model.ChatId
import org.pingme.core.service.merge.MergeReason
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.R as UiR

/** The suggestions screen with its view model. */
@Composable
fun MergeSuggestionsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MergeSuggestionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { text ->
            snackbar.showSnackbar(if (text == "merged") resources.getString(R.string.merge_done) else text)
        }
    }
    MergeSuggestionsScreen(
        state,
        SuggestionActions(viewModel::remove, viewModel::add, viewModel::dismiss, viewModel::merge),
        onBack,
        modifier,
        snackbar,
    )
}

/** Merge suggestions (UI_DESIGN.md 10.15; owner, Phase 7): one editable card per person. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeSuggestionsScreen(
    state: MergeSuggestionsUiState,
    actions: SuggestionActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.merge_suggestions_title)) },
                navigationIcon = {
                    IconButton(
                        onBack,
                    ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    stringResource(
                        if (!state.loading && state.cards.isEmpty()) {
                            R.string.merge_suggestions_empty
                        } else {
                            R.string.merge_suggestions_note
                        },
                    ),
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.cards, key = { it.key }) { card -> SuggestionCardView(card, actions) }
        }
    }
}

@Composable
private fun SuggestionCardView(
    card: SuggestionCard,
    actions: SuggestionActions,
) {
    var picking by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            val labels = card.reasons.map { stringResource(reasonLabel(it)) }
            Text(
                labels.joinToString(" · "),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            card.members.forEach { member ->
                ListItem(
                    headlineContent = { Text(member.title) },
                    supportingContent = { NetworkBadge(member.network) },
                    leadingContent = { Avatar(member.title, size = 40.dp, photo = member.photo) },
                    trailingContent = {
                        IconButton({ actions.onRemove(card.key, member.id) }) {
                            Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.merge_remove))
                        }
                    },
                )
            }
            TextButton({ picking = true }, Modifier.padding(horizontal = 8.dp)) {
                Icon(painterResource(UiR.drawable.ic_add), null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.merge_add_chat))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                TextButton({ actions.onDismiss(card.key) }) { Text(stringResource(R.string.merge_dismiss)) }
                Button({ actions.onMerge(card) }, enabled = card.members.size >= 2) {
                    Icon(painterResource(UiR.drawable.ic_call_merge), null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.merge_confirm))
                }
            }
        }
    }
    if (picking) ChatPickerSheet(card.candidates, { actions.onAdd(card.key, it) }) { picking = false }
}

private fun reasonLabel(reason: MergeReason) =
    when (reason) {
        MergeReason.CONTACT -> R.string.merge_reason_contact
        MergeReason.NUMBER -> R.string.merge_reason_number
        MergeReason.NAME -> R.string.merge_reason_name
    }
