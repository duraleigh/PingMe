// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.Connector
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.Credentials
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.CallMethod
import org.pingme.core.model.CallRule
import org.pingme.core.model.Capabilities
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.MediaRule
import org.pingme.core.model.MessageId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.ReactionRule
import org.pingme.core.model.ReplyRule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A scriptable connector for testing the service. Not the demo network (that is P1.5). */
class FakeConnector : Connector {
    override val network = NetworkId.DEMO
    override val capabilities =
        Capabilities(
            reply = ReplyRule.NATIVE,
            deleteForMe = true,
            deleteForEveryone = null,
            reactions = ReactionRule.AnyEmoji,
            gif = MediaRule.NATIVE,
            voiceNote = MediaRule.NATIVE,
            typing = true,
            readReceipts = true,
            edit = null,
            nativePins = false,
            folders = false,
            startConversation = false,
            createGroup = false,
            block = false,
            multiAccount = true,
            calls = CallRule(CallMethod.NONE, CallMethod.NONE),
        )

    /** What each call to connect does, in order; the last one repeats. */
    val sessions = CopyOnWriteArrayList<suspend FlowCollector<ConnectorEvent>.(Account) -> Unit>()
    val connects = AtomicInteger()
    var chats: List<ChatSnapshot> = emptyList()
    var history: List<MessageSnapshot> = emptyList()
    var sendResult: (OutgoingMessage) -> SendResult = { SendResult.Failed("not scripted", retryable = false) }
    var download: (Attachment) -> File = { error("not scripted") }
    val readMarkers = CopyOnWriteArrayList<Pair<ChatId, MessageId>>()

    override fun loginFlow(): LoginFlow = throw UnsupportedCapabilityException("not used")

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        val index = connects.getAndIncrement().coerceAtMost(sessions.lastIndex)
        val session = sessions[index]
        return flow { session(account) }
    }

    override suspend fun disconnect(accountId: AccountId) = Unit

    override suspend fun syncChats(accountId: AccountId) = chats

    override suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val newestFirst = history.filter { it.message.chatId == chatId }.sortedByDescending { it.message.sentAt }
        val older =
            if (before == null) {
                newestFirst
            } else {
                newestFirst.dropWhile { it.message.id != before }.drop(1)
            }
        return older.take(limit)
    }

    override suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ) = sendResult(draft)

    override suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) = Unit

    override suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        readMarkers += chatId to upTo
    }

    val typingSent = mutableListOf<Boolean>()

    override suspend fun setTyping(
        chatId: ChatId,
        typing: Boolean,
    ) {
        typingSent += typing
    }

    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) = Unit

    override suspend fun downloadAttachment(attachment: Attachment) = download(attachment)

    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId = throw UnsupportedCapabilityException("not used")

    override suspend fun createGroup(
        accountId: AccountId,
        title: String,
        personHandles: List<String>,
    ): ChatId = throw UnsupportedCapabilityException("not used")

    override suspend fun block(chatId: ChatId) = throw UnsupportedCapabilityException("not used")

    override suspend fun edit(
        messageId: MessageId,
        text: String,
    ) = throw UnsupportedCapabilityException("not used")

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ) = Unit

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ) = Unit
}

/** Credentials held in memory, for tests. */
class MemoryCredentialStore : CredentialStore {
    private val secrets = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override suspend fun save(
        ref: String,
        secret: ByteArray,
    ) {
        secrets[ref] = secret
    }

    override suspend fun load(ref: String) = secrets[ref]

    override suspend fun delete(ref: String) {
        secrets.remove(ref)
    }
}
