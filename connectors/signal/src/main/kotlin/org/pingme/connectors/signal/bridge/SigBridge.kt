// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal.bridge

/**
 * The Go bridge's Signal surface (gobridge/sig), as Kotlin sees it. The real one wraps
 * the gomobile classes; tests replace it with a fake, so no test ever loads the native
 * library.
 *
 * Every call blocks until Signal answers (up to a minute), so callers run them on an IO
 * dispatcher. Failures are exceptions whose message starts with a code such as
 * `NOT_LINKED:` (see [SigError]).
 */
interface SigBridge {
    /** Opens (or creates) the account's store at [dbPath]. */
    fun newSession(
        dbPath: String,
        sink: SigEventSink,
    ): SigSession
}

fun interface SigEventSink {
    fun onEvent(json: String)
}

/** One linked account. Method and parameter order follow gobridge/sig/session.go. */
@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
interface SigSession {
    fun isLoggedIn(): Boolean

    fun ownId(): String

    fun ownPhone(): String

    /** Starts linking: "linkQr", then "linkDone" or "linkError" events. */
    fun startLink(deviceName: String)

    fun cancelLink()

    fun connect()

    fun disconnect()

    fun close()

    fun unlink()

    /** A JSON array of [SigMember]: the phone's Signal contacts. */
    fun contacts(): String

    /** A JSON array of [SigChat] from the transferred history. */
    fun chats(): String

    fun chatInfo(chat: String): String

    /** A JSON array of [SigMessage], newest first, older than [beforeTimestamp] (0 for the newest). */
    fun messages(
        chat: String,
        beforeTimestamp: Long,
        count: Int,
    ): String

    fun sendText(
        chat: String,
        text: String,
        quoteJson: String,
    ): String

    @Suppress("LongParameterList") // Everything Signal needs to know about one file.
    fun sendMedia(
        chat: String,
        path: String,
        mime: String,
        fileName: String,
        caption: String,
        voice: Boolean,
        quoteJson: String,
    ): String

    fun sendReaction(
        chat: String,
        targetId: String,
        emoji: String,
        remove: Boolean,
    )

    fun revoke(
        chat: String,
        targetId: String,
    )

    fun edit(
        chat: String,
        targetId: String,
        text: String,
    ): String

    /** Read receipts for the messages (a JSON array of ids) to their senders. */
    fun markRead(
        chat: String,
        idsJson: String,
    )

    fun setTyping(
        chat: String,
        typing: Boolean,
    )

    fun download(
        mediaJson: String,
        destPath: String,
    )

    /** The account id behind a phone number (+E.164), or "" when it has none. */
    fun checkNumber(phone: String): String

    /** Which of the numbers (a JSON array of +E.164) are on Signal: a JSON array of [SigLookup]. */
    fun lookupNumbers(phonesJson: String): String
}

/** The codes the bridge puts before the colon of an error message. */
object SigError {
    const val NOT_LINKED = "NOT_LINKED"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val REJECTED = "REJECTED"

    fun codeOf(e: Throwable): String? {
        val message = e.message ?: return null
        val code = message.substringBefore(':', "")
        return code.takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() || c == '_' } }
    }
}
