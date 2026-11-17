// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import org.pingme.connectors.gmessages.bridge.GmConversation
import org.pingme.connectors.gmessages.bridge.GmMessage
import org.pingme.core.connector.Diag
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes to the diary, once each, the lines Google Messages puts in a chat when it changes
 * how the chat is carried ("RCS chat with …", "Texting with … (SMS/MMS)", encryption on or
 * off), and any message whose status speaks of encryption or failure. Encrypted replies
 * from iPhones stopped reaching the phone around 2026-11-10 (owner, 2026-11-12); these
 * lines show when each chat changed and what PingMe sent around then.
 */
internal class ChatLineNotes {
    private val noted = ConcurrentHashMap.newKeySet<String>()

    /** Every conversation listed since start-up, for the names in the notes. */
    private val names = ConcurrentHashMap<String, String>()

    fun listed(conversations: List<GmConversation>) = conversations.forEach { names[it.id] = it.name }

    fun note(msg: GmMessage) {
        val name = msg.statusName
        val telling =
            msg.direction == "tombstone" || "ENCRYPT" in name || "E2EE" in name || "FAIL" in name
        if (!telling || !noted.add(msg.id)) return
        val at = java.time.Instant.ofEpochMilli(msg.timestamp / MICROS_PER_MILLI)
        val who = names[msg.conversationId].orEmpty()
        Diag.note(
            TAG,
            "Chat ${msg.conversationId} '$who' line ${msg.id} at $at: $name (${msg.status}) ${msg.transport}",
        )
    }

    private companion object {
        const val TAG = "PingMeGmessages"
        const val MICROS_PER_MILLI = 1000
    }
}
