// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.pingme.core.connector.Connector
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.accountId
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Quote
import org.pingme.core.model.Transport
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.PinnedMessageRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * What the chat screen does to messages (UI_DESIGN.md 3.2, 5.1, 5.2): send and reply with a
 * pending bubble that becomes the network's copy, retry, pin, typing, and loading older
 * history. Reactions and deletes come with the long-press actions.
 */
@Singleton
class MessageActions
    @Inject
    constructor(
        private val chats: ChatRepository,
        private val messages: MessageRepository,
        private val pins: PinnedMessageRepository,
        private val accounts: AccountRepository,
        private val registry: ConnectorRegistry,
        private val applier: EventApplier,
        private val clock: Clock,
    ) {
        /**
         * Sends [text] to [chatId], as a reply to [replyTo] when given. The message shows at once
         * as Sending, then turns into what the network accepted, or Failed with the reason.
         */
        suspend fun send(
            chatId: ChatId,
            text: String,
            replyTo: Message? = null,
            replyToName: String? = null,
            forceSms: Boolean = false,
        ): Message {
            val network = networkOf(chatId)
            val pending = pendingMessage(chatId, text, replyTo, replyToName, network, forceSms)
            messages.upsert(pending)
            chats.update(chatId) { it.copy(lastActivityAt = maxOf(it.lastActivityAt, pending.sentAt), unreadCount = 0) }
            return deliver(pending, forceSms)
        }

        /** Sends a Failed message again, with the same text and reply. */
        suspend fun retry(failed: Message): Message {
            val again = failed.copy(status = MessageStatus.Sending, sentAt = clock.now(), receivedAt = clock.now())
            messages.upsert(again)
            return deliver(again, forceSms = failed.transport == Transport.SMS)
        }

        suspend fun pin(message: Message) = pins.pin(message, clock.now())

        suspend fun unpin(id: MessageId) = pins.unpin(id)

        /** Tells the other side you are typing, where the network shows that; silent otherwise. */
        suspend fun setTyping(
            chatId: ChatId,
            typing: Boolean,
        ) {
            val connector = connectorFor(chatId) ?: return
            if (!connector.capabilities.typing) return
            quietly("typing") { connector.setTyping(chatId, typing) }
        }

        /**
         * Fetches up to [count] messages older than the oldest one stored. Returns false when
         * the network has nothing older, so the screen stops asking.
         */
        suspend fun loadOlder(
            chatId: ChatId,
            count: Int = PAGE,
        ): Boolean {
            val connector = connectorFor(chatId) ?: return false
            val older =
                quietly("history") { connector.syncMessages(chatId, messages.oldest(chatId), count) } ?: return false
            applier.apply(ConnectorEvent.HistoryBatch(chatId.accountId, chatId, older, complete = older.size < count))
            return older.size >= count
        }

        private suspend fun deliver(
            pending: Message,
            forceSms: Boolean,
        ): Message {
            val connector = connectorFor(pending.chatId)
            val draft = OutgoingMessage(pending.id, pending.body, emptyList(), pending.replyTo, pending.quote, forceSms)
            val result =
                if (connector == null) {
                    SendResult.Failed("This network is not connected", retryable = true)
                } else {
                    quietly("send") { connector.send(pending.chatId, draft) }
                        ?: SendResult.Failed("Could not reach the network", retryable = true)
                }
            return when (result) {
                is SendResult.Sent -> {
                    messages.delete(pending.id)
                    applier.apply(ConnectorEvent.NewMessage(pending.chatId.accountId, result.message))
                    result.message.message
                }

                is SendResult.Failed -> {
                    pending.copy(status = MessageStatus.Failed(result.reason)).also { messages.upsert(it) }
                }
            }
        }

        private suspend fun pendingMessage(
            chatId: ChatId,
            text: String,
            replyTo: Message?,
            replyToName: String?,
            network: NetworkId?,
            forceSms: Boolean,
        ): Message {
            val now = clock.now()
            val local = "pending-${UUID.randomUUID()}"
            return Message(
                id = chatId.accountId.message(local),
                chatId = chatId,
                senderId = messages.selfIn(chatId) ?: chatId.accountId.person(SELF),
                sentAt = now,
                receivedAt = now,
                body = text,
                kind = MessageKind.TEXT,
                attachments = emptyList(),
                replyTo = replyTo?.id,
                quote = replyTo?.let { Quote(replyToName.orEmpty(), it.body.orEmpty()) },
                editedAt = null,
                deletedForEveryone = false,
                status = MessageStatus.Sending,
                reactions = emptyList(),
                transport = transportFor(network, forceSms),
                networkRemoteId = local,
                linkPreview = null,
                isOutgoing = true,
            )
        }

        private fun transportFor(
            network: NetworkId?,
            forceSms: Boolean,
        ) = when {
            network == NetworkId.SMS || (network == NetworkId.GMESSAGES && forceSms) -> Transport.SMS
            network == NetworkId.GMESSAGES -> Transport.RCS
            else -> Transport.NETWORK
        }

        private suspend fun networkOf(chatId: ChatId) = accounts.get(chatId.accountId)?.network

        private suspend fun connectorFor(chatId: ChatId): Connector? = networkOf(chatId)?.let { registry[it] }

        /** Runs a network call; a failure is logged on the phone and reads as "no result". */
        private suspend fun <T> quietly(
            what: String,
            call: suspend () -> T,
        ): T? =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedCapabilityException) {
                Log.i(TAG, "$what is not available here", e)
                null
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Offline or disconnected; the screen shows the outcome (a failed bubble, no older history).
                Log.w(TAG, "$what failed", e)
                null
            }

        companion object {
            const val PAGE = 50
            private const val SELF = "me"
            private const val TAG = "PingMeMessages"
        }
    }
