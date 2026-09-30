// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable

/** PingMe's own identifiers. Every record also keeps the network's own ID (DESIGN.md 6.3). */

@JvmInline
@Serializable
value class AccountId(
    val value: String,
)

@JvmInline
@Serializable
value class ChatId(
    val value: String,
)

@JvmInline
@Serializable
value class MessageId(
    val value: String,
)

@JvmInline
@Serializable
value class AttachmentId(
    val value: String,
)

@JvmInline
@Serializable
value class PersonId(
    val value: String,
)

/** A person in the phone's contacts provider (ContactsContract), UI_DESIGN.md 10.18. */
@JvmInline
@Serializable
value class ContactId(
    val value: String,
)

@JvmInline
@Serializable
value class SpaceId(
    val value: String,
)
