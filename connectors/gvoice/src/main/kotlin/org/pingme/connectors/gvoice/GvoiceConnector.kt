// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import org.pingme.connectors.gvoice.bridge.GvBridge
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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Voice through a signed-in Google account (BUILD_PLAN.md Phase 6, network 4;
 * DESIGN.md 5.2): texts and picture messages behave like SMS, with quoted-text replies
 * and reactions as text (UI_DESIGN.md 5.2, 5.4).
 */
@Singleton
class GvoiceConnector(
    private val bridge: GvBridge,
    private val credentials: CredentialStore,
    private val mediaDir: File,
) : Connector {
    @Inject
    constructor(
        bridge: GvBridge,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
    ) : this(bridge, credentials, File(context.filesDir, "gvoice-media"))

    override val network = NetworkId.GVOICE
    override val capabilities = CAPABILITIES

    private val sessions = ConcurrentHashMap<AccountId, GvoiceSession>()

    override fun loginFlow(): LoginFlow = gvoiceLoginFlow(bridge, credentials)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val session = GvoiceSession(account.id, bridge, creds.secret.decodeToString(), creds.ref, credentials)
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
            ?: SendResult.Failed("Not connected to Google Voice", retryable = true)

    /** No reactions on Google Voice: one goes out as a text naming the message (UI_DESIGN.md 5.4). */
    override suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        if (remove || emoji == null) return
        session(messageId.accountId).reactAsText(messageId, emoji, "")
    }

    override suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) = session(chatId.accountId).markRead(chatId)

    override suspend fun setTyping(
        chatId: ChatId,
        typing: Boolean,
    ): Unit = throw UnsupportedCapabilityException("Google Voice has no typing notices")

    /** Delete for me stays on this phone; Google Voice has no delete for everyone. */
    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        if (forEveryone) throw UnsupportedCapabilityException("Google Voice cannot take a text back")
    }

    override suspend fun edit(
        messageId: MessageId,
        text: String,
    ): Unit = throw UnsupportedCapabilityException("Google Voice cannot edit a sent text")

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
    ): ChatId = throw UnsupportedCapabilityException("Make group texts in Google Voice itself")

    override suspend fun block(chatId: ChatId) = session(chatId.accountId).block(chatId)

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("Google Voice has no folders")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ): Unit = throw UnsupportedCapabilityException("Google Voice has no message requests")

    private fun session(accountId: AccountId): GvoiceSession =
        sessions[accountId] ?: throw IllegalStateException("Not connected to Google Voice")

    private fun extensionOf(attachment: Attachment): String =
        attachment.mimeType
            .substringAfter('/', "")
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
            ?.let { ".$it" }
            ?: ""

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_EXTENSION = 5

        /** What Google Voice can do (UI_DESIGN.md 8): SMS-like, pictures only. */
        val CAPABILITIES =
            Capabilities(
                reply = ReplyRule.QUOTED_TEXT,
                deleteForMe = true,
                deleteForEveryone = null,
                reactions = ReactionRule.TextFallback,
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.UNSUPPORTED,
                typing = false,
                readReceipts = false,
                edit = null,
                nativePins = false,
                folders = false,
                startConversation = true,
                createGroup = false,
                block = true,
                multiAccount = true,
                calls = CallRule(CallMethod.OPEN_APP, CallMethod.NONE),
            )
    }
}
