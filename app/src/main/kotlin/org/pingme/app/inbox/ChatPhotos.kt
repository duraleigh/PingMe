// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId

/**
 * The photo that stands for a one-to-one chat (UI_DESIGN.md 10.18): the other person's
 * contact photo first, then their network profile photo, as the chat's avatar source says.
 * Groups and people with neither get the initials tile.
 */
fun photoFor(
    chat: Chat,
    people: Map<PersonId, Person>,
): String? {
    if (chat.kind != ChatKind.DIRECT) return null
    val other = chat.participants.mapNotNull { people[it] }.firstOrNull { it.displayName != YOU } ?: return null
    return when (val source = chat.avatarSource) {
        AvatarSource.Contacts -> other.contactPhoto ?: other.avatarPath
        is AvatarSource.Network -> other.avatarPath.takeIf { source.accountId == other.accountId } ?: other.contactPhoto
        AvatarSource.Initials -> null
    }
}

private const val YOU = "You"
