// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import org.pingme.connectors.whatsapp.bridge.WaBridge
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * WhatsApp as a linked device (BUILD_PLAN.md Phase 6, network 1; DESIGN.md 5.2), linked
 * with the eight-character code (owner, 2026-10-02: no QR path). Each linked number keeps
 * its device keys in its own SQLite file under app storage; several numbers can be linked.
 */
@Singleton
class WhatsappConnector(
    private val bridge: WaBridge,
    private val credentials: CredentialStore,
    private val dir: File,
) : Connector {
    @Inject
    constructor(
        bridge: WaBridge,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
    ) : this(bridge, credentials, File(context.filesDir, "whatsapp"))

    override val network = NetworkId.WHATSAPP
    override val capabilities = CAPABILITIES

    private val sessions = ConcurrentHashMap<AccountId, WhatsappSession>()

    override fun loginFlow(): LoginFlow = whatsappLoginFlow(bridge, credentials, dir)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val session = WhatsappSession(account.id, bridge, storePath(dir, creds.ref))
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
            ?: SendResult.Failed("Not connected to WhatsApp", retryable = true)

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

    /** Delete for me stays on this phone; delete for everyone revokes within WhatsApp's limit. */
    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        if (forEveryone) session(messageId.accountId).revoke(messageId)
    }

    override suspend fun edit(
        messageId: MessageId,
        text: String,
    ) = session(messageId.accountId).edit(messageId, text)

    override suspend fun downloadAttachment(attachment: Attachment): File {
        attachment.localPath?.let { return File(it) }
        val name = sha(attachment.id.value) + extensionOf(attachment)
        return session(attachment.id.accountId).download(attachment, File(dir.resolve("media"), name))
    }

    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId = session(accountId).startChat(number(personHandle))

    override suspend fun createGroup(
        accountId: AccountId,
        title: String,
        personHandles: List<String>,
    ): ChatId {
        require(personHandles.isNotEmpty()) { "A group needs someone in it" }
        return session(accountId).createGroup(title, personHandles.map(::number))
    }

    override suspend fun block(chatId: ChatId) = session(chatId.accountId).block(chatId)

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("WhatsApp has no folders")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ): Unit = throw UnsupportedCapabilityException("WhatsApp has no message requests")

    private fun session(accountId: AccountId): WhatsappSession =
        sessions[accountId] ?: throw IllegalStateException("Not connected to WhatsApp")

    private fun number(handle: String): String {
        val digits = handle.filter { it.isDigit() }
        require(digits.length >= MIN_NUMBER_DIGITS) { "$handle is not a phone number with a country code" }
        return digits
    }

    private fun extensionOf(attachment: Attachment): String {
        val fromName = attachment.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
        val fromMime =
            when (attachment.mimeType) {
                "image/jpeg" -> "jpg"
                "audio/ogg" -> "ogg"
                "application/geo+json" -> "geojson"
                "text/vcard" -> "vcf"
                else -> attachment.mimeType.substringAfter('/', "").takeIf { it.isNotEmpty() && it.length <= 5 }
            }
        return (fromName ?: fromMime)?.let { ".$it" } ?: ""
    }

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MIN_NUMBER_DIGITS = 7

        /** The device store for a linked number: `whatsapp/<digits>` names `<digits>.db`. */
        internal fun storePath(
            dir: File,
            credentialRef: String,
        ): String = File(dir, "${credentialRef.substringAfter('/')}.db").also { it.parentFile?.mkdirs() }.absolutePath

        /** What WhatsApp through a linked device can do (UI_DESIGN.md 8). */
        val CAPABILITIES =
            Capabilities(
                reply = ReplyRule.NATIVE,
                deleteForMe = true,
                // WhatsApp allows delete for everyone for about two days.
                deleteForEveryone = TimeLimit.Within(2.days),
                reactions = ReactionRule.AnyEmoji,
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.NATIVE,
                typing = true,
                readReceipts = true,
                edit = TimeLimit.Within(15.minutes),
                nativePins = false,
                folders = false,
                startConversation = true,
                createGroup = true,
                block = true,
                multiAccount = true,
                calls = CallRule(CallMethod.CONTACT_APP_CALL, CallMethod.CONTACT_APP_CALL),
            )
    }
}
