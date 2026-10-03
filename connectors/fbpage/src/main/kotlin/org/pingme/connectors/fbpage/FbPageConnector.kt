// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
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
import org.pingme.core.connector.chat
import org.pingme.core.connector.remoteId
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * A Facebook Page's Messenger inbox through Meta's official Messenger Platform
 * (BUILD_PLAN.md Phase 6, network 7; DESIGN.md 5.2): a pasted Page token, polling from
 * the phone, replies within Meta's 24-hour window. The one Meta connector with no
 * account risk.
 */
@Singleton
class FbPageConnector(
    private val api: PageApi,
    private val credentials: CredentialStore,
    private val mediaDir: File,
    private val pollEvery: Duration,
) : Connector {
    @Inject
    constructor(
        api: PageApi,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
    ) : this(api, credentials, File(context.filesDir, "fbpage-media"), FbPageSession.DEFAULT_POLL)

    override val network = NetworkId.FBPAGE
    override val capabilities = CAPABILITIES

    private val graph = PageGraph(api)
    private val sessions = ConcurrentHashMap<AccountId, FbPageSession>()

    override fun loginFlow(): LoginFlow = fbPageLoginFlow(graph, credentials)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val saved = PageCredential.parse(creds.secret)
        val session = FbPageSession(account.id, graph, saved.token, saved.pageId, saved.pageName, pollEvery)
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
            ?: SendResult.Failed("The Page is not connected", retryable = true)

    /** Meta's Page API has no reactions: the emoji goes as a short text, as SMS does (UI_DESIGN.md 5.4). */
    override suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        if (remove || emoji.isNullOrEmpty()) return
        val chat = messageId.accountId.chat(messageId.remoteId.substringBefore('/'))
        val result =
            session(
                messageId.accountId,
            ).send(chat, OutgoingMessage(messageId, emoji, emptyList(), null, null, false), {})
        if (result is SendResult.Failed) throw UnsupportedCapabilityException(result.reason)
    }

    override suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) = session(chatId.accountId).markRead(chatId)

    /** The Page API cannot see when a person types, so PingMe shows no typing either way. */
    override suspend fun setTyping(
        chatId: ChatId,
        typing: Boolean,
    ): Unit = throw UnsupportedCapabilityException("Typing is not shown for a Page inbox")

    /** Delete for me stays on this phone; the Page API cannot take a message back. */
    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        if (forEveryone) throw UnsupportedCapabilityException("A Page cannot unsend a message")
    }

    override suspend fun edit(
        messageId: MessageId,
        text: String,
    ): Unit = throw UnsupportedCapabilityException("A Page cannot edit a sent message")

    override suspend fun downloadAttachment(attachment: Attachment): File {
        attachment.localPath?.let { return File(it) }
        val name = sha(attachment.id.value) + extensionOf(attachment)
        return session(attachment.id.accountId).download(attachment, File(mediaDir, name))
    }

    /** Meta lets a Page answer people, not approach them. */
    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId = throw UnsupportedCapabilityException("A Page can only reply to people who message it first")

    override suspend fun createGroup(
        accountId: AccountId,
        title: String,
        personHandles: List<String>,
    ): ChatId = throw UnsupportedCapabilityException("A Page has no group chats")

    override suspend fun block(chatId: ChatId): Unit =
        throw UnsupportedCapabilityException("Block people from the Page's inbox on Facebook")

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("A Page inbox has no folders here")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ): Unit = throw UnsupportedCapabilityException("A Page inbox has no requests")

    private fun session(accountId: AccountId): FbPageSession =
        sessions[accountId] ?: throw IllegalStateException("The Page is not connected")

    private fun extensionOf(attachment: Attachment): String =
        attachment.fileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
            ?.let { ".$it" }
            ?: when (attachment.mimeType) {
                "image/jpeg" -> ".jpg"
                "image/gif" -> ".gif"
                "video/mp4" -> ".mp4"
                else -> ""
            }

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_EXTENSION = 5

        /** What Meta's Page API allows (UI_DESIGN.md 8). */
        val CAPABILITIES =
            Capabilities(
                reply = ReplyRule.QUOTED_TEXT,
                deleteForMe = true,
                deleteForEveryone = null,
                reactions = ReactionRule.TextFallback,
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.NATIVE,
                typing = false,
                readReceipts = false,
                edit = null,
                nativePins = false,
                folders = false,
                startConversation = false,
                createGroup = false,
                block = false,
                multiAccount = true,
                calls = CallRule(CallMethod.NONE, CallMethod.NONE),
            )
    }
}
