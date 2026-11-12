// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable

/**
 * A person as seen by one network account. Linked to a phone contact by [contactId]
 * (UI_DESIGN.md 10.18): the link is made on the phone, never sent by a network, and the
 * contact's name and photo ride along so every screen can show them.
 */
@Serializable
data class Person(
    val id: PersonId,
    val accountId: AccountId,
    val displayName: String,
    val phoneNumber: String?,
    val networkHandle: String,
    val avatarPath: String?,
    val contactId: ContactId?,
    /** The matched contact's name, when the phone's address book has this person. */
    val contactName: String? = null,
    /** The matched contact's photo, as a content address the phone can load. */
    val contactPhoto: String? = null,
) {
    /** The name to show: the contact's first, then the network's (UI_DESIGN.md 10.18). */
    val name: String get() = contactName ?: displayName

    /** The photo to show: the contact's first, then the network's profile photo. */
    val photo: String? get() = contactPhoto ?: avatarPath
}

/** A named group of chats shown as one unit (UI_DESIGN.md 10.4). */
@Serializable
data class Space(
    val id: SpaceId,
    /**
     * The account a network space belongs to. Null for a [SpaceKind.CUSTOM] space, which the
     * user can build "from any chats", including chats on different accounts.
     */
    val accountId: AccountId?,
    val title: String,
    val kind: SpaceKind,
    val chatIds: List<ChatId>,
    /** Shown in the bottom bar and the avatar menu; the user picks it for a space they made. */
    val icon: SpaceIcon = SpaceIcon.SPACE,
    /** True: its chats also show in All. False: only inside the space (UI_DESIGN.md 10.4). */
    val showInAll: Boolean = true,
)

/** The icons a space can have (UI_DESIGN.md 10.4). */
@Serializable
enum class SpaceIcon {
    SPACE,
    HOME,
    WORK,
    FAMILY,
    FRIENDS,
    SCHOOL,
    SPORT,
    FITNESS,
    TRAVEL,
    HEART,
    STAR,
    PARTY,
    SHOPPING,
    FOOD,
    MUSIC,
    BOOKS,
    PETS,
    CHAT,
}

@Serializable
enum class SpaceKind {
    WHATSAPP_COMMUNITY,
    TELEGRAM_FORUM,
    CUSTOM,
}
