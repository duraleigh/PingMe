// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * What a connector can do. The UI reads this and disables unsupported controls with a
 * one-line reason, never hiding them and never silently downgrading (UI_DESIGN.md 1, 5, 8).
 * The shape follows the capability matrix in UI_DESIGN.md section 8.
 */
@Serializable
data class Capabilities(
    val reply: ReplyRule,
    val deleteForMe: Boolean,
    /** Null when the network has no delete for everyone. */
    val deleteForEveryone: TimeLimit?,
    val reactions: ReactionRule,
    val gif: MediaRule,
    val voiceNote: MediaRule,
    val typing: Boolean,
    val readReceipts: Boolean,
    /** Null when messages cannot be edited. */
    val edit: TimeLimit?,
    val nativePins: Boolean,
    val folders: Boolean,
    val startConversation: Boolean,
    /** New groups can be made from PingMe (DESIGN.md 6.2). */
    val createGroup: Boolean,
    /** People can be blocked from PingMe (UI_DESIGN.md 6.4, 3.4). */
    val block: Boolean,
    val multiAccount: Boolean,
    val calls: CallRule,
)

/** How long after sending an action stays available. */
@Serializable
sealed interface TimeLimit {
    @Serializable
    data object Unlimited : TimeLimit

    @Serializable
    data class Within(
        val duration: Duration,
    ) : TimeLimit
}

@Serializable
enum class ReplyRule {
    /** The network has real replies (RCS, WhatsApp, Telegram, Signal, ...). */
    NATIVE,

    /** No native reply; sent with a short quoted-line prefix (SMS, Google Voice). */
    QUOTED_TEXT,
}

@Serializable
sealed interface ReactionRule {
    /** Any emoji. */
    @Serializable
    data object AnyEmoji : ReactionRule

    /** Only these emoji (Telegram without Premium). Others show greyed with a reason. */
    @Serializable
    data class Set(
        val allowed: List<String>,
    ) : ReactionRule

    /** Sent as text, "Reacted ❤️ to ..." (SMS, Google Voice). */
    @Serializable
    data object TextFallback : ReactionRule
}

/** How GIFs and voice notes travel. */
@Serializable
enum class MediaRule {
    NATIVE,

    /** Sent as MMS, with the carrier size warning. */
    MMS_SIZE_LIMITED,
    UNSUPPORTED,
}

/** What the phone and video icons in the chat header do (UI_DESIGN.md 10.17). */
@Serializable
data class CallRule(
    val audio: CallMethod,
    val video: CallMethod,
)

@Serializable
enum class CallMethod {
    /** Dial the number immediately in the default dialer. */
    DIALER,

    /** Google Meet calling to the number. */
    MEET,

    /** The call entry the app registers in the phone's contacts (WhatsApp, Signal, Telegram). */
    CONTACT_APP_CALL,

    /** Open the app to that person (Google Voice, Messenger). */
    OPEN_APP,

    /** Open the thread; the service exposes no call intent (Instagram). */
    OPEN_THREAD,
    NONE,
}
