// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.pingme.core.connector.Connector
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.OutgoingAttachment
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
import org.pingme.core.model.Reaction
import org.pingme.core.model.ReactionRule
import org.pingme.core.model.ScheduledSend
import org.pingme.core.model.Transport
import org.pingme.core.service.work.SendAlarm
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.PinnedMessageRepository
import org.pingme.core.store.ScheduledSendRepository
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the chat screen does to messages (UI_DESIGN.md 3.2, 5.1, 5.2): send and reply with a
 * pending bubble that becomes the network's copy, retry, pin, typing, and loading older
 * history. Reactions and deletes come with the long-press actions.
 */
@Singleton
// One function per thing the chat screen can do to a message; the list reads as one.
@Suppress("TooManyFunctions")
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
        private val scheduled: ScheduledSendRepository,
        private val alarm: SendAlarm,
        private val settings: org.pingme.core.store.SettingsRepository,
    ) {
        private val uploads = MutableStateFlow<Map<MessageId, Float>>(emptyMap())

        private val sent = SentCopies(messages)

        /** How far each sending message's media has got, from 0 to 1 (UI_DESIGN.md 5.8). */
        val progress: StateFlow<Map<MessageId, Float>> = uploads.asStateFlow()

        /**
         * The id a sent message first showed under, while it was "sending", or [id] itself. The
         * chat keeps drawing the bubble under that first id, so the network's copy slides into
         * the same bubble instead of a second one fading in over the first (owner, Gate G2).
         */
        fun shownAs(id: MessageId): MessageId = sent.originalOf(id) ?: id

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
            attachments: List<OutgoingAttachment> = emptyList(),
        ): Message {
            val network = networkOf(chatId)
            val pending =
                pendingMessage(chatId, text, replyTo, replyToName, network, forceSms).let { message ->
                    val files = attachments.mapIndexed { i, a -> a.asAttachment(message.id, i) }
                    message.copy(
                        body = text.ifBlank { null },
                        attachments = files,
                        kind = files.firstOrNull()?.kind?.let(::kindOf) ?: MessageKind.TEXT,
                    )
                }
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

        suspend fun pin(message: Message) = pins.pin(sent.current(message), clock.now())

        suspend fun unpin(id: MessageId) = pins.unpin(id)

        /**
         * Reacts to [message] with [emoji], or takes your reaction away when [remove] (UI_DESIGN.md
         * 5.4). One reaction each: a new one replaces yours. Throws, with a reason to show, when
         * the network does not allow it.
         */
        suspend fun react(
            held: Message,
            emoji: String,
            remove: Boolean = false,
        ) {
            val message = sent.current(held)
            val connector =
                connectorFor(message.chatId) ?: throw UnsupportedCapabilityException("This network is not connected")
            connector.react(message.id, emoji, remove)
            if (connector.capabilities.reactions is ReactionRule.TextFallback) return
            val me = messages.selfIn(message.chatId) ?: message.chatId.accountId.person(SELF)
            message.reactions.filter { it.senderId == me }.forEach { messages.removeReaction(message.id, me, it.emoji) }
            if (!remove) messages.addReaction(message.id, Reaction(emoji, me, clock.now()))
        }

        /**
         * Delete for me (UI_DESIGN.md 5.3): gone from this phone. The network is told too, so a
         * paired phone's copy matches where it keeps one (Google Messages); others ignore it.
         */
        suspend fun deleteForMe(targets: List<Message>) {
            targets.map { sent.current(it) }.forEach { message ->
                messages.delete(message.id)
                connectorFor(message.chatId)?.let { connector ->
                    quietly("delete") { connector.delete(message.id, forEveryone = false) }
                }
            }
        }

        /** Delete for everyone, within the network's time limit. Throws with the reason when it cannot. */
        suspend fun deleteForEveryone(held: Message) {
            val message = sent.current(held)
            val connector =
                connectorFor(message.chatId) ?: throw UnsupportedCapabilityException("This network is not connected")
            connector.delete(message.id, forEveryone = true)
            messages.upsert(
                message.copy(
                    body = null,
                    kind = MessageKind.DELETED,
                    attachments = emptyList(),
                    deletedForEveryone = true,
                ),
            )
        }

        /** Changes the text of one of your messages. Throws with the reason when the network says no. */
        suspend fun edit(
            held: Message,
            text: String,
        ) {
            val message = sent.current(held)
            // A message still waiting to go is changed on the phone only.
            val waiting = message.status as? MessageStatus.Scheduled
            if (waiting != null) {
                schedule(message.chatId, text, waiting.at, replacing = message)
                return
            }
            val connector =
                connectorFor(message.chatId) ?: throw UnsupportedCapabilityException("This network is not connected")
            connector.edit(message.id, text)
            messages.upsert(message.copy(body = text, editedAt = clock.now()))
        }

        /** Sends a copy of [message], its text and its files, to [to] (UI_DESIGN.md 3.3). */
        suspend fun forward(
            message: Message,
            to: ChatId,
        ): Message? {
            val connector = connectorFor(message.chatId)
            // Files not yet on the phone are fetched first; one that cannot be fetched is left out.
            val files =
                message.attachments.mapNotNull { file ->
                    val local = file.localPath?.let(::File)?.takeIf { it.exists() }
                    val path = local?.path ?: quietly("download") { connector?.downloadAttachment(file)?.path }
                    path?.let { file.copy(localPath = it).asOutgoing() }
                }
            val text = message.body.orEmpty()
            if (text.isBlank() && files.isEmpty()) return null
            return send(to, text, attachments = files)
        }

        /**
         * Keeps a message to send at [at] (UI_DESIGN.md 10.13): it shows as a pending bubble
         * with a clock until then. [replacing] changes one already scheduled.
         */
        suspend fun schedule(
            chatId: ChatId,
            text: String,
            at: Instant,
            replyTo: Message? = null,
            replyToName: String? = null,
            attachments: List<OutgoingAttachment> = emptyList(),
            replacing: Message? = null,
        ): Message {
            val base =
                replacing ?: pendingMessage(chatId, text, replyTo, replyToName, networkOf(chatId), forceSms = false)
            val files = replacing?.attachments ?: attachments.mapIndexed { i, a -> a.asAttachment(base.id, i) }
            val message =
                base.copy(
                    body = text.ifBlank { null },
                    attachments = files,
                    kind = files.firstOrNull()?.kind?.let(::kindOf) ?: MessageKind.TEXT,
                    status = MessageStatus.Scheduled(at),
                    sentAt = at,
                    receivedAt = at,
                )
            val draft =
                OutgoingMessage(
                    message.id,
                    message.body,
                    files.mapNotNull { it.asOutgoing() },
                    message.replyTo,
                    message.quote,
                    false,
                )
            messages.upsert(message)
            scheduled.upsert(
                ScheduledSend(
                    message.id,
                    at,
                    chatId.accountId,
                    chatId,
                    Json.encodeToString(OutgoingMessage.serializer(), draft),
                    0,
                ),
            )
            alarm.arm()
            return message
        }

        /** Drops a scheduled message before it goes. */
        suspend fun cancelScheduled(message: Message) {
            scheduled.delete(message.id)
            messages.delete(message.id)
            alarm.arm()
        }

        /** Sends a scheduled message straight away. */
        suspend fun sendNow(message: Message): Message {
            scheduled.delete(message.id)
            val now = message.copy(status = MessageStatus.Sending, sentAt = clock.now(), receivedAt = clock.now())
            messages.upsert(now)
            alarm.arm()
            return deliver(now, forceSms = false)
        }

        /** Tells the other side you are typing, where the network shows that; silent otherwise. */
        suspend fun setTyping(
            chatId: ChatId,
            typing: Boolean,
        ) {
            val connector = connectorFor(chatId) ?: return
            if (!connector.capabilities.typing) return
            // "Show when I am typing" off: no typing events go out (UI_DESIGN.md 10.3).
            val network = networkOf(chatId) ?: return
            if (!settings.app
                    .first()
                    .privacy
                    .sendsTyping(network)
            ) {
                return
            }
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
            val files = pending.attachments.mapNotNull { it.asOutgoing() }
            val draft = OutgoingMessage(pending.id, pending.body, files, pending.replyTo, pending.quote, forceSms)
            val result =
                if (connector == null) {
                    SendResult.Failed("This network is not connected", retryable = true)
                } else {
                    val report: (Float) -> Unit = { done -> uploads.update { it + (pending.id to done) } }
                    try {
                        quietly("send") { connector.send(pending.chatId, draft, report) }
                    } finally {
                        uploads.update { it - pending.id }
                    } ?: SendResult.Failed("Could not reach the network", retryable = true)
                }
            return when (result) {
                is SendResult.Sent -> {
                    sent.replaced(pending.id, result.message.message.id)
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

/**
 * Each "sending" message and the network's message that replaced it. A message held while it
 * showed as "sending" may since have been sent under the network's ID; an action on it then
 * applies to the sent message.
 */
internal class SentCopies(
    private val messages: MessageRepository,
) {
    private val sentAs = java.util.concurrent.ConcurrentHashMap<MessageId, MessageId>()
    private val sendingAs = java.util.concurrent.ConcurrentHashMap<MessageId, MessageId>()

    fun replaced(
        sending: MessageId,
        sent: MessageId,
    ) {
        sentAs[sending] = sent
        // A retry sends the same pending bubble again: the newest copy keeps the first id.
        sendingAs[sent] = sendingAs[sending] ?: sending
    }

    /** The "sending" id that [sent] replaced, if it was ever a pending bubble. */
    fun originalOf(sent: MessageId): MessageId? = sendingAs[sent]

    /** [message] as it is now. */
    suspend fun current(message: Message): Message = sentAs[message.id]?.let { messages.get(it) } ?: message
}
