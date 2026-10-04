// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.service.Names
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MergeRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** A merge the user asked for cannot be made: the reason, in plain words for a notice. */
class MergeRefusedException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Merging chats (UI_DESIGN.md 10.15; owner, Phase 7): any one-to-one chats across any
 * networks become one merged chat, a chat row of its own that the members point at. PingMe
 * only ever merges on the user's word: suggestions are made elsewhere and confirmed here.
 */
@Singleton
class Merges
    @Inject
    constructor(
        private val chats: ChatRepository,
        private val merges: MergeRepository,
        private val contacts: ContactRepository,
    ) {
        /**
         * Merges [ids] (chats, members of merged chats, or merged chats themselves, which bring
         * their members) into one merged chat: [into] when it is one already, otherwise the
         * first merged chat among them, otherwise a new one. Returns the merged chat's id.
         */
        suspend fun merge(
            ids: List<ChatId>,
            into: ChatId? = null,
        ): ChatId {
            val members = resolve(ids)
            if (members.size < 2) throw MergeRefusedException("Pick at least two chats to merge.")
            val parents = ids.mapNotNull { chats.get(it) }.filter { isMerged(it) }
            val parent =
                parents.firstOrNull { it.id == into }
                    ?: parents.firstOrNull()
                    ?: newParent(members).also { chats.upsert(it) }
            members.forEach { merges.setMergedInto(it.id, parent.id) }
            shareContact(members)
            val everyone = members.flatMap { m -> m.participants }
            chats.update(parent.id) { it.copy(participants = (it.participants + everyone).distinct()) }
            return parent.id
        }

        /** Takes one member out of its merged chat; a merged chat left with one member dissolves. */
        suspend fun split(member: ChatId) = merges.setMergedInto(member, null)

        /** The account the composer starts on in a merged chat (UI_DESIGN.md 10.15). */
        suspend fun setDefault(
            merged: ChatId,
            account: AccountId,
        ) {
            chats.update(merged) { it.copy(defaultSendAccount = account, accountId = account) }
        }

        /** True for a merged chat: a chat other chats point at. */
        suspend fun isMerged(chat: Chat): Boolean = merges.members(chat.id).isNotEmpty()

        private suspend fun resolve(ids: List<ChatId>): List<Chat> {
            val found = LinkedHashMap<ChatId, Chat>()
            ids.forEach { id ->
                val chat = chats.get(id) ?: return@forEach
                val parent = chat.mergedInto
                val group =
                    if (parent != null) merges.members(parent) else merges.members(chat.id).ifEmpty { listOf(chat) }
                group.forEach { found[it.id] = it }
            }
            found.values.firstOrNull { it.kind == ChatKind.GROUP }?.let {
                throw MergeRefusedException("Group chats cannot be merged (${it.title}).")
            }
            return found.values.toList()
        }

        private suspend fun newParent(members: List<Chat>): Chat {
            val people = members.flatMap { m -> m.participants.mapNotNull { contacts.person(it) } }
            val named = people.firstOrNull { it.contactName != null }
            val lead = members.firstOrNull { m -> m.participants.any { it == named?.id } } ?: members.first()
            val title =
                named?.contactName
                    ?: members.map { it.nameOverride ?: it.title }.firstOrNull { Names.isReal(it) }
                    ?: lead.title
            return Chat(
                id = ChatId("$PREFIX${UUID.randomUUID()}"),
                accountId = lead.accountId,
                kind = ChatKind.DIRECT,
                title = title,
                participants = members.flatMap { it.participants }.distinct(),
                unreadCount = members.sumOf { it.unreadCount },
                lastActivityAt = members.maxOf { it.lastActivityAt },
                isPinned = members.any { it.isPinned },
                pinOrder = members.mapNotNull { it.pinOrder }.minOrNull(),
                isMuted = false,
                muteUntil = null,
                isArchived = false,
                isLowPriority = false,
                isObscured = members.any { it.isObscured },
                folder = null,
                spaceId = null,
                mergedInto = null,
                avatarSource = AvatarSource.Contacts,
                nameOverride = null,
                defaultSendAccount = lead.accountId,
                networkRemoteId = REMOTE,
                readUpTo = members.mapNotNull { it.readUpTo }.maxOrNull() ?: Instant.DISTANT_PAST,
            )
        }

        // A member without a contact link takes the one the others have: the merged person is one person.
        private suspend fun shareContact(members: List<Chat>) {
            val people = members.flatMap { m -> m.participants.mapNotNull { contacts.person(it) } }
            val linked = people.firstOrNull { it.contactId != null } ?: return
            people.filter { it.contactId == null && it.displayName != YOU }.forEach { person ->
                contacts.link(person.id, linked.contactId, linked.contactName, linked.contactPhoto)
            }
        }

        companion object {
            /** Merged chats belong to no network: their id says so, and no connector is ever asked about one. */
            const val PREFIX = "merged/"
            const val REMOTE = "merged"
            private const val YOU = "You"

            fun isMergedId(id: ChatId) = id.value.startsWith(PREFIX)
        }
    }
