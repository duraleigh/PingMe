// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import kotlinx.coroutines.CompletableDeferred
import org.pingme.connectors.gmessages.bridge.GmMessage
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.model.MessageId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One send: what went out, so the phone's copy can be matched by its tmpId, or, when the
 * phone drops that, by text and time. [waiter] is set while the send waits; [standIn] once
 * a stand-in message has been shown instead.
 */
internal class Outgoing(
    val conversation: String,
    val text: String,
    val pictures: Int,
    val sentAt: Instant,
    @Volatile var waiter: CompletableDeferred<MessageSnapshot>?,
    @Volatile var standIn: MessageId? = null,
)

/**
 * Which waiting send, by its tmpId, a message from the phone is the copy of; null when none.
 *
 * A message carrying a tmpId is that send's copy or no send's at all. Only a message the phone
 * left untagged is matched by what it says: the same chat, the same text, as many pictures,
 * and made no earlier than the send. Before this, any older message of yours with the same
 * text answered a new send, and every picture sent without words has the same empty text: the
 * catch-up re-reading the chat paired a new picture's send with the picture before it, which
 * then took the new picture's file, while the new message came in bare and showed blank
 * (owner, 2026-11-02, Nicole's chat: "inside that bubble is no longer the previous image").
 */
internal fun sendAnswered(
    msg: GmMessage,
    sends: Map<String, Outgoing>,
    now: Instant,
    window: Duration,
): String? =
    when {
        !msg.fromMe -> {
            null
        }

        msg.tmpId.isNotEmpty() -> {
            msg.tmpId.takeIf { it in sends }
        }

        else -> {
            val made = Instant.fromEpochMilliseconds(msg.timestamp / MICROS_PER_MILLI)
            sends.entries
                .filter { (_, o) ->
                    o.conversation == msg.conversationId &&
                        o.text == msg.text &&
                        o.pictures == msg.media.size &&
                        made >= o.sentAt - CLOCK_SLACK &&
                        now - o.sentAt < window
                }.minByOrNull { (_, o) -> o.sentAt }
                ?.key
        }
    }

private const val MICROS_PER_MILLI = 1000

// The phone stamps a message a moment after PingMe asked for it, on the same clock; a little
// room keeps a rounding from refusing the right one, and is far short of the message before.
private val CLOCK_SLACK = 2.seconds
