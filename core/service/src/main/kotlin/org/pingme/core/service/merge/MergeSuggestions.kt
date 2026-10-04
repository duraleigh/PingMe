// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.pingme.core.model.Chat
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
) {
    /** Stable for this set of chats: dismissing it hides exactly this set, a new member brings it back. */
    val key: String get() = keyOf(chats.map { it.id.value })

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
    ) {
        val suggestions: Flow<List<MergeSuggestion>> =
            combine(chats.all(), contacts.inChats(), settings.dismissedMerges) { all, people, dismissed ->
                MergeMatcher.suggest(all, people).filter { it.key !in dismissed }
            }
    }

/** The matching itself, pure, so it can be tested as such. */
object MergeMatcher {
    private const val YOU = "You"

    fun suggest(
        all: List<Chat>,
        people: Map<PersonId, Person>,
    ): List<MergeSuggestion> {
        val parents = all.mapNotNull { it.mergedInto }.toSet()
        val candidates = all.filter { it.kind == ChatKind.DIRECT && it.mergedInto == null && it.id !in parents }
        val groups = Clusters<Chat>()
        val byKey = HashMap<Pair<MergeReason, String>, Chat>()
        candidates.forEach { chat ->
            keysOf(chat, people[other(chat, people)]).forEach { key ->
                val first = byKey.putIfAbsent(key, chat)
                if (first != null) groups.join(first, chat, key.first)
            }
        }
        return groups
            .all()
            .filter { (members, _) -> members.map { it.accountId }.distinct().size >= 2 }
            .map { (members, reasons) -> MergeSuggestion(members.sortedBy { it.id.value }, reasons) }
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
