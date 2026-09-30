// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable

/** A person as seen by one network account. Linked to a phone contact by [contactId]. */
@Serializable
data class Person(
    val id: PersonId,
    val accountId: AccountId,
    val displayName: String,
    val phoneNumber: String?,
    val networkHandle: String,
    val avatarPath: String?,
    val contactId: ContactId?,
)

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
)

@Serializable
enum class SpaceKind {
    WHATSAPP_COMMUNITY,
    TELEGRAM_FORUM,
    CUSTOM,
}
