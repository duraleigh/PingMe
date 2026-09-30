// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.person
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import kotlin.time.Instant

/**
 * One demo account's side of the fake network: its people, chats, and messages, as the
 * "server" holds them. All changes go through the connector; tests and UI tests reach in
 * through DemoConnector's simulate functions.
 */
internal class DemoWorld(
    val accountId: AccountId,
) {
    val me = Person(accountId.person(ME), accountId, "You", null, ME, null, null)
    val people = mutableMapOf<PersonId, Person>()
    val chats = linkedMapOf<ChatId, ChatState>()
    val messages = mutableMapOf<ChatId, MutableList<Message>>()
    val deletedForEveryone = mutableSetOf<MessageId>()

    /** Events for the open session, if any. Buffered so bursts are never dropped. */
    val events = MutableSharedFlow<ConnectorEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** True from connect until disconnect. */
    val open = MutableStateFlow(false)

    data class ChatState(
        val id: ChatId,
        val kind: ChatKind,
        val title: String,
        val participants: List<PersonId>,
        var unreadCount: Int,
        var folder: ChatFolder?,
        val remoteId: String,
    )

    fun person(id: PersonId) = if (id == me.id) me else people.getValue(id)

    fun history(chatId: ChatId): List<Message> = messages[chatId].orEmpty().sortedByDescending { it.sentAt }

    fun lastActivity(chatId: ChatId): Instant? = messages[chatId]?.maxOfOrNull { it.sentAt }

    fun snapshot(chat: ChatState) =
        ChatSnapshot(
            id = chat.id,
            accountId = accountId,
            kind = chat.kind,
            title = chat.title,
            participants = chat.participants.map(::person),
            unreadCount = chat.unreadCount,
            lastActivityAt = lastActivity(chat.id) ?: Instant.DISTANT_PAST,
            folder = chat.folder,
            spaceId = null,
            networkRemoteId = chat.remoteId,
        )

    fun snapshot(message: Message) = MessageSnapshot(message, if (message.isOutgoing) me else person(message.senderId))

    fun findMessage(id: MessageId): Message? =
        messages.values.firstNotNullOfOrNull { list -> list.find { it.id == id } }

    fun replaceMessage(updated: Message) {
        val list = messages.getValue(updated.chatId)
        list[list.indexOfFirst { it.id == updated.id }] = updated
    }

    companion object {
        const val ME = "me"
        private const val EVENT_BUFFER = 256
    }
}
