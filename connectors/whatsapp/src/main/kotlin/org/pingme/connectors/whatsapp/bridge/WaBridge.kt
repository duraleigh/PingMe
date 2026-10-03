// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp.bridge

/**
 * The Go bridge's WhatsApp surface (gobridge/wa), as Kotlin sees it. The real one wraps
 * the gomobile classes; tests replace it with a fake, so no test ever loads the native
 * library.
 *
 * Every call blocks until WhatsApp answers (up to a minute), so callers run them on an
 * IO dispatcher. Failures are exceptions whose message starts with a code such as
 * `NOT_LOGGED_IN:` (see [WaError]).
 */
interface WaBridge {
    /** Opens (or creates) the device store at [dbPath]; events go to [sink]. */
    fun newSession(
        dbPath: String,
        sink: WaEventSink,
    ): WaSession
}

fun interface WaEventSink {
    fun onEvent(json: String)
}

/** One linked account. Method and parameter order follow gobridge/wa/session.go. */
@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
interface WaSession {
    fun isLoggedIn(): Boolean

    fun ownId(): String

    fun ownPhone(): String

    fun ownLid(): String

    fun pushName(): String

    fun connect()

    fun disconnect()

    fun close()

    fun logout()

    /** The eight-character code to type into WhatsApp on the phone. */
    fun pairCode(phone: String): String

    /** A JSON array of [WaChat]: every joined group, communities included. */
    fun listGroups(): String

    fun groupInfo(jid: String): String

    /** A JSON array of [WaParticipant]: everyone the phone's WhatsApp has a name for. */
    fun contacts(): String

    fun contactName(jid: String): String

    fun phoneOf(jid: String): String

    /** Returns the [WaMessage] as sent. */
    fun sendText(
        chat: String,
        text: String,
        replyJson: String,
    ): String

    @Suppress("LongParameterList") // Everything WhatsApp needs to know about one file.
    fun sendMedia(
        chat: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        caption: String,
        seconds: Long,
        width: Long,
        height: Long,
        replyJson: String,
    ): String

    fun sendReaction(
        chat: String,
        targetId: String,
        targetSender: String,
        targetFromMe: Boolean,
        emoji: String,
    )

    fun revoke(
        chat: String,
        targetId: String,
        targetSender: String,
        targetFromMe: Boolean,
    )

    fun edit(
        chat: String,
        targetId: String,
        text: String,
    )

    fun markRead(
        chat: String,
        sender: String,
        idsJson: String,
        timestamp: Long,
    )

    fun setTyping(
        chat: String,
        typing: Boolean,
    )

    fun download(
        mediaJson: String,
        destPath: String,
    )

    fun requestHistory(
        chat: String,
        lastId: String,
        lastTimestamp: Long,
        lastFromMe: Boolean,
        count: Int,
    )

    /** The user id for an international number on WhatsApp, or "". */
    fun checkNumber(phone: String): String

    /** Every hidden id the phone can pair with a number: a JSON array of [WaIdPair]. */
    fun hiddenIdMap(): String

    fun createGroup(
        name: String,
        participantsJson: String,
    ): String

    fun block(jid: String)

    fun profilePictureUrl(jid: String): String
}

/** The codes the bridge puts before the colon of an error message. */
object WaError {
    const val NOT_LOGGED_IN = "NOT_LOGGED_IN"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val REJECTED = "REJECTED"

    fun codeOf(e: Throwable): String? {
        val message = e.message ?: return null
        val code = message.substringBefore(':', "")
        return code.takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() || c == '_' } }
    }
}
