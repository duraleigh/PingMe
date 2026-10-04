// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.Account
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceIcon
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * The bottom bar on phones (UI_DESIGN.md 3.1, 3.7): All, then the user's picks, with unread
 * badges, and a fixed fifth button, More, that lists everything else (owner, 2026-10-03).
 */
@Composable
fun InboxBottomBar(
    bar: List<BarEntry>,
    selected: InboxBarItem?,
    accounts: List<Account>,
    actions: BarActions,
    modifier: Modifier = Modifier,
    more: List<BarEntry> = emptyList(),
) {
    ShortNavigationBar(modifier.testTag(INBOX_BAR)) {
        bar.forEach { entry ->
            BarButton(entry, accounts, actions) { narrowMenu ->
                ShortNavigationBarItem(
                    selected = entry.item == selected,
                    onClick = { actions.onSelect(entry.item) },
                    icon = { BarIcon(entry) },
                    label = { Text(barLabel(entry, accounts), maxLines = 1) },
                    modifier = narrowMenu,
                )
            }
        }
        MoreButton(more, selected, accounts, actions) { open, inMore ->
            ShortNavigationBarItem(
                selected = inMore,
                onClick = open,
                icon = { MoreIcon(more) },
                label = { Text(stringResource(R.string.bar_more), maxLines = 1) },
            )
        }
    }
}

/** The same items as a navigation rail on tablets and unfolded foldables (UI_DESIGN.md 3.7). */
@Composable
fun InboxRail(
    bar: List<BarEntry>,
    selected: InboxBarItem?,
    accounts: List<Account>,
    actions: BarActions,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    more: List<BarEntry> = emptyList(),
) {
    WideNavigationRail(modifier, header = header) {
        bar.forEach { entry ->
            BarButton(entry, accounts, actions) { narrowMenu ->
                WideNavigationRailItem(
                    selected = entry.item == selected,
                    onClick = { actions.onSelect(entry.item) },
                    icon = { BarIcon(entry) },
                    label = { Text(barLabel(entry, accounts), maxLines = 1) },
                    railExpanded = false,
                    modifier = narrowMenu,
                )
            }
        }
        MoreButton(more, selected, accounts, actions) { open, inMore ->
            WideNavigationRailItem(
                selected = inMore,
                onClick = open,
                icon = { MoreIcon(more) },
                label = { Text(stringResource(R.string.bar_more), maxLines = 1) },
                railExpanded = false,
            )
        }
    }
}

/**
 * Wraps one button so a long-press on a network narrows it to one account or folder
 * (UI_DESIGN.md 6.4, 6.5), remembered until changed.
 */
@Composable
private fun BarButton(
    entry: BarEntry,
    accounts: List<Account>,
    actions: BarActions,
    button: @Composable (Modifier) -> Unit,
) {
    val item = entry.item as? InboxBarItem.Network
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val narrowable = item != null && entry.narrowOptions.isNotEmpty()
    val narrowed = entry.narrowedTo?.let { narrowingLabel(it, accounts) }
    // The button fills the slot the bar gives it, so its icon sits in the middle (UI_DESIGN.md 3.1).
    Box(propagateMinConstraints = true) {
        button(
            Modifier
                .semantics { if (narrowed != null) stateDescription = narrowed }
                .then(
                    if (narrowable) {
                        Modifier.pointerInput(entry) {
                            detectTapGestures(
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    open = true
                                },
                                onTap = { actions.onSelect(entry.item) },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        )
        if (item != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val everything =
                    if (item.network == NetworkId.INSTAGRAM && entry.narrowOptions.none { it is Narrowing.Account }) {
                        R.string.bar_show_both_folders
                    } else {
                        R.string.bar_show_all_accounts
                    }
                NarrowChoice(stringResource(everything), entry.narrowedTo == null) {
                    open = false
                    actions.onNarrow(item.network, null)
                    actions.onSelect(item)
                }
                entry.narrowOptions.forEach { option ->
                    NarrowChoice(narrowingLabel(option, accounts), entry.narrowedTo == option) {
                        open = false
                        actions.onNarrow(item.network, option)
                        actions.onSelect(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun NarrowChoice(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { RadioButton(selected = chosen, onClick = null) },
    )
}

@Composable
private fun narrowingLabel(
    narrowing: Narrowing,
    accounts: List<Account>,
): String =
    when (narrowing) {
        is Narrowing.Account -> {
            accounts.find { it.id == narrowing.id }?.displayName ?: narrowing.id.value
        }

        is Narrowing.Folder -> {
            stringResource(
                if (narrowing.folder ==
                    ChatFolder.GENERAL
                ) {
                    R.string.bar_general_only
                } else {
                    R.string.bar_primary_only
                },
            )
        }
    }

// Unread shows as a small dot, not a count (owner, Gate G3: the numbers were loud).
@Composable
internal fun BarIcon(entry: BarEntry) {
    BadgedBox(badge = { if (entry.badge > 0) Badge(Modifier.size(UNREAD_DOT)) }) {
        when (val item = entry.item) {
            null -> Icon(painterResource(UiR.drawable.ic_forum), null)
            InboxBarItem.Unread -> Icon(painterResource(UiR.drawable.ic_mark_chat_unread), null)
            is InboxBarItem.Network -> NetworkDot(item.network)
            is InboxBarItem.Space -> Icon(painterResource((entry.spaceIcon ?: SpaceIcon.SPACE).drawable()), null)
            InboxBarItem.LowPriority -> Icon(painterResource(UiR.drawable.ic_low_priority), null)
        }
    }
}

/** A network's mark: its short name on its accent colour. No brand logos are bundled. */
@Composable
fun NetworkDot(
    network: NetworkId,
    modifier: Modifier = Modifier,
) {
    val accent = PingMeTheme.networkColors.accent(network)
    Box(modifier.size(24.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            // Two letters: "GM" for Google Messages, the badge's start for the rest.
            badgeLabel(network).take(2),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color =
                org.pingme.core.ui.components
                    .readableOn(accent),
        )
    }
}

@Composable
fun barLabel(
    entry: BarEntry,
    accounts: List<Account>,
): String =
    when (val item = entry.item) {
        null -> {
            stringResource(R.string.bar_all)
        }

        InboxBarItem.Unread -> {
            stringResource(R.string.bar_unread)
        }

        is InboxBarItem.Network -> {
            (entry.narrowedTo as? Narrowing.Account)?.let { n -> accounts.find { it.id == n.id }?.displayName }
                ?: item.network.displayName
        }

        is InboxBarItem.Space -> {
            entry.spaceName.orEmpty()
        }

        InboxBarItem.LowPriority -> {
            stringResource(R.string.bar_low_priority)
        }
    }

/**
 * Picks the bar's four buttons (UI_DESIGN.md 10.4): All, Unread, each network you have, each
 * space, and Low priority, in the order ticked. Everything left over sits behind More.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBarSheet(
    current: List<InboxBarItem?>,
    accounts: List<Account>,
    spaces: List<Space>,
    onSave: (List<InboxBarItem?>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(current) }
    // All is one of the choices: on by default, and it can be taken off (UI_DESIGN.md 10.4).
    val options =
        listOf<InboxBarItem?>(null, InboxBarItem.Unread) +
            accounts.map { it.network }.distinct().map { InboxBarItem.Network(it) } +
            spaces.map { InboxBarItem.Space(it.id) } +
            InboxBarItem.LowPriority
    ModalBottomSheet(onDismissRequest = {
        onSave(picked)
        onDismiss()
    }) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.bar_edit_title),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(
                    if (picked.size >=
                        InboxBarConfig.MAX_BUTTONS
                    ) {
                        R.string.bar_edit_full
                    } else {
                        R.string.bar_edit_hint
                    },
                ),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { option ->
                val on = option in picked
                val space = (option as? InboxBarItem.Space)?.let { s -> spaces.find { it.id == s.id } }
                val entry =
                    BarEntry(
                        option,
                        0,
                        spaceName = space?.title,
                        spaceIcon = space?.icon,
                    )
                val full = !on && picked.size >= InboxBarConfig.MAX_BUTTONS
                // The last button stays: a bar with none would leave nothing to open.
                val last = on && picked.size == 1
                ListItem(
                    onClick = { picked = if (on) picked - option else ordered(picked + option) },
                    enabled = !full && !last,
                    leadingContent = { BarIcon(entry) },
                    trailingContent = { Checkbox(checked = on, onCheckedChange = null, enabled = !full && !last) },
                ) { Text(barLabel(entry, accounts)) }
            }
        }
    }
}

// All stays first; the rest keep the order they were picked in.
private fun ordered(picked: List<InboxBarItem?>) = picked.filter { it == null } + picked.filterNotNull()

/** The + menu (UI_DESIGN.md 3.1): New chat and New group. Scan QR was dropped by the owner. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val newLabel = stringResource(if (expanded) R.string.fab_close else R.string.fab_new)
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                modifier = Modifier.semantics { contentDescription = newLabel },
            ) {
                val icon = if (checkedProgress > HALF) UiR.drawable.ic_close else UiR.drawable.ic_add
                Icon(painterResource(icon), null, Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        listOf(
            Triple(R.string.fab_new_group, UiR.drawable.ic_group_add, onNewGroup),
            Triple(R.string.fab_new_chat, UiR.drawable.ic_chat, onNewChat),
        ).forEach { (label, icon, action) ->
            FloatingActionButtonMenuItem(
                onClick = {
                    onExpandedChange(false)
                    action()
                },
                text = { Text(stringResource(label)) },
                icon = { Icon(painterResource(icon), null) },
            )
        }
    }
}

private const val HALF = 0.5f

const val INBOX_BAR = "inbox-bar"

private val UNREAD_DOT = 7.dp
