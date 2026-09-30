// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.Connector
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.Credentials
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.accountId
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.connector.remoteId
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.Reaction
import org.pingme.core.model.ReactionRule
import org.pingme.core.model.TimeLimit
import org.pingme.core.model.Transport
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The demo network (BUILD_PLAN.md P1.5). Scripted people message on a timer, type first,
 * answer and react to what you send, and read your messages. Every capability can be
 * switched at runtime through [DemoControls]. The login walks through every kind of
 * login step. Nothing leaves the phone.
 */
@Singleton
class DemoConnector(
    private val controls: DemoControls,
    private val credentials: CredentialStore,
    private val mediaDir: File,
    private val clock: Clock,
) : Connector {
    @Inject
    constructor(
        controls: DemoControls,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
        clock: Clock,
    ) : this(controls, credentials, File(context.filesDir, "demo-media"), clock)

    override val network = NetworkId.DEMO
    override val capabilities get() = controls.settings.value.capabilities

    private val server = DemoNetwork(controls, clock)

    /** Plays the other side, for UI tests: someone messages, types, or reacts on command. */
    val simulate = DemoSimulator(server)

    private fun world(accountId: AccountId) = server.world(accountId)

    override fun loginFlow(): LoginFlow = demoLoginFlow(controls, credentials)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        if (!creds.secret.contentEquals(
                DEMO_SECRET,
            )
        ) {
            throw ActionNeededException("Sign in to the demo network again", null)
        }
        val world = world(account.id)
        val session = server.open(account.id)
        return channelFlow {
            send(ConnectorEvent.State(account.id, ConnectionState.Connected))
            val forward = launch { for (event in session) send(event) }
            val life = launch { server.liveActivity(world) }
            world.open.first { !it }
            forward.cancel()
            life.cancel()
        }
    }

    override suspend fun disconnect(accountId: AccountId) = server.close(accountId)

    override suspend fun syncChats(accountId: AccountId) =
        world(accountId).let { w ->
            synchronized(w) {
                w.chats.values.map(w::snapshot)
            }
        }

    override suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ) = world(chatId.accountId).let { w ->
        synchronized(w) {
            val newestFirst = w.history(chatId)
            val start = before?.let { id -> newestFirst.indexOfFirst { it.id == id } + 1 } ?: 0
            newestFirst.drop(start).take(limit).map(w::snapshot)
        }
    }

    override suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
    ): SendResult {
        val world = world(chatId.accountId)
        if (chatId !in world.chats) return SendResult.Failed("This chat is gone", retryable = false)
        val message = server.addOutgoing(world, chatId, draft.body, draft)
        server.followUp(world, message)
        return SendResult.Sent(world.snapshot(message))
    }

    override suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        val world = world(messageId.accountId)
        val target = synchronized(world) { world.findMessage(messageId) } ?: return
        when (val rule = capabilities.reactions) {
            // Sent as a text message, the way SMS does it (UI_DESIGN.md 5.4).
            ReactionRule.TextFallback -> {
                if (!remove && emoji != null) {
                    val text = "Reacted $emoji to “${target.body.orEmpty()}”"
                    val message = server.addOutgoing(world, target.chatId, text, draft = null)
                    server.emit(world, ConnectorEvent.NewMessage(world.accountId, world.snapshot(message)))
                }
                return
            }

            is ReactionRule.Set -> {
                if (!remove &&
                    emoji !in rule.allowed
                ) {
                    throw UnsupportedCapabilityException("Not available on the demo network")
                }
            }

            ReactionRule.AnyEmoji -> {
                // Any emoji goes.
            }
        }
        synchronized(world) {
            val kept = target.reactions.filterNot { it.senderId == world.me.id }
            val added = if (remove || emoji == null) emptyList() else listOf(Reaction(emoji, world.me.id, clock.now()))
            world.replaceMessage(target.copy(reactions = kept + added))
        }
    }

    override suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        val world = world(chatId.accountId)
        synchronized(world) { world.chats[chatId]?.unreadCount = 0 }
    }

    override suspend fun setTyping(
        chatId: ChatId,
        typing: Boolean,
    ) {
        if (!capabilities.typing) throw UnsupportedCapabilityException("Typing is turned off on the demo network")
    }

    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        // Delete for me changes nothing on the network, as on WhatsApp or Signal.
        if (!forEveryone) return
        val limit =
            capabilities.deleteForEveryone
                ?: throw UnsupportedCapabilityException("Delete for everyone is turned off on the demo network")
        val world = world(messageId.accountId)
        val deleted =
            synchronized(world) {
                val message = world.findMessage(messageId) ?: return
                if (limit is TimeLimit.Within && clock.now() - message.sentAt > limit.duration) {
                    throw UnsupportedCapabilityException("Only possible for ${limit.duration} after sending")
                }
                world.deletedForEveryone += messageId
                message
                    .copy(body = null, kind = MessageKind.DELETED, attachments = emptyList(), deletedForEveryone = true)
                    .also(world::replaceMessage)
            }
        server.emit(world, ConnectorEvent.MessageUpdated(world.accountId, world.snapshot(deleted)))
    }

    override suspend fun downloadAttachment(attachment: Attachment): File =
        withContext(Dispatchers.IO) {
            attachment.localPath?.let { return@withContext File(it) }
            mediaDir.mkdirs()
            val media = DemoSeed.Media.valueOf(attachment.remoteRef ?: DemoSeed.Media.PICTURE.name)
            val file = File(mediaDir, "${attachment.id.remoteId}.${media.extension}")
            if (!file.exists()) file.writeBytes(media.bytes(attachment.id.value.hashCode()))
            file
        }

    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId {
        if (!capabilities.startConversation) {
            throw UnsupportedCapabilityException("Starting chats is turned off on the demo network")
        }
        val world = world(accountId)
        val remote = "new-${personHandle.filter(Char::isLetterOrDigit)}"
        val chat =
            synchronized(world) {
                val person =
                    Person(accountId.person(remote), accountId, personHandle, personHandle, personHandle, null, null)
                world.people[person.id] = person
                world.chats.getOrPut(accountId.chat(remote)) {
                    DemoWorld.ChatState(
                        accountId.chat(remote),
                        ChatKind.DIRECT,
                        personHandle,
                        listOf(person.id),
                        0,
                        null,
                        remote,
                    )
                }
            }
        world.messages.getOrPut(chat.id) { mutableListOf() }
        server.emit(world, ConnectorEvent.ChatUpdated(accountId, world.snapshot(chat)))
        return chat.id
    }

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ) {
        requireFolders()
        val world = world(chatId.accountId)
        val chat = synchronized(world) { world.chats[chatId]?.also { it.folder = folder } } ?: return
        server.emit(world, ConnectorEvent.ChatUpdated(world.accountId, world.snapshot(chat)))
    }

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ) {
        requireFolders()
        val world = world(chatId.accountId)
        if (accept) {
            moveFolder(chatId, ChatFolder.PRIMARY)
        } else {
            synchronized(world) { world.chats.remove(chatId) }
            server.emit(world, ConnectorEvent.ChatRemoved(world.accountId, chatId))
        }
    }

    internal fun textsSentTo(chatId: ChatId): List<String> =
        server.world(chatId.accountId).let { w ->
            synchronized(
                w,
            ) {
                w.messages[chatId]
                    .orEmpty()
                    .filter { it.isOutgoing }
                    .sortedBy { it.sentAt }
                    .mapNotNull { it.body }
            }
        }

    internal fun myReactionsOn(messageId: MessageId): List<String> =
        server.world(messageId.accountId).let { w ->
            synchronized(
                w,
            ) {
                w
                    .findMessage(messageId)
                    ?.reactions
                    ?.filter { it.senderId == w.me.id }
                    ?.map { it.emoji }
                    .orEmpty()
            }
        }

    internal fun deletedForEveryone(): Set<MessageId> =
        server
            .knownAccounts()
            .flatMap { id ->
                server.world(id).let { w -> synchronized(w) { w.deletedForEveryone.toSet() } }
            }.toSet()

    private fun requireFolders() {
        if (!capabilities.folders) throw UnsupportedCapabilityException("Folders are turned off on the demo network")
    }

    companion object {
        /** What a demo login stores as its "credentials". */
        internal val DEMO_SECRET = "pingme-demo".toByteArray()
    }
}
