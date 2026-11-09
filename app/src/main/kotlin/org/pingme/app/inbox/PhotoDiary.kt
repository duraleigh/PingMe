// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import org.pingme.core.connector.Diag
import org.pingme.core.model.Chat
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId

/**
 * Every merged chat's photo and where it comes from, to the diagnostic file: merged chats
 * made on 2026-11-08 and 11-09 showed the owner's own photo, and nothing on screen says
 * which person inside them carries it (owner, 2026-11-09).
 */
internal fun notePhotos(
    chats: List<Chat>,
    people: Map<PersonId, Person>,
) {
    chats.filter { it.id.value.startsWith(MERGED) }.forEach { chat ->
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
