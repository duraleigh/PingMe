// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.accountId
import org.pingme.core.connector.attachment
import org.pingme.core.connector.message
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.PersonId
import org.pingme.core.model.Reaction
import org.pingme.core.model.ReactionRule
import org.pingme.core.model.Transport
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The demo network's "server": each account's world, the open sessions, and the scripted
 * people who message, type, react, and read (BUILD_PLAN.md P1.5).
 */
internal class DemoNetwork(
    private val controls: DemoControls,
    private val clock: Clock,
) {
    private val worlds = ConcurrentHashMap<AccountId, DemoWorld>()
    private val sessions = ConcurrentHashMap<AccountId, Channel<ConnectorEvent>>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = Random(RANDOM_SEED)

    private val capabilities get() = controls.settings.value.capabilities

    fun world(accountId: AccountId): DemoWorld =
        worlds.getOrPut(accountId) { DemoWorld(accountId).also { DemoSeed.populate(it, clock.now()) } }

    fun knownAccounts(): Set<AccountId> = worlds.keys.toSet()

    /** Opens a session. Its channel exists from now, so nothing sent before collection is lost. */
    fun open(accountId: AccountId): Channel<ConnectorEvent> {
        val session = Channel<ConnectorEvent>(Channel.UNLIMITED)
        sessions[accountId] = session
        world(accountId).open.value = true
        return session
    }

    fun close(accountId: AccountId) {
        worlds[accountId]?.open?.value = false
        sessions.remove(accountId)?.close()
    }

    fun emit(
        world: DemoWorld,
        event: ConnectorEvent,
    ) {
        sessions[world.accountId]?.trySend(event)
    }

    /** Scripted people message now and then while live activity is on. */
    suspend fun liveActivity(world: DemoWorld) {
        while (true) {
            // Waits, without waking the phone, until live activity is on.
            val settings = controls.settings.first { it.liveActivity }
            delay(settings.activityInterval * (JITTER_MIN + random.nextDouble()))
            // It may have been turned off while waiting.
            if (!controls.settings.value.liveActivity) continue
            val chat =
                synchronized(world) {
                    world.chats.values
                        .filter { it.folder != ChatFolder.REQUESTS }
                        .randomOrNull(random)
                }
            chat?.let { speak(world, it.id, it.participants.random(random), DemoSeed.chatter.random(random)) }
        }
    }

    /** After you send: delivered, read, and sometimes a reaction or a reply. */
    fun followUp(
        world: DemoWorld,
        sent: Message,
    ) {
        if (!controls.settings.value.liveActivity) return
        background.launch {
            delay(1.seconds)
            update(world, sent.id) { it.copy(status = MessageStatus.Delivered) }
            if (!capabilities.readReceipts) return@launch
            delay(2.seconds)
            update(world, sent.id) { it.copy(status = MessageStatus.Read) }
            emit(world, ConnectorEvent.ReadReceipt(world.accountId, sent.chatId, sent.id, reader = null))
            val chat = world.chats[sent.chatId] ?: return@launch
            when (FollowUp.entries.random(random)) {
                FollowUp.REACT -> {
                    if (capabilities.reactions != ReactionRule.TextFallback) {
                        react(sent.id, REACTIONS.random(random))
                    }
                }

                FollowUp.REPLY -> {
                    speak(world, chat.id, chat.participants.random(random), DemoSeed.replies.random(random))
                }

                FollowUp.NOTHING -> {
                    // Sometimes nobody answers, like real life.
                }
            }
        }
    }

    /** [from] types for a moment (where typing is on), then says [text]. */
    suspend fun speak(
        world: DemoWorld,
        chatId: ChatId,
        from: PersonId,
        text: String,
    ) {
        if (capabilities.typing) {
            emit(world, ConnectorEvent.Typing(world.accountId, chatId, from, typing = true))
            delay(controls.settings.value.typingTime)
        }
        val message = addIncoming(world, chatId, from, text)
        emit(world, ConnectorEvent.NewMessage(world.accountId, world.snapshot(message)))
    }

    /** Someone in the chat reacts to [messageId]. */
    fun react(
        messageId: MessageId,
        emoji: String,
    ) {
        val world = world(messageId.accountId)
        val reaction =
            synchronized(world) {
                val message = world.findMessage(messageId) ?: return
                val from =
                    world.chats
                        .getValue(message.chatId)
                        .participants
                        .first()
                Reaction(emoji, from, clock.now()).also {
                    world.replaceMessage(
                        message.copy(
                            reactions =
                                message.reactions + it,
                        ),
                    )
                }
            }
        emit(world, ConnectorEvent.ReactionChanged(world.accountId, messageId, reaction, removed = false))
    }

    fun update(
        world: DemoWorld,
        id: MessageId,
        change: (Message) -> Message,
    ) {
        val updated = synchronized(world) { world.findMessage(id)?.let(change)?.also(world::replaceMessage) } ?: return
        emit(world, ConnectorEvent.MessageUpdated(world.accountId, world.snapshot(updated)))
    }

    fun addIncoming(
        world: DemoWorld,
        chatId: ChatId,
        from: PersonId,
        text: String,
    ): Message =
        synchronized(world) {
            val message = baseMessage(world, chatId, "in-${UUID.randomUUID()}", from, text, outgoing = false)
            world.messages.getOrPut(chatId) { mutableListOf() } += message
            world.chats[chatId]?.let { it.unreadCount++ }
            message
        }

    fun addOutgoing(
        world: DemoWorld,
        chatId: ChatId,
        text: String?,
        draft: OutgoingMessage?,
    ): Message =
        synchronized(world) {
            val remote = "out-${UUID.randomUUID()}"
            val attachments =
                draft?.attachments.orEmpty().mapIndexed {
                    i,
                    a,
                    ->
                    outgoingAttachment(world, "$remote-$i", a)
                }
            val message =
                baseMessage(world, chatId, remote, world.me.id, text, outgoing = true).copy(
                    attachments = attachments,
                    kind = attachments.firstOrNull()?.kind?.let(::messageKindFor) ?: MessageKind.TEXT,
                    replyTo = draft?.replyTo?.let(world::findMessage)?.id,
                    quote = draft?.quote,
                )
            world.messages.getOrPut(chatId) { mutableListOf() } += message
            world.chats[chatId]?.unreadCount = 0
            message
        }

    private fun outgoingAttachment(
        world: DemoWorld,
        remote: String,
        draft: org.pingme.core.connector.OutgoingAttachment,
    ) = Attachment(
        id = world.accountId.attachment(remote),
        kind = draft.kind,
        mimeType = draft.mimeType,
        fileName = draft.fileName,
        sizeBytes = File(draft.localPath).length(),
        localPath = draft.localPath,
        remoteRef = null,
        durationMs = draft.durationMs,
        width = null,
        height = null,
        isEphemeral = false,
        savedAt = null,
    )

    private fun baseMessage(
        world: DemoWorld,
        chatId: ChatId,
        remote: String,
        sender: PersonId,
        text: String?,
        outgoing: Boolean,
    ): Message {
        val now = clock.now()
        return Message(
            id = world.accountId.message(remote),
            chatId = chatId,
            senderId = sender,
            sentAt = now,
            receivedAt = now,
            body = text,
            kind = MessageKind.TEXT,
            attachments = emptyList(),
            replyTo = null,
            quote = null,
            editedAt = null,
            deletedForEveryone = false,
            status = if (outgoing) MessageStatus.Sent else MessageStatus.Delivered,
            reactions = emptyList(),
            transport = Transport.NETWORK,
            networkRemoteId = remote,
            linkPreview = null,
            isOutgoing = outgoing,
        )
    }

    private fun messageKindFor(kind: AttachmentKind) =
        when (kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.AUDIO, AttachmentKind.FILE -> MessageKind.FILE
            AttachmentKind.VOICE -> MessageKind.VOICE
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.STICKER -> MessageKind.STICKER
            AttachmentKind.CONTACT -> MessageKind.CONTACT
            AttachmentKind.LOCATION -> MessageKind.LOCATION
        }

    private enum class FollowUp { REACT, REPLY, NOTHING }

    private companion object {
        const val RANDOM_SEED = 7

        /** Waits vary between half and one and a half times the interval. */
        const val JITTER_MIN = 0.5
        val REACTIONS = listOf("❤️", "😂", "👍")
    }
}

/**
 * Plays the other side of the demo network, for UI tests and trying things out: someone
 * messages, types, or reacts on command.
 */
class DemoSimulator internal constructor(
    private val network: DemoNetwork,
) {
    /** Someone in [chatId] sends [text]. With [from] unset, the chat's first participant. */
    fun incoming(
        chatId: ChatId,
        text: String,
        from: PersonId? = null,
    ) {
        val world = network.world(chatId.accountId)
        val sender =
            from ?: world.chats
                .getValue(chatId)
                .participants
                .first()
        val message = network.addIncoming(world, chatId, sender, text)
        network.emit(world, ConnectorEvent.NewMessage(world.accountId, world.snapshot(message)))
    }

    /** Someone in [chatId] starts or stops typing. */
    fun typing(
        chatId: ChatId,
        typing: Boolean,
        from: PersonId? = null,
    ) {
        val world = network.world(chatId.accountId)
        val who =
            from ?: world.chats
                .getValue(chatId)
                .participants
                .first()
        network.emit(world, ConnectorEvent.Typing(world.accountId, chatId, who, typing))
    }

    /** Someone reacts to [messageId] with [emoji]. */
    fun reaction(
        messageId: MessageId,
        emoji: String,
    ) = network.react(messageId, emoji)
}
