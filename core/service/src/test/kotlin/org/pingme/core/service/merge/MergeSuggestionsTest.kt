// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ContactId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import kotlin.time.Instant

/** Who looks like the same person across networks (owner, Phase 7). */
class MergeSuggestionsTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val people = HashMap<PersonId, Person>()

    private fun person(
        account: String,
        name: String,
        phone: String? = null,
        handle: String = name,
        contact: String? = null,
    ): Person =
        Person(PersonId("$account/$handle"), AccountId(account), name, phone, handle, null, contact?.let(::ContactId))
            .also { people[it.id] = it }

    private fun chat(
        account: String,
        title: String,
        other: Person,
        kind: ChatKind = ChatKind.DIRECT,
        mergedInto: ChatId? = null,
    ) = Chat(
        id = ChatId("$account/${other.networkHandle}"),
        accountId = AccountId(account),
        kind = kind,
        title = title,
        participants = listOf(other.id),
        unreadCount = 0,
        lastActivityAt = now,
        isPinned = false,
        pinOrder = null,
        isMuted = false,
        muteUntil = null,
        isArchived = false,
        isLowPriority = false,
        isObscured = false,
        folder = null,
        spaceId = null,
        mergedInto = mergedInto,
        avatarSource = AvatarSource.Contacts,
        nameOverride = null,
        defaultSendAccount = null,
        networkRemoteId = other.networkHandle,
    )

    private fun suggest(vararg chats: Chat) = MergeMatcher.suggest(chats.toList(), people)

    private val textOffers =
        Offers(
            listOf(
                ContactOffer("Jake Smith", "+15550001", "15550001", ContactId("c-jake"), null),
                ContactOffer("Sam Ortiz", "+15550002", "15550002", ContactId("c-sam"), null),
            ),
            AccountId("gm"),
        )

    // Owner, 2026-10-04: a phone contact with a number is a Google Messages chat to merge
    // with, even when no text has ever been sent to it.
    @Test
    fun aContactWithANumberIsOfferedAsATextChatByCardOrByName() {
        val jake = person("ig", "Jake Smith", handle = "jakes")
        val sam = person("ig", "sam.ortiz", handle = "sam.ortiz", contact = "c-sam")
        val noOne = person("ig", "Nobody Known", handle = "nobody")
        val found =
            MergeMatcher.suggest(
                listOf(chat("ig", "Jake Smith", jake), chat("ig", "sam.ortiz", sam), chat("ig", "Nobody Known", noOne)),
                people,
                textOffers,
            )
        assertEquals(2, found.size)
        val byName = found.first { it.chats.single().title == "Jake Smith" }
        assertEquals("15550001", byName.contacts.single().digits)
        assertEquals(setOf(MergeReason.NAME), byName.reasons)
        val byCard = found.first { it.chats.single().title == "sam.ortiz" }
        assertEquals("15550002", byCard.contacts.single().digits)
        assertEquals(setOf(MergeReason.CONTACT), byCard.reasons)
        assertEquals(ChatId("contact/15550002"), byCard.contacts.single().chatId)
    }

    @Test
    fun aClusterThatAlreadyHasATextChatGetsNoOffer() {
        val jakeIg = person("ig", "Jake Smith", handle = "jakes")
        val jakeGm = person("gm", "Jake Smith", phone = "+15550009", handle = "+15550009")
        val found =
            MergeMatcher.suggest(
                listOf(chat("ig", "Jake Smith", jakeIg), chat("gm", "Jake Smith", jakeGm)),
                people,
                textOffers,
            )
        assertEquals(1, found.size)
        assertTrue(found.single().contacts.isEmpty())
    }

    @Test
    fun theSameNumberOnTwoNetworksIsSuggested() {
        val rcs = chat("rcs", "Sam Ortiz", person("rcs", "Sam Ortiz", "+15555550123", "+15555550123"))
        val wa = chat("wa", "+1 (555) 555-0123", person("wa", "+15555550123", "+15555550123", "+15555550123"))
        val stranger = chat("wa", "+15555559999", person("wa", "+15555559999", "+15555559999", "+15555559999"))
        val found = suggest(rcs, wa, stranger)
        assertEquals(1, found.size)
        assertEquals(
            setOf(rcs.id, wa.id),
            found
                .single()
                .chats
                .map { it.id }
                .toSet(),
        )
        assertTrue(MergeReason.NUMBER in found.single().reasons)
    }

    @Test
    fun theSameNameOrUsernameIsSuggestedAcrossNetworks() {
        val wa = chat("wa", "Sam Ortiz", person("wa", "Sam Ortiz", "+15555550123", "+15555550123"))
        val ig = chat("ig", "Sam Ortiz 🌟", person("ig", "Sam Ortiz 🌟", handle = "sam.ortiz"))
        val tg = chat("tg", "sam_ortiz", person("tg", "sam_ortiz", handle = "sam_ortiz"))
        val found = suggest(wa, ig, tg)
        assertEquals(1, found.size)
        assertEquals(3, found.single().chats.size)
        assertEquals(setOf(MergeReason.NAME), found.single().reasons)
    }

    @Test
    fun twoChatsOnTheSameAccountAreNeverASuggestionOnTheirOwn() {
        val a = chat("ig", "Sam Ortiz", person("ig", "Sam Ortiz", handle = "sam.ortiz"))
        val b = chat("ig", "Sam Ortiz", person("ig", "Sam Ortiz", handle = "samortiz2"))
        assertTrue(suggest(a, b).isEmpty())
    }

    @Test
    fun groupsMembersAndMergedChatsStayOut() {
        val sam = person("wa", "Sam Ortiz", "+15555550123", "+15555550123")
        val group = chat("wa", "Sam Ortiz", sam, kind = ChatKind.GROUP)
        val onRcs = person("rcs", "Sam Ortiz", "+15555550123", "+15555550123")
        val member = chat("rcs", "Sam Ortiz", onRcs, mergedInto = ChatId("merged/1"))
        val other = chat("tg", "Sam Ortiz", person("tg", "Sam Ortiz", handle = "samo"))
        assertTrue(suggest(group, member, other).isEmpty())
    }

    @Test
    fun aSharedPhoneContactIsEnoughOnItsOwn() {
        val ig = chat("ig", "sunny", person("ig", "sunny", handle = "sunny", contact = "lookup-7"))
        val onWa = person("wa", "+15555550123", "+15555550123", "+15555550123", contact = "lookup-7")
        val wa = chat("wa", "+15555550123", onWa)
        val found = suggest(ig, wa)
        assertEquals(setOf(MergeReason.CONTACT), found.single().reasons)
    }

    @Test
    fun theKeyNamesExactlyThisSetOfChats() {
        val rcs = chat("rcs", "Sam Ortiz", person("rcs", "Sam Ortiz", "+15555550123", "+15555550123"))
        val wa = chat("wa", "Sam Ortiz", person("wa", "Sam Ortiz", "+15555550123", "+15555550123"))
        val found = suggest(rcs, wa).single()
        assertEquals(MergeSuggestion.keyOf(listOf(wa.id.value, rcs.id.value)), found.key)
    }

    @Test
    fun nameKeysDropWhatNetworksDecorate() {
        assertEquals("sam ortiz", NameKey.of("Sam Ortiz 🌟"))
        assertEquals("sam ortiz", NameKey.of("sam.ortiz"))
        assertEquals("sam ortiz", NameKey.of("SAM_ORTIZ"))
        assertEquals("jose nunez", NameKey.of("José Núñez"))
        assertEquals("salvatore altamore", NameKey.of("Salvatore Altamore | Stylist"))
        assertEquals("sam", NameKey.of("Sam (work)"))
        assertNull(NameKey.of("+15555550123"))
        assertNull(NameKey.of("Jo"))
    }
}
