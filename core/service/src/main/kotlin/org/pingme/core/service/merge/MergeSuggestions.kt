// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.service.people.Numbers
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Why two chats look like the same person. */
enum class MergeReason { CONTACT, NUMBER, NAME }

/** Chats on different accounts that look like one person; the user decides (UI_DESIGN.md 10.15). */
data class MergeSuggestion(
    val chats: List<Chat>,
    val reasons: Set<MergeReason>,
    /** Phone contacts offered as Google Messages chats to merge with (owner, 2026-10-04). */
    val contacts: List<ContactOffer> = emptyList(),
) {
    /** Stable for this set of chats: dismissing it hides exactly this set, a new member brings it back. */
    val key: String get() = keyOf(chats.map { it.id.value } + contacts.map { it.chatId.value })

    companion object {
        fun keyOf(ids: List<String>) = ids.sorted().joinToString("\n")
    }
}

/**
 * Proposes merges (owner, Phase 7): one-to-one chats on different accounts whose person
 * shares a phone contact, a phone number, or a name (or username) that reads the same.
 * PingMe never merges on its own; every suggestion can be edited, dismissed, or confirmed.
 */
@Singleton
class MergeSuggestions
    @Inject
    constructor(
        chats: ChatRepository,
        contacts: ContactRepository,
        settings: SettingsRepository,
        contactChats: ContactChats,
    ) {
        /**
         * The suggestions and the contact offers they and the pickers draw on. Computed off
         * the main thread: on it, matching every chat against every contact froze the app
         * (owner, 2026-10-04, 7:57 PM: "PingMe isn't responding").
         */
        val proposals: Flow<Proposals> =
            combine(chats.all(), contacts.inChats(), settings.dismissedMerges) { all, people, dismissed ->
                val offers = contactChats.offers()
                val found = MergeMatcher.suggest(all, people, offers).filter { it.key !in dismissed }
                Proposals(found, offers)
            }.flowOn(Dispatchers.Default)

        val suggestions: Flow<List<MergeSuggestion>> = proposals.map { it.suggestions }
    }

data class Proposals(
    val suggestions: List<MergeSuggestion>,
    val offers: Offers,
)

/** The matching itself, pure, so it can be tested as such. */
object MergeMatcher {
    private const val YOU = "You"

    fun suggest(
        all: List<Chat>,
        people: Map<PersonId, Person>,
        offers: Offers = Offers(),
    ): List<MergeSuggestion> {
        val parents = all.mapNotNull { it.mergedInto }.toSet()
        val candidates = all.filter { it.kind == ChatKind.DIRECT && it.mergedInto == null && it.id !in parents }
        val groups = Clusters<Chat>()
        val byKey = HashMap<Pair<MergeReason, String>, Chat>()
        val keys = HashMap<ChatId, List<Pair<MergeReason, String>>>()
        candidates.forEach { chat ->
            keysOf(chat, people[other(chat, people)]).also { keys[chat.id] = it }.forEach { key ->
                val first = byKey.putIfAbsent(key, chat)
                if (first != null) groups.join(first, chat, key.first)
            }
        }
        val clustered = groups.all()
        val index = OfferIndex(offers)
        val inCluster = clustered.flatMap { (members, _) -> members.map { it.id } }.toSet()
        // A chat on its own, or a cluster, with no Google Messages member yet: a phone contact
        // with that person's contact card or name is offered as the text chat to merge with.
        val lone = candidates.filter { it.id !in inCluster }.map { listOf(it) to emptySet<MergeReason>() }
        return (clustered + lone).mapNotNull { (members, reasons) ->
            val contacts =
                if (members.none { it.accountId == offers.account }) {
                    index.offered(members.flatMap { keys[it.id].orEmpty() })
                } else {
                    emptyList()
                }
            val accountsIn = members.map { it.accountId }.distinct().size + (if (contacts.isEmpty()) 0 else 1)
            if (accountsIn < 2) return@mapNotNull null
            val why = reasons + contacts.map { it.reason }
            MergeSuggestion(members.sortedBy { it.id.value }, why, contacts.map { it.offer })
        }
    }

    private class Offered(
        val offer: ContactOffer,
        val reason: MergeReason,
    )

    /** The offers indexed once by contact card and by name key, so each chat's lookup is cheap. */
    private class OfferIndex(
        private val offers: Offers,
    ) {
        private val byContact = HashMap<String, MutableList<ContactOffer>>()
        private val byName = HashMap<String, MutableList<ContactOffer>>()

        init {
            if (offers.account != null) {
                offers.list.forEach { offer ->
                    offer.contactId?.let { byContact.getOrPut(it.value) { ArrayList() } += offer }
                    NameKey.of(offer.name)?.let { byName.getOrPut(it) { ArrayList() } += offer }
                }
            }
        }

        /** Offers whose contact card is the person's, else whose name reads the same (one per contact). */
        fun offered(keys: List<Pair<MergeReason, String>>): List<Offered> {
            if (offers.account == null) return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<Offered>()

            fun take(
                offer: ContactOffer,
                reason: MergeReason,
            ) {
                if (seen.add(offer.contactId?.value ?: offer.digits)) out += Offered(offer, reason)
            }
            keys.forEach { (reason, key) ->
                when (reason) {
                    MergeReason.CONTACT -> byContact[key]?.forEach { take(it, MergeReason.CONTACT) }
                    MergeReason.NAME -> byName[key]?.forEach { take(it, MergeReason.NAME) }
                    MergeReason.NUMBER -> Unit
                }
            }
            return out
        }
    }

    private fun other(
        chat: Chat,
        people: Map<PersonId, Person>,
    ): PersonId? = chat.participants.firstOrNull { people[it]?.displayName != YOU }

    private fun keysOf(
        chat: Chat,
        person: Person?,
    ): List<Pair<MergeReason, String>> {
        val keys = ArrayList<Pair<MergeReason, String>>()
        person?.contactId?.let { keys += MergeReason.CONTACT to it.value }
        Numbers.digits(person?.phoneNumber)?.let { digits ->
            keys += MergeReason.NUMBER to (Numbers.national(digits) ?: digits)
        }
        val names =
            listOfNotNull(
                chat.nameOverride,
                chat.title,
                person?.contactName,
                person?.displayName,
                person?.networkHandle,
            )
        names.mapNotNull { NameKey.of(it) }.distinct().forEach { keys += MergeReason.NAME to it }
        return keys
    }
}

/** Union-find over chats, remembering why each pair was joined. */
private class Clusters<T> {
    private val parent = HashMap<T, T>()
    private val reasons = HashMap<T, MutableSet<MergeReason>>()

    fun join(
        a: T,
        b: T,
        reason: MergeReason,
    ) {
        val ra = root(a)
        val rb = root(b)
        if (ra != rb) parent[rb] = ra
        val merged = reasons.getOrPut(root(a)) { HashSet() }
        merged += reason
        if (ra != rb) reasons.remove(rb)?.let { merged += it }
    }

    fun all(): List<Pair<List<T>, Set<MergeReason>>> =
        parent.keys
            .groupBy { root(it) }
            .filter { (_, members) -> members.size >= 2 }
            .map { (root, members) -> members to reasons[root].orEmpty() }

    private fun root(x: T): T {
        var current = parent.getOrPut(x) { x }
        while (parent[current] != current) current = parent.getValue(current)
        return current
    }
}
