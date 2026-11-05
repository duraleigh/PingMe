// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.Person
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.R as UiR

// New chat's rows and bars (owner, 2026-11-01, from Google Messages on the phone).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatBar(
    naming: Boolean,
    building: Boolean,
    onBack: () -> Unit,
) {
    val title =
        when {
            naming -> R.string.new_group_add_name
            building -> R.string.new_group_chat
            else -> R.string.fab_new_chat
        }
    TopAppBar(
        title = { Text(stringResource(title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        },
    )
}

@Composable
internal fun NextButton(onClick: () -> Unit) {
    // The words first and the arrow after, as in Google Messages.
    androidx.compose.material3.ExtendedFloatingActionButton(onClick) {
        Text(stringResource(R.string.new_group_next))
        Icon(painterResource(UiR.drawable.ic_arrow_forward), null, Modifier.padding(start = 12.dp))
    }
}

/** "Start a chat with" or "Add" whatever has been typed. */
@Composable
internal fun TypedRow(
    typed: String,
    building: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        enabled = enabled,
        leadingContent = {
            Icon(painterResource(if (building) UiR.drawable.ic_add else UiR.drawable.ic_chat), null)
        },
    ) { Text(stringResource(if (building) R.string.new_group_add else R.string.new_chat_start, typed)) }
}

/** A letter over each run of people, whose rows join into one card, as in Google Messages. */
internal fun androidx.compose.foundation.lazy.LazyListScope.people(
    state: NewChatUiState,
    actions: NewChatActions,
) {
    state.suggestions.groupBy(::letterOf).forEach { (letter, people) ->
        item(key = "letter-$letter") {
            Text(
                letter,
                Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        itemsIndexed(people, key = { _, person -> person.id.value }) { i, person ->
            PersonRow(
                person,
                picked = person.networkHandle in state.members,
                first = i == 0,
                last = i == people.lastIndex,
                enabled = !state.working,
                onClick = { actions.onChoose(person.networkHandle) },
                // Holding does what a tap does here: both add, as in Google Messages.
                onHold = { actions.onChoose(person.networkHandle) },
            )
        }
    }
}

private fun letterOf(person: Person): String =
    person.name
        .firstOrNull()
        ?.takeIf(Char::isLetter)
        ?.uppercase() ?: "#"

/**
 * One person. Picked for a group, the row rounds into a pill with a check where the picture
 * was, and stays in the list (owner, 2026-11-01, from Google Messages on the phone).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(
    person: Person,
    picked: Boolean,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val top by androidx.compose.animation.core.animateDpAsState(
        if (picked) {
            PILL_CORNER
        } else if (first) {
            CARD_CORNER
        } else {
            JOIN_CORNER
        },
        label = "pickTop",
    )
    val bottom by androidx.compose.animation.core.animateDpAsState(
        if (picked) {
            PILL_CORNER
        } else if (last) {
            CARD_CORNER
        } else {
            JOIN_CORNER
        },
        label = "pickBottom",
    )
    androidx.compose.material3.Surface(
        Modifier
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(
                androidx.compose.foundation.shape
                    .RoundedCornerShape(top, top, bottom, bottom),
            ).combinedClickable(enabled = enabled, onLongClick = onHold, onClick = onClick)
            // The list item keeps its words to itself, so the row has to say who it is.
            .semantics { contentDescription = person.name },
        color = if (picked) colours.secondaryContainer else colours.surfaceContainer,
    ) {
        androidx.compose.material3.ListItem(
            leadingContent = {
                if (picked) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(40.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(colours.primaryContainer),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { Icon(painterResource(UiR.drawable.ic_check), null, tint = colours.onPrimaryContainer) }
                } else {
                    Avatar(person.name, size = 40.dp, photo = person.photo)
                }
            },
            headlineContent = { Text(person.name) },
            // The number or username, never a network's raw id (owner, 2026-10-05: "@lid").
            supportingContent = secondLine(person)?.let { line -> { Text(line) } },
            colors =
                androidx.compose.material3.ListItemDefaults
                    .colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

private val PILL_CORNER = 36.dp
private val CARD_CORNER = 24.dp
private val JOIN_CORNER = 4.dp

/** "To:" and everyone picked so far, in one rounded box, as in Google Messages. */
@Composable
internal fun ToField(
    state: NewChatUiState,
    actions: NewChatActions,
) {
    androidx.compose.material3.Surface(
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(start = 20.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.new_chat_to), style = MaterialTheme.typography.bodyLarge)
            Column(Modifier.padding(start = 4.dp)) {
                if (state.members.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(start = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.members.forEach { handle ->
                            val name = state.memberNames[handle] ?: handle
                            InputChip(
                                selected = true,
                                onClick = { actions.onRemove(handle) },
                                label = { Text(name) },
                                trailingIcon = {
                                    Icon(
                                        painterResource(UiR.drawable.ic_close),
                                        stringResource(R.string.new_group_remove, name),
                                    )
                                },
                            )
                        }
                    }
                }
                androidx.compose.material3.TextField(
                    state.text,
                    actions.onText,
                    Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(
                                if (state.members.isEmpty()) R.string.new_chat_who else R.string.new_chat_anyone_else,
                            ),
                        )
                    },
                    singleLine = true,
                    colors =
                        androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                )
            }
        }
    }
}
