// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.pingme.core.connector.Connector
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.accountId
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * What the inbox's swipes and action sheet do to a chat (UI_DESIGN.md 3.1, 5.1, 10.7,
 * 10.10). Everything is local except marking read, which also tells the network.
 */
@Singleton
// One function per thing the inbox or chat details can do to a chat; the list reads as one.
@Suppress("TooManyFunctions")
class ChatActions
    @Inject
    constructor(
        private val chats: ChatRepository,
        private val messages: MessageRepository,
        private val accounts: AccountRepository,
        private val registry: ConnectorRegistry,
        private val applier: EventApplier,
        private val settings: org.pingme.core.store.SettingsRepository,
        private val notifications: NotificationRouter,
    ) {
        /** The chat is on screen: its notification comes down at once (owner, Gate G2). */
        fun opened(id: ChatId) = notifications.clear(id)

        /** Pins or unpins. Returns false when pinning would pass [MAX_PINS]. */
        suspend fun setPinned(
            id: ChatId,
            pinned: Boolean,
        ): Boolean {
            if (!pinned) {
                chats.update(id) { it.copy(isPinned = false, pinOrder = null) }
                return true
            }
            val pins = chats.pinned()
            if (pins.any { it.id == id }) return true
            if (pins.size >= MAX_PINS) return false
            val next = (pins.mapNotNull { it.pinOrder }.maxOrNull() ?: -1) + 1
            // Archived and low-priority chats come back to the inbox when pinned.
            chats.update(id) { it.copy(isPinned = true, pinOrder = next, isArchived = false, isLowPriority = false) }
            return true
        }

        /** Moves [id] to [position] in the pinned grid, shifting the others along. */
        suspend fun movePin(
            id: ChatId,
            position: Int,
        ) {
            val order =
                chats
                    .pinned()
                    .map { it.id }
                    .toMutableList()
            if (!order.remove(id)) return
            order.add(position.coerceIn(0, order.size), id)
            order.forEachIndexed { index, chatId -> chats.update(chatId) { it.copy(pinOrder = index) } }
        }

        /**
         * Read clears the unread count here and sends a read marker for the newest message,
         * so the other side sees it. Unread is local: networks have no "mark unread".
         */
        suspend fun setRead(
            id: ChatId,
            read: Boolean,
        ) {
            if (read) {
                chats.update(id) { it.copy(unreadCount = 0) }
                notifications.clear(id)
                sendReadMarker(id)
            } else {
                chats.update(id) { if (it.unreadCount > 0) it else it.copy(unreadCount = 1) }
            }
        }

        private suspend fun sendReadMarker(id: ChatId) {
            val newest = messages.newest(id) ?: return
            val network = accounts.get(id.accountId)?.network ?: return
            // "Send read receipts" off: the network never hears it (UI_DESIGN.md 10.3).
            if (!settings.app
                    .first()
                    .privacy
                    .sendsReadReceipts(network)
            ) {
                return
            }
            val connector = registry[network] ?: return
            try {
                connector.markRead(id, newest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedCapabilityException) {
                Log.i(TAG, "No read markers on $network", e)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Offline or disconnected: the chat still reads as read here.
                Log.w(TAG, "Could not send the read marker to $network", e)
            }
        }

        suspend fun setMuted(
            id: ChatId,
            muted: Boolean,
        ) = change(id) { it.copy(isMuted = muted, muteUntil = null) }

        /** Archive takes a chat out of the pinned grid too: pinned chats are never archived (5.1). */
        suspend fun setArchived(
            id: ChatId,
            archived: Boolean,
        ) = change(id) {
            if (archived) it.copy(isArchived = true, isPinned = false, pinOrder = null) else it.copy(isArchived = false)
        }

        /** Low priority leaves the list and the pinned grid (10.7). */
        suspend fun setLowPriority(
            id: ChatId,
            low: Boolean,
        ) = change(id) {
            if (low) {
                it.copy(
                    isLowPriority = true,
                    isPinned = false,
                    pinOrder = null,
                )
            } else {
                it.copy(isLowPriority = false)
            }
        }

        /** Mutes until [until], or for good when it is null (UI_DESIGN.md 3.4: 1 hour, 8 hours, 1 week, forever). */
        suspend fun muteUntil(
            id: ChatId,
            until: Instant?,
        ) = change(id) { it.copy(isMuted = true, muteUntil = until) }

        /** The name shown for this chat everywhere in PingMe; blank goes back to the network's name (10.15). */
        suspend fun rename(
            id: ChatId,
            name: String,
        ) = change(id) { it.copy(nameOverride = name.trim().ifEmpty { null }) }

        /** Which photo stands for this chat: the contact's or the network's (10.18). */
        suspend fun setAvatarSource(
            id: ChatId,
            source: AvatarSource,
        ) = change(id) { it.copy(avatarSource = source) }

        /** Moves an Instagram chat between Primary and General on the network too (DESIGN.md 6.4). */
        suspend fun moveFolder(
            id: ChatId,
            folder: ChatFolder,
        ) {
            connectorFor(id.accountId).moveFolder(id, folder)
            change(id) { it.copy(folder = folder) }
        }

        suspend fun setObscured(
            id: ChatId,
            obscured: Boolean,
        ) = change(id) { it.copy(isObscured = obscured) }

        /** Opens or makes a chat with [handle] on [accountId] (UI_DESIGN.md 3.1, the + menu). */
        suspend fun startChat(
            accountId: AccountId,
            handle: String,
        ): ChatId = withChatStored(accountId) { it.startConversation(accountId, handle) }

        /** Makes a new group on [accountId] (DESIGN.md 6.2). */
        suspend fun createGroup(
            accountId: AccountId,
            title: String,
            handles: List<String>,
        ): ChatId = withChatStored(accountId) { it.createGroup(accountId, title, handles) }

        /** Accepts or declines a message request (UI_DESIGN.md 6.4). Accepting moves it to Primary. */
        suspend fun respondToRequest(
            id: ChatId,
            accept: Boolean,
        ) {
            connectorFor(id.accountId).respondToRequest(id, accept)
            if (accept) chats.update(id) { it.copy(folder = ChatFolder.PRIMARY) } else chats.delete(id)
        }

        /** Blocks the other side on the network and removes the chat here (UI_DESIGN.md 6.4). */
        suspend fun block(id: ChatId) {
            connectorFor(id.accountId).block(id)
            chats.delete(id)
        }

        /** Runs [create], then stores the new chat at once so it can open even while offline events lag. */
        private suspend fun withChatStored(
            accountId: AccountId,
            create: suspend (Connector) -> ChatId,
        ): ChatId {
            val connector = connectorFor(accountId)
            val id = create(connector)
            applier.applyChats(connector.syncChats(accountId).filter { it.id == id })
            return id
        }

        private suspend fun connectorFor(accountId: AccountId): Connector {
            val network =
                accounts.get(accountId)?.network ?: throw UnsupportedCapabilityException("That account is gone")
            return registry[network] ?: throw UnsupportedCapabilityException("${network.name} is not in this build")
        }

        /** Deletes the chat and its messages from this phone. The network keeps its copy. */
        suspend fun delete(id: ChatId) = chats.delete(id)

        private suspend fun change(
            id: ChatId,
            transform: (Chat) -> Chat,
        ) {
            chats.update(id, transform)
        }

        companion object {
            /** UI_DESIGN.md 3.1, 5.1. */
            const val MAX_PINS = 12
            private const val TAG = "PingMeChatActions"
        }
    }
