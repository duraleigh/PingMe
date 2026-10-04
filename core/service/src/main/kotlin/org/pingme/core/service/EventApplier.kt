// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.accountId
import org.pingme.core.connector.remoteId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.Quote
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

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
        private val people: PeopleApplier,
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
                is ConnectorEvent.Typing -> {
                    typing.set(event.chatId, event.personId, event.typing)
                }

                is ConnectorEvent.ChatUpdated -> {
                    applyChat(event.chat)
                }

                is ConnectorEvent.ChatRemoved -> {
                    // Written to the phone's log: chats vanishing is being chased (owner, 2026-10-04).
                    android.util.Log.i(TAG, "Removed chat ${event.chatId.value} at the network's word")
                    chats.delete(event.chatId)
                }

                is ConnectorEvent.ChatMerged -> {
                    mergeChats(event.from, event.into)
                }

                is ConnectorEvent.State -> {
                    accounts.updateState(event.accountId, event.state)
                }

                is ConnectorEvent.SpaceUpdated -> {
                    applySpace(event.space)
                }

                is ConnectorEvent.PeopleUpdated -> {
                    people.apply(event)
                }

                else -> {
                    Unit
                }
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
                    // A reaction to a message the store never got (older than the history kept, or
                    // in a chat not listed) has nothing to sit on; the row would be refused anyway
                    // (owner, Gate G7: Messenger stuck on "reconnecting" over one such reaction).
                    val target = messages.get(event.messageId) ?: return
                    messages.addReaction(event.messageId, event.reaction)
                    // A reaction is activity: the chat moves up as it does in the network's own app.
                    chats.update(
                        target.chatId,
                    ) { it.copy(lastActivityAt = maxOf(it.lastActivityAt, event.reaction.at)) }
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
            // A chat deleted here has nothing stored, so a history fetch would rebuild it
            // (owner, Gate G7, round 3: deleted chats came back): old pages are dropped.
            val newest = event.messages.maxOfOrNull { it.message.sentAt }
            if (newest != null && hiddenHere(event.chatId, newest)) return
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
            if (hiddenHere(snapshot.id, snapshot.lastActivityAt)) return
            snapshot.participants.forEach { people.remember(it) }
            val existing = chats.get(snapshot.id)
            if (existing != null && existing.folder != snapshot.folder) {
                android.util.Log.i(
                    TAG,
                    "Chat ${snapshot.id.value} '${snapshot.title}' moves ${existing.folder} -> ${snapshot.folder}",
                )
            }
            val merged = existing?.withSnapshot(snapshot) ?: snapshot.toNewChat()
            chats.upsert(merged.copy(title = people.titleFor(snapshot), unreadCount = unreadFor(existing, snapshot)))
        }

        /**
         * The unread count after a network listing. The network's own count is trusted only for
         * a chat PingMe knows nothing about yet: once the chat has been read here, PingMe counts
         * for itself from the messages it holds, and a chat whose newest message is ours is read.
         * Networks whose read marks do not take (or are not sent) kept listing read chats as
         * unread (owner, Gate G7: Google Messages, Telegram, Instagram).
         */
        private suspend fun unreadFor(
            existing: Chat?,
            snapshot: ChatSnapshot,
        ): Int {
            val readUpTo = existing?.readUpTo
            return when {
                existing == null -> snapshot.unreadCount
                readUpTo != null -> messages.incomingSince(snapshot.id, readUpTo)
                messages.newestIsOutgoing(snapshot.id) == true -> 0
                else -> snapshot.unreadCount
            }
        }

        /**
         * A chat the user deleted here stays gone while the network has nothing newer than the
         * deletion; something newer brings it back (owner, Gate G7: deleted chats came back unread).
         */
        private suspend fun hiddenHere(
            chatId: ChatId,
            at: Instant,
        ): Boolean {
            val hiddenAt = chats.hiddenAt(chatId) ?: return false
            if (at <= hiddenAt) {
                android.util.Log.i(TAG, "Dropped for chat ${chatId.value}: hidden here at $hiddenAt, this is from $at")
                return true
            }
            chats.unhide(chatId)
            return false
        }

        /**
         * Two stored chats were one conversation (WhatsApp filed some of it under a hidden
         * id before the number was known): the messages move into the number's chat, which
         * is made from the old one when it does not exist yet, and the old chat goes
         * (owner, Gate G7: split WhatsApp threads).
         */
        private suspend fun mergeChats(
            from: ChatId,
            into: ChatId,
        ) {
            if (from == into) return
            val old = chats.get(from) ?: return
            val target =
                chats.get(into) ?: old.copy(id = into, networkRemoteId = into.remoteId, participants = emptyList())
            chats.upsert(
                target.copy(
                    lastActivityAt = maxOf(target.lastActivityAt, old.lastActivityAt),
                    unreadCount = target.unreadCount + old.unreadCount,
                    isPinned = target.isPinned || old.isPinned,
                    nameOverride = target.nameOverride ?: old.nameOverride,
                    // A membership the old chat had carries over to the number's chat.
                    mergedInto = target.mergedInto ?: old.mergedInto,
                ),
            )
            messages.moveToChat(from, into)
            chats.delete(from)
        }

        /** Your own reactions, echoed back by the network, do not flip the row. */
        private suspend fun announceIfFromSomeoneElse(event: ConnectorEvent.ReactionChanged) {
            val chatId = messages.get(event.messageId)?.chatId ?: return
            if (event.reaction.senderId != messages.selfIn(chatId)) {
                reactionFeed.emit(IncomingReaction(chatId, event.reaction.emoji, event.messageId))
            }
        }

        private suspend fun applyNewMessage(snapshot: MessageSnapshot) {
            if (hiddenHere(snapshot.message.chatId, snapshot.message.sentAt)) return
            // A message the store already has (a bridge handing old messages back after the
            // app reopened) is an update, not news: it must not count as unread again.
            val known = messages.get(snapshot.message.id) != null
            val kept = if (snapshot.message.isOutgoing) withStandInFiles(snapshot) else snapshot
            saveMessage(kept)
            val message = kept.message
            if (message.isOutgoing) retireStandIns(message)
            snapshot.sender?.let { typing.set(message.chatId, it.id, typing = false) }
            chats.update(message.chatId) { chat ->
                val readHere = chat.readUpTo?.let { message.sentAt <= it } ?: false
                val news = !message.isOutgoing && !known && !readHere
                chat.copy(
                    lastActivityAt = maxOf(chat.lastActivityAt, message.sentAt),
                    unreadCount = if (message.isOutgoing) 0 else chat.unreadCount + (if (news) 1 else 0),
                    readUpTo = if (message.isOutgoing) maxOf(chat.lastActivityAt, message.sentAt) else chat.readUpTo,
                    // New activity brings an archived chat back (UI_DESIGN.md 10.7).
                    isArchived = chat.isArchived && message.isOutgoing,
                )
            }
        }

        /**
         * The network's copy of a sent picture names the network's file, not the one on the
         * phone, so the bubble went blank until that file was fetched back (owner, Gate G7).
         * The stand-in's files are already here: the copy takes them over, by position.
         */
        private suspend fun withStandInFiles(snapshot: MessageSnapshot): MessageSnapshot {
            val message = snapshot.message
            if (message.networkRemoteId.startsWith(STAND_IN_PREFIX) || message.attachments.isEmpty()) return snapshot
            val standIn =
                messages
                    .standIns(message.chatId, STAND_IN_PREFIX)
                    .firstOrNull { it.body == message.body && it.attachments.size == message.attachments.size }
                    ?: return snapshot
            val merged =
                message.attachments.mapIndexed { i, a ->
                    if (a.localPath != null) a else a.copy(localPath = standIn.attachments[i].localPath)
                }
            return snapshot.copy(message = message.copy(attachments = merged))
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
            val message = withQuote(snapshot.message)
            if (chats.get(message.chatId) == null && hiddenHere(message.chatId, message.sentAt)) return
            // Through the people step, or a message's sender would overwrite the person's
            // downloaded picture with the network's link again (owner, 2026-10-04: photos gone).
            snapshot.sender?.let { people.remember(it) }
            if (chats.get(message.chatId) == null) {
                // A connector should announce a chat before its messages. If one arrives
                // first, keep the message under a minimal chat until the chat's own
                // snapshot fills it in.
                chats.upsert(placeholderChat(snapshot))
            }
            messages.upsert(message)
        }

        /**
         * A reply from the network names only the message it answers, so the quote shown
         * above it comes from the store (owner, Gate G3: the quote vanished once the network's
         * copy replaced the stand-in). A reply to something not stored yet stays bare.
         */
        private suspend fun withQuote(message: Message): Message {
            val target = message.replyTo?.takeIf { message.quote == null }?.let { messages.get(it) } ?: return message
            val name = if (target.isOutgoing) YOU else contacts.person(target.senderId)?.displayName.orEmpty()
            return message.copy(quote = Quote(name, target.body ?: quoteLabel(target.kind)))
        }

        private fun quoteLabel(kind: MessageKind): String =
            when (kind) {
                MessageKind.TEXT, MessageKind.DELETED -> ""
                MessageKind.VOICE -> "Voice note"
                MessageKind.GIF -> "GIF"
                MessageKind.IMAGE -> "Photo"
                MessageKind.VIDEO -> "Video"
                MessageKind.FILE -> "File"
                MessageKind.LOCATION -> "Location"
                MessageKind.CONTACT -> "Contact"
                MessageKind.STICKER -> "Sticker"
            }

        private fun placeholderChat(snapshot: MessageSnapshot): Chat {
            val message = snapshot.message
            // Your own message (sent from the network's app) says nothing about who the chat
            // is with: no title until the chat's listing comes, never "You" (owner, Gate G7).
            val other = snapshot.sender?.takeIf { !message.isOutgoing }
            return ChatSnapshot(
                id = message.chatId,
                accountId = message.chatId.accountId,
                kind = ChatKind.DIRECT,
                title = other?.displayName ?: "",
                participants = listOfNotNull(other),
                unreadCount = 0,
                lastActivityAt = message.sentAt,
                folder = null,
                spaceId = null,
                networkRemoteId = message.chatId.remoteId,
            ).toNewChat()
        }
    }

/** WhatsApp's hidden user ids end this way; a person is never shown by one. */
const val HIDDEN_ID_SUFFIX = "@lid"

/** Handles that stand for nobody (WhatsApp's "0" user) and must never be a person. */
val PLACEHOLDER_HANDLES = listOf("0@s.whatsapp.net", "+0")

/** How connectors mark the remote id of a stand-in they show while the network's copy is slow. */
const val YOU = "You"
const val STAND_IN_PREFIX = "tmp/"

// A listing never moves a chat earlier: Instagram re-sends a thread with a stale time after
// a reaction or a folder change, and the chat sank to that old place in the list, which
// looked like vanishing while search still found it (owner, 2026-10-04: Jake, Kameron).
private fun Chat.withSnapshot(s: ChatSnapshot) =
    copy(
        kind = s.kind,
        title = s.title,
        participants = s.participants.map { it.id },
        unreadCount = s.unreadCount,
        lastActivityAt = maxOf(lastActivityAt, s.lastActivityAt),
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

private const val TAG = "PingMeApplier"
