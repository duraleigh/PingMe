// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.accountId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.Reaction
import org.pingme.core.store.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iPhone reactions that arrive as text over SMS ("Loved an image", "Laughed at “See you
 * at 7”", "Removed a heart from “…”") become reactions on the message they refer to:
 * the newest picture or video before them, or the newest message with that text
 * (owner, Gate G3: never a bubble of their own).
 */
@Singleton
class Tapbacks
    @Inject
    constructor(
        private val messages: MessageRepository,
    ) {
        /** What a reaction text says, or null when [text] is not one. */
        data class Tapback(
            val emoji: String,
            val removed: Boolean,
            /** The quoted text, or null for "an image" and the like. */
            val quoted: String?,
            /** The kinds a media reference can mean, empty for a quote. */
            val kinds: Set<MessageKind>,
        )

        /** A new message that is really a reaction becomes that reaction; anything else passes through. */
        suspend fun rewrite(event: ConnectorEvent): ConnectorEvent =
            if (event is ConnectorEvent.NewMessage) asReaction(event.message.message) ?: event else event

        /**
         * The reaction [message] stands for, on the message it refers to, or null when it is
         * ordinary text or nothing before it matches (then it stays a message).
         */
        suspend fun asReaction(message: Message): ConnectorEvent.ReactionChanged? {
            if (message.isOutgoing) return null
            val tapback = parse(message.body ?: return null) ?: return null
            val target = targetOf(message, tapback) ?: return null
            val reaction = Reaction(tapback.emoji, message.senderId, message.sentAt)
            return ConnectorEvent.ReactionChanged(message.chatId.accountId, target.id, reaction, tapback.removed)
        }

        private suspend fun targetOf(
            message: Message,
            tapback: Tapback,
        ): Message? {
            val earlier = messages.before(message.chatId, message.sentAt, LOOK_BACK).filter { it.id != message.id }
            val quoted = tapback.quoted
            if (quoted != null) {
                // The phone quotes the whole text; a long one may be cut short.
                return earlier.firstOrNull { it.body?.trim() == quoted }
                    ?: earlier.firstOrNull { it.body?.trim()?.startsWith(quoted) == true }
            }
            return earlier.firstOrNull { it.kind in tapback.kinds }
        }

        companion object {
            private const val LOOK_BACK = 200

            private val VERBS =
                mapOf(
                    "Loved" to "\u2764\uFE0F",
                    "Liked" to "\uD83D\uDC4D",
                    "Disliked" to "\uD83D\uDC4E",
                    "Laughed at" to "\uD83D\uDE02",
                    "Emphasized" to "\u203C\uFE0F",
                    "Questioned" to "\u2753",
                )
            private val NOUNS =
                mapOf(
                    "a heart" to "\u2764\uFE0F",
                    "a like" to "\uD83D\uDC4D",
                    "a dislike" to "\uD83D\uDC4E",
                    "a laugh" to "\uD83D\uDE02",
                    "an exclamation" to "\u203C\uFE0F",
                    "a question mark" to "\u2753",
                )
            private val PICTURES = setOf(MessageKind.IMAGE, MessageKind.GIF, MessageKind.STICKER)
            private val MEDIA =
                mapOf(
                    "an image" to PICTURES,
                    "a photo" to PICTURES,
                    "a picture" to PICTURES,
                    "a video" to setOf(MessageKind.VIDEO),
                    "a movie" to setOf(MessageKind.VIDEO),
                    "an audio message" to setOf(MessageKind.VOICE),
                    "a voice message" to setOf(MessageKind.VOICE),
                    "a GIF" to setOf(MessageKind.GIF),
                    "a sticker" to setOf(MessageKind.STICKER),
                    "an attachment" to setOf(MessageKind.FILE, MessageKind.IMAGE, MessageKind.VIDEO, MessageKind.VOICE),
                    "a location" to setOf(MessageKind.LOCATION),
                    "a contact" to setOf(MessageKind.CONTACT),
                )
            private val ADDED = Regex("""^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned) (.+)$""")
            private val REMOVED =
                Regex("""^Removed (a heart|a like|a dislike|a laugh|an exclamation|a question mark) from (.+)$""")
            private val QUOTE = Regex("""^[“"](.*)[”"]$""", RegexOption.DOT_MATCHES_ALL)

            /** The reaction a text means, or null when it is ordinary text. */
            fun parse(text: String): Tapback? {
                val line = text.trim()
                ADDED.matchEntire(line)?.let { m ->
                    val emoji = VERBS[m.groupValues[1]] ?: return null
                    return subject(m.groupValues[2])?.let { (quoted, kinds) -> Tapback(emoji, false, quoted, kinds) }
                }
                REMOVED.matchEntire(line)?.let { m ->
                    val emoji = NOUNS[m.groupValues[1]] ?: return null
                    return subject(m.groupValues[2])?.let { (quoted, kinds) -> Tapback(emoji, true, quoted, kinds) }
                }
                return null
            }

            // “quoted text” or a media noun; anything else is not a reaction text.
            private fun subject(rest: String): Pair<String?, Set<MessageKind>>? {
                QUOTE.matchEntire(rest)?.let { return it.groupValues[1] to emptySet() }
                return MEDIA[rest]?.let { null to it }
            }
        }
    }
