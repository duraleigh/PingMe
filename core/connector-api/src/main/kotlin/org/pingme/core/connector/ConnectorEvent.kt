// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.Reaction
import org.pingme.core.model.Space
import kotlin.time.Instant

/** Everything a live connection reports (DESIGN.md 6.2). Every event names its account. */
sealed interface ConnectorEvent {
    val accountId: AccountId

    data class NewMessage(
        override val accountId: AccountId,
        val message: MessageSnapshot,
    ) : ConnectorEvent

    /** An edit, a status change, or a delete for everyone. */
    data class MessageUpdated(
        override val accountId: AccountId,
        val message: MessageSnapshot,
    ) : ConnectorEvent

    /**
     * A message gone from the network's own copy, such as one deleted on the phone in
     * Google Messages (UI_DESIGN.md 5.3 keeps both copies matching).
     */
    data class MessageRemoved(
        override val accountId: AccountId,
        val chatId: ChatId,
        val messageId: MessageId,
    ) : ConnectorEvent

    data class ReactionChanged(
        override val accountId: AccountId,
        val messageId: MessageId,
        val reaction: Reaction,
        val removed: Boolean,
    ) : ConnectorEvent

    /** [reader] read everything up to [upTo]; null reader means "the other side". */
    data class ReadReceipt(
        override val accountId: AccountId,
        val chatId: ChatId,
        val upTo: MessageId,
        val reader: PersonId?,
    ) : ConnectorEvent

    data class Typing(
        override val accountId: AccountId,
        val chatId: ChatId,
        val personId: PersonId,
        val typing: Boolean,
    ) : ConnectorEvent

    data class ChatUpdated(
        override val accountId: AccountId,
        val chat: ChatSnapshot,
    ) : ConnectorEvent

    data class ChatRemoved(
        override val accountId: AccountId,
        val chatId: ChatId,
    ) : ConnectorEvent

    /**
     * People the network knows beyond the chats shown, such as the phone's WhatsApp
     * contacts: they fill the people search when starting a chat (owner, Gate G3).
     */
    data class PeopleUpdated(
        override val accountId: AccountId,
        val people: List<Person>,
    ) : ConnectorEvent

    data class State(
        override val accountId: AccountId,
        val state: ConnectionState,
    ) : ConnectorEvent

    /** A message deleted for everyone on the network: its text and files go, a note stays (UI_DESIGN.md 5.3). */
    data class MessageRevoked(
        override val accountId: AccountId,
        val chatId: ChatId,
        val messageId: MessageId,
    ) : ConnectorEvent

    /** A message's text changed on the network (WhatsApp edits). */
    data class MessageEdited(
        override val accountId: AccountId,
        val chatId: ChatId,
        val messageId: MessageId,
        val body: String,
        val editedAt: Instant,
    ) : ConnectorEvent

    /** A delivery or read tick for one of your messages, without the whole message again. */
    data class StatusChanged(
        override val accountId: AccountId,
        val messageId: MessageId,
        val status: MessageStatus,
    ) : ConnectorEvent

    /** A network grouping of chats, such as a WhatsApp community, as it stands now (UI_DESIGN.md 10.4). */
    data class SpaceUpdated(
        override val accountId: AccountId,
        val space: Space,
    ) : ConnectorEvent

    /** Older messages arriving in bulk (history sync). [complete] when nothing older is left. */
    data class HistoryBatch(
        override val accountId: AccountId,
        val chatId: ChatId,
        val messages: List<MessageSnapshot>,
        val complete: Boolean,
    ) : ConnectorEvent
}
