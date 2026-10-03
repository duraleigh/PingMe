// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import org.pingme.connectors.messenger.bridge.FbBridge
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
import org.pingme.core.connector.accountId
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
import org.pingme.core.model.TimeLimit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * Messenger through a signed-in facebook.com session (BUILD_PLAN.md Phase 6, network 6;
 * DESIGN.md 5.2): cookie sign-in in the in-app browser, the inbox and the request queue
 * (UI_DESIGN.md 6.4).
 */
@Singleton
class MessengerConnector(
    private val bridge: FbBridge,
    private val credentials: CredentialStore,
    private val mediaDir: File,
) : Connector {
    @Inject
    constructor(
        bridge: FbBridge,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
    ) : this(bridge, credentials, File(context.filesDir, "messenger-media"))

    override val network = NetworkId.MESSENGER
    override val capabilities = CAPABILITIES

    private val sessions = ConcurrentHashMap<AccountId, MessengerSession>()

    override fun loginFlow(): LoginFlow = messengerLoginFlow(bridge, credentials)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val session = MessengerSession(account.id, bridge, creds.secret.decodeToString(), creds.ref, credentials)
        sessions[account.id] = session
        return session.flow().onCompletion { sessions.remove(account.id, session) }
    }

    override suspend fun disconnect(accountId: AccountId) {
        sessions.remove(accountId)?.close()
    }

    override suspend fun syncChats(accountId: AccountId): List<ChatSnapshot> = session(accountId).syncChats()

    override suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> = session(chatId.accountId).syncMessages(chatId, before, limit)

    override suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult =
        sessions[chatId.accountId]?.send(chatId, draft, progress)
            ?: SendResult.Failed("Not connected to Messenger", retryable = true)

    override suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) = session(messageId.accountId).react(messageId, emoji, remove)

    override suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) = session(chatId.accountId).markRead(chatId, upTo)

    override suspend fun setTyping(
        chatId: ChatId,
        typing: Boolean,
    ) = session(chatId.accountId).typing(chatId, typing)

    /** Delete for me stays on this phone; delete for everyone unsends. */
    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        if (forEveryone) session(messageId.accountId).unsend(messageId)
    }

    override suspend fun edit(
        messageId: MessageId,
        text: String,
    ) = session(messageId.accountId).edit(messageId, text)

    override suspend fun downloadAttachment(attachment: Attachment): File {
        attachment.localPath?.let { return File(it) }
        val name = sha(attachment.id.value) + extensionOf(attachment)
        return session(attachment.id.accountId).download(attachment, File(mediaDir, name))
    }

    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId = session(accountId).startConversation(personHandle)

    override suspend fun createGroup(
        accountId: AccountId,
        title: String,
        personHandles: List<String>,
    ): ChatId = throw UnsupportedCapabilityException("Make Messenger groups in Messenger itself for now")

    override suspend fun block(chatId: ChatId): Unit =
        throw UnsupportedCapabilityException("Block people in Messenger itself")

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("Messenger sorts chats itself")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ) = session(chatId.accountId).respondToRequest(chatId, accept)

    private fun session(accountId: AccountId): MessengerSession =
        sessions[accountId] ?: throw IllegalStateException("Not connected to Messenger")

    private fun extensionOf(attachment: Attachment): String =
        attachment.fileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
            ?.let { ".$it" }
            ?: when (attachment.mimeType) {
                "image/jpeg" -> {
                    ".jpg"
                }

                "image/webp" -> {
                    ".webp"
                }

                "video/mp4" -> {
                    ".mp4"
                }

                "audio/mp4" -> {
                    ".m4a"
                }

                else -> {
                    attachment.mimeType
                        .substringAfter('/', "")
                        .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
                        ?.let { ".$it" }
                        ?: ""
                }
            }

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_EXTENSION = 5

        /** What Messenger through the website's session can do (UI_DESIGN.md 8). */
        val CAPABILITIES =
            Capabilities(
                reply = ReplyRule.NATIVE,
                deleteForMe = true,
                deleteForEveryone = TimeLimit.Unlimited,
                reactions = ReactionRule.AnyEmoji,
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.NATIVE,
                typing = true,
                readReceipts = true,
                edit = TimeLimit.Within(15.minutes),
                nativePins = false,
                folders = true,
                startConversation = true,
                createGroup = false,
                block = false,
                multiAccount = true,
                calls = CallRule(CallMethod.OPEN_THREAD, CallMethod.OPEN_THREAD),
            )
    }
}
