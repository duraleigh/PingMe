// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import org.pingme.core.connector.Diag
import org.pingme.core.model.Chat
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId

/**
 * Every merged chat's photo and where it comes from, and every chat showing a photo that
 * chats with several different names share, to the diagnostic file: many chats showed the
 * owner's own photo, and nothing on screen says which person inside them carries it
 * (owner, 2026-11-09).
 */
internal fun notePhotos(
    chats: List<Chat>,
    people: Map<PersonId, Person>,
) {
    // A photo shared by chats with several different names is one person's photo on others.
    val shown = chats.associateWith { photoFor(it, people) }
    val shared =
        shown.entries
            .filter { it.value != null }
            .groupBy({ it.value }, { it.key.title })
            .filterValues { titles -> titles.distinct().size >= SHARED_BY }
            .keys
    Diag.note(TAG, "Photos shown on $SHARED_BY or more differently named chats: $shared")
    chats.filter { it.id.value.startsWith(MERGED) || shown[it] in shared }.forEach { chat ->
        val shows = photoFor(chat, people)
        Diag.note(TAG, "'${chat.title.take(TITLE)}' ${chat.id.value} source=${chat.avatarSource} shows=$shows")
        chat.participants.forEach { id ->
            val p = people[id]
            Diag.note(
                TAG,
                "  ${id.value} " +
                    if (p == null) {
                        "(not stored)"
                    } else {
                        "name='${p.displayName.take(TITLE)}' phone=${p.phoneNumber != null} " +
                            "contact=${p.contactId?.value} '${p.contactName?.take(TITLE)}' " +
                            "contactPhoto=${p.contactPhoto} picture=${p.avatarPath?.substringAfterLast('/')}"
                    },
            )
        }
    }
}

private const val TAG = "PingMePhotos"
private const val MERGED = "merged/"
private const val TITLE = 30
private const val SHARED_BY = 3
