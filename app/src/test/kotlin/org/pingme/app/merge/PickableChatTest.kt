// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ContactId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import kotlin.time.Instant

/**
 * A merge suggestion offers each member's own picture to stand for the merged chat (owner,
 * 2026-11-07: Instagram pictures never showed as choices).
 */
class PickableChatTest {
    @Test
    fun anInstagramChatOffersItsInstagramPictureEvenWhenThePersonHasAContactPhoto() {
        val ig = AccountId("instagram-1")
        val merle =
            Person(
                id = PersonId("instagram-1/merle"),
                accountId = ig,
                displayName = "Merle Dandridge",
                phoneNumber = null,
                networkHandle = "merle",
                avatarPath = "/files/avatars/merle-instagram",
                contactId = ContactId("42"),
                contactName = "Merle Dandridge",
                contactPhoto = "content://contacts/42/photo",
            )
        val chat =
            Chat(
                id = ChatId("instagram-1/thread"),
                accountId = ig,
                kind = ChatKind.DIRECT,
                title = "Merle Dandridge",
                participants = listOf(merle.id),
                unreadCount = 0,
                lastActivityAt = Instant.fromEpochSeconds(0),
                isPinned = false,
                pinOrder = null,
                isMuted = false,
                muteUntil = null,
                isArchived = false,
                isLowPriority = false,
                isObscured = false,
                folder = null,
                spaceId = null,
                mergedInto = null,
                avatarSource = AvatarSource.Contacts,
                nameOverride = null,
                defaultSendAccount = null,
                networkRemoteId = "thread",
            )

        val offered = PickableChat.of(chat, NetworkId.INSTAGRAM, mapOf(merle.id to merle))

        assertEquals("/files/avatars/merle-instagram", offered.photo)
    }
}
