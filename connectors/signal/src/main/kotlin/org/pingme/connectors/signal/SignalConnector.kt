// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import org.pingme.connectors.signal.bridge.SigBridge
import org.pingme.core.connector.AddressBook
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
import kotlin.time.Duration.Companion.hours

/**
 * Signal as a linked device (BUILD_PLAN.md Phase 6, network 3; DESIGN.md 5.2), linked by
 * a QR code the phone's Signal app scans. Each account keeps its keys and transferred
 * history in its own SQLite file under app storage.
 */
@Singleton
class SignalConnector(
    private val bridge: SigBridge,
    private val credentials: CredentialStore,
    private val dir: File,
    private val addressBook: AddressBook = AddressBook.None,
) : Connector {
    @Inject
    constructor(
        bridge: SigBridge,
        credentials: CredentialStore,
        addressBook: AddressBook,
        @ApplicationContext context: Context,
    ) : this(bridge, credentials, File(context.filesDir, "signal"), addressBook)

    /** The phone's contacts who are on Signal, found through Signal's directory (owner, Gate G7). */
    override suspend fun refreshPeople(accountId: AccountId) {
        val session = sessions[accountId] ?: return
        session.refreshPeople(addressBook.entries())
    }

    override val network = NetworkId.SIGNAL
    override val capabilities = CAPABILITIES

    private val sessions = ConcurrentHashMap<AccountId, SignalSession>()

    override fun loginFlow(): LoginFlow = signalLoginFlow(bridge, credentials, dir)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val session = SignalSession(account.id, bridge, storePath(dir, creds.ref))
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
            ?: SendResult.Failed("Not connected to Signal", retryable = true)

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

    /** Delete for me stays on this phone; delete for everyone is Signal's own, within a day. */
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
        return session(attachment.id.accountId).download(attachment, File(dir, "media/$name"))
    }

    override suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId = session(accountId).startConversation(personHandle)

    override suspend fun createGroup(
        accountId: AccountId,
        title: String,
        personHandles: List<String>,
    ): ChatId = throw UnsupportedCapabilityException("Make Signal groups in Signal itself for now")

    override suspend fun block(chatId: ChatId): Unit =
        throw UnsupportedCapabilityException("Block people in Signal itself")

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("Signal has no folders")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ): Unit = throw UnsupportedCapabilityException("Answer message requests in Signal itself")

    private fun session(accountId: AccountId): SignalSession =
        sessions[accountId] ?: throw IllegalStateException("Not connected to Signal")

    private fun extensionOf(attachment: Attachment): String =
        attachment.fileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
            ?.let { ".$it" }
            ?: attachment.mimeType
                .substringAfter('/', "")
                .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
                ?.let { ".$it" }
            ?: ""

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_EXTENSION = 5

        /** Where a credential ref ("signal/<account id>") keeps its store. */
        internal fun storePath(
            dir: File,
            credentialRef: String,
        ): String = File(dir, "${credentialRef.substringAfter('/')}.db").also { it.parentFile?.mkdirs() }.absolutePath

        /** What Signal through a linked device can do (UI_DESIGN.md 8). */
        val CAPABILITIES =
            Capabilities(
                reply = ReplyRule.NATIVE,
                deleteForMe = true,
                deleteForEveryone = TimeLimit.Within(24.hours),
                reactions = ReactionRule.AnyEmoji,
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.NATIVE,
                typing = true,
                readReceipts = true,
                edit = TimeLimit.Within(24.hours),
                nativePins = false,
                folders = false,
                startConversation = true,
                createGroup = false,
                block = false,
                multiAccount = true,
                calls = CallRule(CallMethod.CONTACT_APP_CALL, CallMethod.CONTACT_APP_CALL),
            )
    }
}
