// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import org.pingme.connectors.telegram.td.TelegramBridge
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
 * Telegram through TDLib (BUILD_PLAN.md Phase 6, network 2; DESIGN.md 5.2): phone and
 * code sign-in, forum topics as spaces, the free reaction set. Each account keeps TDLib's
 * database in its own folder under app storage.
 */
@Singleton
class TelegramConnector(
    private val bridge: TelegramBridge,
    private val credentials: CredentialStore,
    private val dir: File,
    private val apiId: Int,
    private val apiHash: String,
) : Connector {
    @Inject
    constructor(
        bridge: TelegramBridge,
        credentials: CredentialStore,
        @ApplicationContext context: Context,
    ) : this(
        bridge,
        credentials,
        File(context.filesDir, "telegram"),
        BuildConfig.TELEGRAM_API_ID,
        BuildConfig.TELEGRAM_API_HASH,
    )

    override val network = NetworkId.TELEGRAM
    override val capabilities = CAPABILITIES

    private val sessions = ConcurrentHashMap<AccountId, TelegramSession>()

    override fun loginFlow(): LoginFlow = telegramLoginFlow(bridge, credentials, dir, apiId, apiHash)

    override suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent> {
        sessions.remove(account.id)?.close()
        val session = TelegramSession(account.id, bridge, storeDir(dir, creds.ref), apiId, apiHash)
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
            ?: SendResult.Failed("Not connected to Telegram", retryable = true)

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

    /** Delete for me stays on this phone; delete for everyone is Telegram's own. */
    override suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    ) {
        if (forEveryone) session(messageId.accountId).delete(messageId)
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
    ): ChatId = session(accountId).createGroup(title, personHandles)

    override suspend fun block(chatId: ChatId) = session(chatId.accountId).block(chatId)

    override suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    ): Unit = throw UnsupportedCapabilityException("Telegram's folders are managed in Telegram itself")

    override suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ): Unit = throw UnsupportedCapabilityException("Telegram has no message requests")

    private fun session(accountId: AccountId): TelegramSession =
        sessions[accountId] ?: throw IllegalStateException("Not connected to Telegram")

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

        /** Where a credential ref ("telegram/<user id>") keeps TDLib's database. */
        internal fun storeDir(
            dir: File,
            credentialRef: String,
        ): File = File(dir, credentialRef.substringAfter('/')).also { it.mkdirs() }

        /** The reactions every Telegram account may use; Premium ones are not offered (UI_DESIGN.md 5.4). */
        val FREE_REACTIONS =
            listOf(
                "👍",
                "👎",
                "❤",
                "🔥",
                "🥰",
                "👏",
                "😁",
                "🤔",
                "🤯",
                "😱",
                "🤬",
                "😢",
                "🎉",
                "🤩",
                "🤮",
                "💩",
                "🙏",
                "👌",
                "🕊",
                "🤡",
                "🥱",
                "🥴",
                "😍",
                "🐳",
                "❤‍🔥",
                "🌚",
                "🌭",
                "💯",
                "🤣",
                "⚡",
                "🍌",
                "🏆",
                "💔",
                "🤨",
                "😐",
                "🍓",
                "🍾",
                "💋",
                "🖕",
                "😈",
                "😴",
                "😭",
                "🤓",
                "👻",
                "👨‍💻",
                "👀",
                "🎃",
                "🙈",
                "😇",
                "😨",
                "🤝",
                "✍",
                "🤗",
                "🫡",
                "🎅",
                "🎄",
                "☃",
                "💅",
                "🤪",
                "🗿",
                "🆒",
                "💘",
                "🙉",
                "🦄",
                "😘",
                "💊",
                "🙊",
                "😎",
                "👾",
                "🤷‍♂",
                "🤷",
                "🤷‍♀",
                "😡",
            )

        /** What Telegram through TDLib can do (UI_DESIGN.md 8). */
        val CAPABILITIES =
            Capabilities(
                reply = ReplyRule.NATIVE,
                deleteForMe = true,
                deleteForEveryone = TimeLimit.Unlimited,
                reactions = ReactionRule.Set(FREE_REACTIONS),
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.NATIVE,
                typing = true,
                readReceipts = true,
                edit = TimeLimit.Within(48.hours),
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
