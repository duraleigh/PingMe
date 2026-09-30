// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageKind
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.Transport
import org.pingme.core.store.LastMessage
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val previewNow = Instant.parse("2026-09-30T18:00:00Z")

private fun previewRow(
    name: String,
    network: NetworkId,
    text: String,
    ago: kotlin.time.Duration,
    unread: Int = 0,
    pin: Int? = null,
    muted: Boolean = false,
    folder: ChatFolder? = null,
    typing: Boolean = false,
) = ChatRow(
    Chat(
        ChatId(name),
        AccountId(network.name),
        ChatKind.DIRECT,
        name,
        emptyList(),
        unread,
        previewNow - ago,
        pin != null,
        pin,
        muted,
        null,
        false,
        false,
        false,
        folder,
        null,
        null,
        AvatarSource.Initials,
        null,
        null,
        name,
    ),
    network,
    LastMessage(text, MessageKind.TEXT, false, Transport.NETWORK, previewNow - ago, null),
    typing,
)

/** A believable inbox for previews: several networks, pins, unread, muted, typing. */
@Suppress("MagicNumber") // Sample unread counts.
fun previewInboxState(): InboxUiState {
    val accounts =
        listOf(NetworkId.GMESSAGES, NetworkId.WHATSAPP, NetworkId.TELEGRAM).map {
            Account(
                AccountId(it.name),
                it,
                it.displayName,
                0,
                ConnectionState.Connected,
                true,
                NotificationMode.NORMAL,
                it.name,
            )
        }
    return InboxUiState(
        loading = false,
        pinned =
            listOf(
                previewRow(
                    "Sam Ortiz",
                    NetworkId.GMESSAGES,
                    "Are you still coming tonight?",
                    1.minutes,
                    unread = 2,
                    pin = 0,
                ),
                previewRow("Mom", NetworkId.WHATSAPP, "Call me", 3.hours, pin = 1, typing = true),
                previewRow("Work", NetworkId.TELEGRAM, "Standup moved", 5.hours, pin = 2),
            ),
        rows =
            listOf(
                previewRow("Design team", NetworkId.WHATSAPP, "Priya: new mocks are up", 40.minutes, unread = 3),
                previewRow("Dad", NetworkId.GMESSAGES, "Call me when you can", 26.hours),
                previewRow(
                    "Jordan Blake",
                    NetworkId.INSTAGRAM,
                    "Loved your post",
                    2.hours,
                    unread = 1,
                    folder = ChatFolder.GENERAL,
                ),
                previewRow("Gym crew", NetworkId.TELEGRAM, "I'm in", 50.hours, muted = true),
            ),
        bar =
            listOf(
                BarEntry(null, 6),
                BarEntry(InboxBarItem.Unread, 6),
                BarEntry(InboxBarItem.Network(NetworkId.GMESSAGES), 2),
                BarEntry(InboxBarItem.Network(NetworkId.WHATSAPP), 3),
                BarEntry(InboxBarItem.Network(NetworkId.TELEGRAM), 0),
            ),
        accounts = accounts,
        now = previewNow,
    )
}

private val previewNavigation =
    InboxNavigation({}, {}, {}, {}, {}, MenuActions({}, {}, {}))

private val previewCallbacks =
    InboxCallbacks({ _, _ -> }, { _, _ -> }, {}, BarActions({}, { _, _ -> }), {})

@Preview(name = "Inbox, light", widthDp = 411, heightDp = 891)
@Composable
private fun InboxLightPreview() {
    PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
        InboxScreen(previewInboxState(), previewCallbacks, previewNavigation)
    }
}

@Preview(name = "Inbox, dark", widthDp = 411, heightDp = 891)
@Composable
private fun InboxDarkPreview() {
    PingMeTheme(
        Appearance(mode = ThemeMode.DARK),
    ) { InboxScreen(previewInboxState(), previewCallbacks, previewNavigation) }
}

@Preview(name = "Inbox, tablet", widthDp = 900, heightDp = 700)
@Composable
private fun InboxWidePreview() {
    PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
        InboxScreen(previewInboxState(), previewCallbacks, previewNavigation)
    }
}
