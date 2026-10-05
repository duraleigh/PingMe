// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.pingme.core.model.Attachment
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.NetworkId
import org.pingme.core.model.PersonId
import org.pingme.core.service.IncomingReaction

// What the chat screen's parts can ask for, gathered so each part takes one argument.

/** What the header's buttons do. Null ones are not built yet and show greyed out. */
class HeaderActions(
    val onBack: (() -> Unit)?,
    val onCall: (video: Boolean) -> Unit,
    val onDetails: (() -> Unit)? = null,
    val onSearch: (() -> Unit)? = null,
    /** A merged chat's header dropdown: one member's account, or null for all (owner, Phase 7). */
    val onFilter: ((org.pingme.core.model.AccountId?) -> Unit)? = null,
)

/** Everything the chat screen can ask its view model for. */
class ChatScreenActions(
    val header: HeaderActions,
    val onSend: (String) -> Unit,
    val onReply: (Message?) -> Unit,
    val onRetry: (Message) -> Unit,
    val onUnpin: (Message) -> Unit,
    val onLoadOlder: () -> Unit,
    val onNeed: (Attachment) -> Unit,
    val onTyping: (String) -> Unit,
    /** The composer chip in a merged chat: send through this member (UI_DESIGN.md 10.15). */
    val onSendVia: (ChatId) -> Unit = {},
    /** Press and hold, double tap, delete, select; null in previews. */
    val menu: MessageMenu? = null,
    val onRememberEmoji: (String) -> Unit = {},
    val incoming: Flow<IncomingReaction> = emptyFlow(),
    val forwardTargets: List<Chat> = emptyList(),
    val composer: ComposerHooks = ComposerHooks(),
    val uploads: Map<org.pingme.core.model.MessageId, Float> = emptyMap(),
    /** Plays voice notes; null in previews. */
    val player: org.pingme.app.chat.voice.VoicePlayer? = null,
    val search: org.pingme.app.chat.search.SearchHooks =
        org.pingme.app.chat.search
            .SearchHooks(),
    /** Voice-note transcripts; null when they are off, and in previews. */
    val transcripts: org.pingme.app.chat.voice.Transcripts? = null,
    /** The app's settings, for what the chat shows and plays (UI_DESIGN.md 10). */
    val settings: org.pingme.core.model.AppSettings =
        org.pingme.core.model
            .AppSettings(),
    /** Strips tracking from a shown link; identity when the setting is off. */
    val cleanLink: (String) -> String = { it },
)

/** The composer's extras: attachments and other ways to send. Null ones are not offered. */
class ComposerHooks(
    val outbox: org.pingme.app.chat.attach.Outbox? = null,
    val onProblem: (org.pingme.app.chat.attach.AttachProblem) -> Unit = {},
    /** Voice notes; null where the network cannot take them. */
    val voice: org.pingme.app.chat.voice.VoiceNotes? = null,
    val onVoiceTooShort: () -> Unit = {},
    /** The GIF button's picker; null where the network cannot take GIFs. */
    val gifs: org.pingme.app.chat.gif.GifSearch? = null,
    val gifPicks: org.pingme.app.chat.gif.GifPicks? = null,
    /** Send later: the text and when (UI_DESIGN.md 10.13). */
    val onSchedule: ((String, kotlin.time.Instant) -> Unit)? = null,
    /** The network the next message goes on, named in the box (owner, 2026-10-03). */
    val network: NetworkId? = null,
    /** A merged chat's members, offered by the badge in the box; one or none otherwise. */
    val members: List<MemberChip> = emptyList(),
    val sendVia: ChatId? = null,
    val onSendVia: (ChatId) -> Unit = {},
    /** The sending account is connected; false red-lines the badge and says so in the box. */
    val connected: Boolean = true,
)

/** What a message row needs besides the message. */
class RowContext(
    val network: NetworkId,
    val kind: ChatKind,
    /** A merged chat: each message's network, for its colour and the badge beside the ticks. */
    val networkOf: Map<ChatId, NetworkId> = emptyMap(),
    val names: Map<PersonId, String>,
    val onRetry: (Message) -> Unit,
    val onNeed: (Attachment) -> Unit,
    val onQuote: (Message) -> Unit,
    /** How far each sending message's media has got. */
    val uploads: Map<org.pingme.core.model.MessageId, Float> = emptyMap(),
    val player: org.pingme.app.chat.voice.VoicePlayer? = null,
    /** Received GIFs play by themselves; off, a tap plays one (UI_DESIGN.md 5.5). */
    val gifsAutoplay: Boolean = true,
    val transcripts: org.pingme.app.chat.voice.Transcripts? = null,
    /** Incoming links shown cleaned when "Clean links I receive" is on (UI_DESIGN.md 10.11). */
    val cleanLink: (String) -> String = { it },
    /** The user's own person id here, so their own reactions read "You". */
    val me: PersonId? = null,
) {
    /** The network a message went over: its own chat's in a merged chat (UI_DESIGN.md 10.15). */
    fun networkFor(message: Message): NetworkId = networkOf[message.chatId] ?: network

    /** Whether bubbles name their network beside the time and ticks (only in a merged chat). */
    val marksNetwork: Boolean get() = networkOf.size > 1
}

/** One line of the action card. [enabled] false shows it greyed with [reason] (UI_DESIGN.md 1). */
data class MessageAction(
    @param:androidx.annotation.StringRes val label: Int,
    @param:androidx.annotation.DrawableRes val icon: Int,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)
