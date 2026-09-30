// SPDX-License-Identifier: AGPL-3.0-or-later
// The function list is the contract in BUILD_PLAN.md P1.3; splitting it would split the contract.
@file:Suppress("TooManyFunctions")

package org.pingme.core.connector

import kotlinx.coroutines.flow.Flow
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.Capabilities
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.model.NetworkId
import java.io.File

/**
 * One network's adapter (DESIGN.md 6.2, BUILD_PLAN.md P1.3). Nothing outside a connector
 * ever sees network-specific data: connectors translate to and from core/model.
 *
 * One instance serves every account on its network. IDs it hands out are scoped to an
 * account (see ScopedIds), so a chat, message, or attachment ID alone says which account
 * an operation is for.
 *
 * Operations a network cannot do throw [UnsupportedCapabilityException]; the UI reads
 * [capabilities] first and shows those controls disabled with the reason.
 */
interface Connector {
    val network: NetworkId
    val capabilities: Capabilities

    /** A new login. The UI renders its steps without knowing the network (DESIGN.md 6.2). */
    fun loginFlow(): LoginFlow

    /**
     * Opens the live connection for [account] and streams everything that happens on it.
     * The flow ends when the connection drops; the supervisor (P1.4) reconnects.
     */
    suspend fun connect(
        account: Account,
        creds: Credentials,
    ): Flow<ConnectorEvent>

    suspend fun disconnect(accountId: AccountId)

    suspend fun syncChats(accountId: AccountId): List<ChatSnapshot>

    /** Up to [limit] messages older than [before] (newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot>

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
    ): SendResult

    /** Adds [emoji], or removes the user's reaction when [remove] is true. */
    suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    )

    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    )

    suspend fun setTyping(
        chatId: ChatId,
        typing: Boolean,
    )

    suspend fun delete(
        messageId: MessageId,
        forEveryone: Boolean,
    )

    suspend fun downloadAttachment(attachment: Attachment): File

    /**
     * Opens or creates a chat with [personHandle] (a phone number, username, ...) on
     * [accountId]. Throws when `capabilities.startConversation` is false.
     */
    suspend fun startConversation(
        accountId: AccountId,
        personHandle: String,
    ): ChatId

    /** Instagram only: moves a chat between Primary and General. */
    suspend fun moveFolder(
        chatId: ChatId,
        folder: ChatFolder,
    )

    /** Instagram only: accepts or declines a message request. */
    suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    )
}
