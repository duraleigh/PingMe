// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import org.pingme.connectors.gmessages.bridge.GmBridge
import org.pingme.connectors.gmessages.bridge.GoBridge
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
import kotlin.time.Clock

/**
 * Google Messages through its Google account pairing (BUILD_PLAN.md P3.2, DESIGN.md 5.2,
 * 5.4). RCS, SMS, and MMS all come through the one pairing; the phone's own Google
 * Messages app stays the SMS app and keeps RCS on. One account: a phone has one pairing.
 */
@Singleton
class GmessagesConnector(
    private val bridge: GmBridge,
    private val checks: GmessagesChecks,
    private val credentials: CredentialStore,
    private val mediaDir: File,
    private val clock: Clock,
) : Connector {
    @Inject
    constructor(
        bridge: GmBridge,
        checks: GmessagesChecks,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
        clock: Clock,
    ) : this(bridge, checks, credentials, File(context.filesDir, "gmessages-media"), clock)

    override val network = NetworkId.GMESSAGES
    override val capabilities = CAPABILITIES

    private val sessions = ConcurrentHashMap<AccountId, GmessagesSession>()

    override fun loginFlow(): LoginFlow = gmessagesLoginFlow(bridge, checks, credentials)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val session = GmessagesSession(account.id, bridge, creds.secret.decodeToString(), creds.ref, credentials, clock)
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
            ?: SendResult.Failed("Not connected to Google Messages", retryable = true)

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
    ) {
        // Google Messages has no "stopped typing"; the indicator times out on its own.
        if (typing) session(chatId.accountId).typing(chatId)
    }

    /**
     * Delete for me also deletes the phone's copy, through the pairing, so Google Messages
     * matches (UI_DESIGN.md 5.3). Google exposes no delete for everyone to paired devices.
     */
    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        if (forEveryone) throw UnsupportedCapabilityException("Google Messages cannot delete a message for everyone")
        session(messageId.accountId).delete(messageId)
    }

    override suspend fun edit(
        messageId: MessageId,
        text: String,
    ): Unit = throw UnsupportedCapabilityException("Google Messages cannot edit a sent message")

    override suspend fun downloadAttachment(attachment: Attachment): File {
        attachment.localPath?.let { return File(it) }
        val ref = GoBridge.MediaRef.decode(requireNotNull(attachment.remoteRef) { "the phone has not uploaded it yet" })
        val name = sha(attachment.id.value) + extensionOf(attachment)
        return session(attachment.id.accountId).download(ref, File(mediaDir, name))
    }

    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId = session(accountId).openChat(listOf(number(personHandle)), groupName = "")

    override suspend fun createGroup(
        accountId: AccountId,
        title: String,
        personHandles: List<String>,
    ): ChatId {
        require(personHandles.size >= 2) { "A group needs at least two people" }
        return session(accountId).openChat(personHandles.map(::number), title)
    }

    override suspend fun block(chatId: ChatId): Unit =
        throw UnsupportedCapabilityException("Block people in Google Messages itself")

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("Google Messages has no folders")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ): Unit = throw UnsupportedCapabilityException("Google Messages has no message requests")

    private fun session(accountId: AccountId): GmessagesSession =
        sessions[accountId] ?: throw IllegalStateException("Not connected to Google Messages")

    private fun number(handle: String): String {
        val digits = handle.filter { it.isDigit() || it == '+' }
        require(digits.count { it.isDigit() } >= MIN_NUMBER_DIGITS) { "$handle is not a phone number" }
        return digits
    }

    private fun extensionOf(attachment: Attachment): String {
        val fromName = attachment.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
        val fromMime = attachment.mimeType.substringAfter('/', "").takeIf { it.isNotEmpty() && it.length <= 5 }
        return (fromName ?: fromMime)?.let { ".$it" } ?: ""
    }

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MIN_NUMBER_DIGITS = 3

        /** What RCS through Google Messages can do (UI_DESIGN.md 8). */
        val CAPABILITIES =
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
                startConversation = true,
                createGroup = true,
                block = false,
                multiAccount = false,
                calls = CallRule(CallMethod.DIALER, CallMethod.MEET),
            )
    }
}
