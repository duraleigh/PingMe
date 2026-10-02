// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.accountId
import org.pingme.core.connector.remoteId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes what connectors report into the store (BUILD_PLAN.md P1.4). Snapshots carry only
 * what the network knows; everything the user chose locally (pinned, muted, archived, low
 * priority, obscured, name override, merges, avatar source) is kept.
 */
@Singleton
class EventApplier
    @Inject
    constructor(
        private val accounts: AccountRepository,
        private val chats: ChatRepository,
        private val messages: MessageRepository,
        private val contacts: ContactRepository,
        private val typing: TypingTracker,
        private val reactionFeed: ReactionFeed,
        private val tapbacks: Tapbacks,
    ) {
        suspend fun apply(event: ConnectorEvent) {
            when (event) {
                is ConnectorEvent.NewMessage -> applyNewMessage(event.message)
                is ConnectorEvent.MessageUpdated -> saveMessage(event.message)
                is ConnectorEvent.MessageRemoved -> messages.delete(event.messageId)
                is ConnectorEvent.ReactionChanged -> applyReaction(event)
                is ConnectorEvent.ReadReceipt -> messages.markOutgoingRead(event.chatId, event.upTo)
                is ConnectorEvent.MessageRevoked -> applyRevoke(event)
                is ConnectorEvent.MessageEdited -> applyEdit(event)
                is ConnectorEvent.StatusChanged -> messages.updateStatus(event.messageId, event.status)
                is ConnectorEvent.HistoryBatch -> applyHistory(event)
                else -> applyChatEvent(event)
            }
        }

        // Chats, typing, spaces, and the account's state: everything that is not a message.
        private suspend fun applyChatEvent(event: ConnectorEvent) {
            when (event) {
                is ConnectorEvent.Typing -> typing.set(event.chatId, event.personId, event.typing)
                is ConnectorEvent.ChatUpdated -> applyChat(event.chat)
                is ConnectorEvent.ChatRemoved -> chats.delete(event.chatId)
                is ConnectorEvent.State -> accounts.updateState(event.accountId, event.state)
                is ConnectorEvent.SpaceUpdated -> applySpace(event.space)
                else -> Unit
            }
        }

        /** A reaction taken away with no emoji named (WhatsApp) drops whatever that sender had on it. */
        private suspend fun applyReaction(event: ConnectorEvent.ReactionChanged) {
            when {
                event.removed && event.reaction.emoji.isEmpty() -> {
                    messages.removeReactions(event.messageId, event.reaction.senderId)
                }

                event.removed -> {
                    messages.removeReaction(event.messageId, event.reaction.senderId, event.reaction.emoji)
                }

                else -> {
                    messages.addReaction(event.messageId, event.reaction)
                    announceIfFromSomeoneElse(event)
                }
            }
        }

        /** Deleted for everyone: the text and files go, "This message was deleted" stays (UI_DESIGN.md 5.3). */
        private suspend fun applyRevoke(event: ConnectorEvent.MessageRevoked) {
            val message = messages.get(event.messageId) ?: return
            messages.upsert(
                message.copy(
                    body = null,
                    kind = org.pingme.core.model.MessageKind.DELETED,
                    attachments = emptyList(),
                    deletedForEveryone = true,
                ),
            )
        }

        private suspend fun applyEdit(event: ConnectorEvent.MessageEdited) {
            val message = messages.get(event.messageId) ?: return
            messages.upsert(message.copy(body = event.body, editedAt = event.editedAt))
        }

        /** A network space; the user's own choices for it (icon, whether it shows in All) stay. */
        private suspend fun applySpace(space: org.pingme.core.model.Space) {
            val own = chats.space(space.id)
            chats.upsertSpace(own?.let { space.copy(icon = it.icon, showInAll = it.showInAll) } ?: space)
        }

        /**
         * Older messages land first, then the reaction texts among them become reactions on
         * what they refer to, which is now in the store (iPhone tapbacks over SMS; owner, Gate G3).
         */
        private suspend fun applyHistory(event: ConnectorEvent.HistoryBatch) {
            val (reactions, plain) = event.messages.partition { Tapbacks.parse(it.message.body.orEmpty()) != null }
            plain.forEach { saveMessage(it) }
            reactions.forEach { snapshot ->
                when (val reaction = tapbacks.asReaction(snapshot.message)) {
                    null -> saveMessage(snapshot)
                    else -> apply(reaction)
                }
            }
        }

        /** Applies a full chat list from a sync. */
        suspend fun applyChats(snapshots: List<ChatSnapshot>) = snapshots.forEach { applyChat(it) }

        private suspend fun applyChat(snapshot: ChatSnapshot) {
            snapshot.participants.forEach { contacts.upsert(it) }
            val existing = chats.get(snapshot.id)
            chats.upsert(existing?.withSnapshot(snapshot) ?: snapshot.toNewChat())
        }

        /** Your own reactions, echoed back by the network, do not flip the row. */
        private suspend fun announceIfFromSomeoneElse(event: ConnectorEvent.ReactionChanged) {
            val chatId = messages.get(event.messageId)?.chatId ?: return
            if (event.reaction.senderId != messages.selfIn(chatId)) {
                reactionFeed.emit(IncomingReaction(chatId, event.reaction.emoji, event.messageId))
            }
        }

        private suspend fun applyNewMessage(snapshot: MessageSnapshot) {
            saveMessage(snapshot)
            val message = snapshot.message
            if (message.isOutgoing) retireStandIns(message)
            snapshot.sender?.let { typing.set(message.chatId, it.id, typing = false) }
            chats.update(message.chatId) { chat ->
                chat.copy(
                    lastActivityAt = maxOf(chat.lastActivityAt, message.sentAt),
                    unreadCount = if (message.isOutgoing) 0 else chat.unreadCount + 1,
                    // New activity brings an archived chat back (UI_DESIGN.md 10.7).
                    isArchived = chat.isArchived && message.isOutgoing,
                )
            }
        }

        /**
         * The network's own copy of a sent message has come: a stand-in the connector showed
         * for it (same chat, same text, same number of files) goes, even when the connector
         * itself has forgotten the send, as after a restart (owner, Gate G3: a scheduled SMS
         * showed twice).
         */
        private suspend fun retireStandIns(message: Message) {
            if (message.networkRemoteId.startsWith(STAND_IN_PREFIX)) return
            messages
                .standIns(message.chatId, STAND_IN_PREFIX)
                .filter { it.body == message.body && it.attachments.size == message.attachments.size }
                .forEach { messages.delete(it.id) }
        }

        private suspend fun saveMessage(snapshot: MessageSnapshot) {
            val message = snapshot.message
            snapshot.sender?.let { contacts.upsert(it) }
            if (chats.get(message.chatId) == null) {
                // A connector should announce a chat before its messages. If one arrives
                // first, keep the message under a minimal chat until the chat's own
                // snapshot fills it in.
                chats.upsert(placeholderChat(snapshot))
            }
            messages.upsert(message)
        }

        private fun placeholderChat(snapshot: MessageSnapshot): Chat {
            val message = snapshot.message
            return ChatSnapshot(
                id = message.chatId,
                accountId = message.chatId.accountId,
                kind = ChatKind.DIRECT,
                title = snapshot.sender?.displayName ?: "",
                participants = listOfNotNull(snapshot.sender),
                unreadCount = 0,
                lastActivityAt = message.sentAt,
                folder = null,
                spaceId = null,
                networkRemoteId = message.chatId.remoteId,
            ).toNewChat()
        }
    }

/** How connectors mark the remote id of a stand-in they show while the network's copy is slow. */
const val STAND_IN_PREFIX = "tmp/"

private fun Chat.withSnapshot(s: ChatSnapshot) =
    copy(
        kind = s.kind,
        title = s.title,
        participants = s.participants.map { it.id },
        unreadCount = s.unreadCount,
        lastActivityAt = s.lastActivityAt,
        folder = s.folder,
        spaceId = s.spaceId,
        networkRemoteId = s.networkRemoteId,
    )

private fun ChatSnapshot.toNewChat() =
    Chat(
        id = id,
        accountId = accountId,
        kind = kind,
        title = title,
        participants = participants.map { it.id },
        unreadCount = unreadCount,
        lastActivityAt = lastActivityAt,
        isPinned = false,
        pinOrder = null,
        isMuted = false,
        muteUntil = null,
        isArchived = false,
        isLowPriority = false,
        isObscured = false,
        folder = folder,
        spaceId = spaceId,
        mergedInto = null,
        avatarSource = AvatarSource.Contacts,
        nameOverride = null,
        defaultSendAccount = null,
        networkRemoteId = networkRemoteId,
    )
