// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger.bridge

/**
 * The Go bridge's Messenger surface (gobridge/fb), as Kotlin sees it. The real one wraps
 * the gomobile classes; tests replace it with a fake, so no test ever loads the native
 * library.
 *
 * Every call blocks until Messenger answers (up to a minute), so callers run them on an
 * IO dispatcher. Failures are exceptions whose message starts with a code such as
 * `LOGGED_OUT:` (see [FbError]).
 */
interface FbBridge {
    /** Opens a session with the facebook.com cookies (a JSON object of name to value). */
    fun newSession(
        cookiesJson: String,
        sink: FbEventSink,
    ): FbSession
}

fun interface FbEventSink {
    fun onEvent(json: String)
}

/** One signed-in account. Method and parameter order follow gobridge/fb/session.go. */
@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
interface FbSession {
    fun cookiesJson(): String

    fun ownId(): String

    /** Loads the inbox page (one "thread" event per conversation, then "inboxLoaded"), then stays connected. */
    fun connect()

    fun disconnect()

    /** Asks for the next page of older conversations (each a "thread" event); true when more may follow. */
    fun moreThreads(): Boolean

    /** Every conversation known so far, newest first, as a JSON array of [FbThread]. */
    fun threads(): String

    fun thread(id: String): String

    /** A JSON array of [FbMessage], newest first, older than [olderThan] (the newest known when empty). */
    fun messages(
        thread: String,
        olderThan: String,
    ): String

    fun sendText(
        thread: String,
        text: String,
        replyTo: String,
    ): String

    @Suppress("LongParameterList") // Everything Messenger needs to know about one file.
    fun sendMedia(
        thread: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        text: String,
        replyTo: String,
    ): String

    fun sendReaction(
        thread: String,
        messageId: String,
        emoji: String,
        remove: Boolean,
    )

    fun unsend(messageId: String)

    fun edit(
        messageId: String,
        text: String,
    )

    fun markRead(
        thread: String,
        timestamp: Long,
    )

    fun setTyping(
        thread: String,
        typing: Boolean,
    )

    fun acceptRequest(thread: String)

    fun deleteThread(thread: String)

    fun download(
        url: String,
        mime: String,
        destPath: String,
    )

    /** A JSON array of [FbUser] you can message, matching a name. */
    fun searchUsers(query: String): String

    /** Opens (or finds) the one-to-one thread with a person; returns its id. */
    fun startChat(userId: String): String
}

/** The codes the bridge puts before the colon of an error message. */
object FbError {
    const val LOGGED_OUT = "LOGGED_OUT"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val REJECTED = "REJECTED"

    fun codeOf(e: Throwable): String? {
        val message = e.message ?: return null
        val code = message.substringBefore(':', "")
        return code.takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() || c == '_' } }
    }
}
