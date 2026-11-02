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
    val source = chat.avatarSource
    return when {
        source is AvatarSource.Picture -> source.path
        chat.kind != ChatKind.DIRECT -> null
        else -> directPhoto(source, chat, people)
    }
}

private fun directPhoto(
    source: AvatarSource,
    chat: Chat,
    people: Map<PersonId, Person>,
): String? {
    // A merged chat lists the person once per network; the source says whose picture counts.
    val others = chat.participants.mapNotNull { people[it] }.filter { it.displayName != YOU }
    val other = others.firstOrNull()
    return when {
        other == null || source == AvatarSource.Initials || source is AvatarSource.Picture -> {
            null
        }

        source is AvatarSource.Network -> {
            val onNetwork = others.firstOrNull { it.accountId == source.accountId } ?: other
            onNetwork.avatarPath ?: onNetwork.contactPhoto
        }

        else -> {
            others.firstNotNullOfOrNull { it.contactPhoto } ?: others.firstNotNullOfOrNull { it.avatarPath }
        }
    }
}

/** A group's members, not counting you, as faces for its composite avatar (owner, 2026-10-03). */
fun facesFor(
    chat: Chat,
    people: Map<PersonId, Person>,
): List<org.pingme.core.ui.components.Face> {
    // A picked picture stands in for the members' faces.
    if (chat.kind != ChatKind.GROUP || chat.avatarSource is AvatarSource.Picture) return emptyList()
    return chat.participants
        .mapNotNull { people[it] }
        .filter { it.displayName != YOU }
        .map {
            org.pingme.core.ui.components
                .Face(it.name, it.photo)
        }
}

private const val YOU = "You"
