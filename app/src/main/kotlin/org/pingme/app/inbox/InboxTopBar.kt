// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.Account
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.Space
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/** The lists the avatar menu opens (UI_DESIGN.md 3.1, 6.4, 10.4, 10.7). */
sealed interface ChatList {
    data object Archived : ChatList

    data object LowPriority : ChatList

    data object Requests : ChatList

    data object General : ChatList

    data class InSpace(
        val space: Space,
    ) : ChatList
}

/** Where the avatar menu leads. Settings, Notifications, and Accounts arrive with P2.6. */
class MenuActions(
    val onAppearance: () -> Unit,
    val onList: (ChatList) -> Unit,
    val onEditBar: () -> Unit,
    val onSettings: (() -> Unit)? = null,
    val onNotifications: (() -> Unit)? = null,
    val onAccounts: (() -> Unit)? = null,
)

/**
 * The compact top bar (BUILD_PLAN.md P2.3): small wordmark, status pill, search, and the
 * avatar that opens the account menu. Inset by the status bar and nothing more (3.1).
 */
@Composable
fun InboxTopBar(
    accounts: List<Account>,
    menu: MenuCounts,
    onStatus: () -> Unit,
    onSearch: () -> Unit,
    actions: MenuActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = PingMeTheme.chatTitle,
            fontWeight = FontWeight.ExtraBold,
        )
        StatusPill(accounts, onStatus)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) {
            Icon(painterResource(UiR.drawable.ic_search), stringResource(R.string.inbox_search))
        }
        AccountMenuButton(accounts, menu, actions)
    }
}

private enum class Health { OK, WORKING, ATTENTION, NONE }

private fun health(accounts: List<Account>): Pair<Health, Account?> {
    val attention = accounts.firstOrNull { it.state is ConnectionState.ActionNeeded }
    val working = accounts.firstOrNull { it.state is ConnectionState.Reconnecting }
    return when {
        accounts.isEmpty() -> Health.NONE to null
        attention != null -> Health.ATTENTION to attention
        working != null -> Health.WORKING to working
        else -> Health.OK to null
    }
}

/** Connected, "RCS reconnecting" with a pulsing dot, or "RCS needs attention" (UI_DESIGN.md 3.1). */
@Composable
private fun StatusPill(
    accounts: List<Account>,
    onClick: () -> Unit,
) {
    val (health, account) = health(accounts)
    val c = MaterialTheme.colorScheme
    val (container, content) =
        when (health) {
            Health.OK -> c.secondaryContainer to c.onSecondaryContainer
            Health.WORKING -> c.tertiaryContainer to c.onTertiaryContainer
            Health.ATTENTION -> c.errorContainer to c.onErrorContainer
            Health.NONE -> c.surfaceContainerHigh to c.onSurfaceVariant
        }
    val name = account?.displayName.orEmpty()
    val label =
        when (health) {
            Health.OK -> stringResource(R.string.status_connected)
            Health.WORKING -> stringResource(R.string.status_reconnecting, name)
            Health.ATTENTION -> stringResource(R.string.status_attention, name)
            Health.NONE -> stringResource(R.string.status_no_accounts)
        }
    Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PulsingDot(content, pulsing = health == Health.WORKING)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun PulsingDot(
    colour: Color,
    pulsing: Boolean,
) {
    val moving = pulsing && PingMeTheme.motion != MotionIntensity.OFF
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f,
        if (moving) PULSE else 1f,
        infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        Modifier
            .scale(pulse)
            .size(7.dp)
            .background(colour, CircleShape),
    )
}

/**
 * The slim chip under the bar, only while an account is not Connected: "RCS reconnecting"
 * or "RCS needs attention, tap to fix" (UI_DESIGN.md 3.1).
 */
@Composable
fun HealthChip(
    problems: List<Account>,
    onFix: (Account) -> Unit,
    modifier: Modifier = Modifier,
) {
    val worst = problems.firstOrNull() ?: return
    val attention = worst.state is ConnectionState.ActionNeeded
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = { onFix(worst) },
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = if (attention) c.errorContainer else c.tertiaryContainer,
        contentColor = if (attention) c.onErrorContainer else c.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (attention) UiR.drawable.ic_error else UiR.drawable.ic_sync),
                null,
                Modifier.size(18.dp),
            )
            Text(
                stringResource(
                    if (attention) R.string.health_attention else R.string.health_reconnecting,
                    worst.displayName,
                ),
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun AccountMenuButton(
    accounts: List<Account>,
    counts: MenuCounts,
    actions: MenuActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(UiR.drawable.ic_account_circle),
                stringResource(R.string.inbox_account_menu),
                Modifier.size(30.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AccountMenuContent(accounts, counts, actions) { open = false }
        }
    }
}

/** The account menu's lines (UI_DESIGN.md 3.1): who you are, where to go, the chat lists, and the bar. */
@Composable
private fun ColumnScope.AccountMenuContent(
    accounts: List<Account>,
    counts: MenuCounts,
    actions: MenuActions,
    close: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        val connected = accounts.count { it.state == ConnectionState.Connected }
        Text(
            pluralStringResource(R.plurals.inbox_networks_connected, connected, connected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()

    fun go(action: () -> Unit) {
        close()
        action()
    }
    MenuEntry(R.string.menu_settings, UiR.drawable.ic_settings, actions.onSettings?.let { { go(it) } })
    MenuEntry(R.string.menu_appearance, UiR.drawable.ic_palette, { go(actions.onAppearance) })
    MenuEntry(
        R.string.menu_notifications,
        UiR.drawable.ic_notifications,
        actions.onNotifications?.let { { go(it) } },
    )
    MenuEntry(R.string.menu_accounts, UiR.drawable.ic_person, actions.onAccounts?.let { { go(it) } })
    HorizontalDivider()
    MenuEntry(R.string.menu_archived, UiR.drawable.ic_archive, {
        go { actions.onList(ChatList.Archived) }
    }, counts.archived)
    MenuEntry(R.string.menu_low_priority, UiR.drawable.ic_low_priority, {
        go { actions.onList(ChatList.LowPriority) }
    }, counts.lowPriority)
    if (counts.hasFolders || counts.requests > 0) {
        MenuEntry(
            R.string.menu_requests,
            UiR.drawable.ic_mail,
            { go { actions.onList(ChatList.Requests) } },
            counts.requests,
        )
    }
    if (counts.hasFolders || counts.general > 0) {
        MenuEntry(
            R.string.menu_general,
            UiR.drawable.ic_inbox,
            { go { actions.onList(ChatList.General) } },
            counts.general,
        )
    }
    counts.spaces.forEach { (space, unread) ->
        DropdownMenuItem(
            text = { Text(space.title) },
            onClick = { go { actions.onList(ChatList.InSpace(space)) } },
            leadingIcon = { Icon(painterResource(space.icon.drawable()), null) },
            trailingIcon = { MenuCount(unread) },
        )
    }
    HorizontalDivider()
    MenuEntry(R.string.menu_edit_bar, UiR.drawable.ic_edit, { go(actions.onEditBar) })
}

/** A menu line; [onClick] null shows it greyed out until its screen exists. */
@Composable
private fun MenuEntry(
    label: Int,
    icon: Int,
    onClick: (() -> Unit)?,
    count: Int = 0,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        leadingIcon = { Icon(painterResource(icon), null) },
        trailingIcon = { MenuCount(count) },
    )
}

/** A menu row's unread total, shown only when there is one. */
@Composable
private fun MenuCount(count: Int) {
    if (count > 0) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PULSE = 1.6f
private const val PULSE_MS = 500
