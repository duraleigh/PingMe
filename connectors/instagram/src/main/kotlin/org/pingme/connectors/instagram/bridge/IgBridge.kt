// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram.bridge

/**
 * The Go bridge's Instagram surface (gobridge/ig), as Kotlin sees it. The real one wraps
 * the gomobile classes; tests replace it with a fake, so no test ever loads the native
 * library.
 *
 * Every call blocks until Instagram answers (up to a minute), so callers run them on an
 * IO dispatcher. Failures are exceptions whose message starts with a code such as
 * `LOGGED_OUT:` (see [IgError]).
 */
interface IgBridge {
    /** Opens a session with the instagram.com cookies (a JSON object of name to value). */
    fun newSession(
        cookiesJson: String,
        sink: IgEventSink,
    ): IgSession
}

fun interface IgEventSink {
    fun onEvent(json: String)
}

/** One signed-in account. Method and parameter order follow gobridge/ig/session.go. */
@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
interface IgSession {
    fun cookiesJson(): String

    fun ownId(): String

    /** Loads the inbox (one "thread" event per conversation), then stays connected. */
    fun connect()

    fun disconnect()

    /** A JSON object {threads, nextCursor} for folder "INBOX" or "PENDING". */
    fun listThreads(
        folder: String,
        cursor: String,
    ): String

    fun thread(fbid: String): String

    /** A JSON array of [IgMessage], newest first, older than [olderThan] (the newest when empty). */
    fun messages(
        fbid: String,
        olderThan: String,
        count: Int,
    ): String

    fun sendText(
        fbid: String,
        text: String,
        replyTo: String,
    ): String

    @Suppress("LongParameterList") // Everything Instagram needs to know about one file.
    fun sendMedia(
        fbid: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        replyTo: String,
    ): String

    fun sendReaction(
        fbid: String,
        messageId: String,
        emoji: String,
        remove: Boolean,
    )

    fun unsend(
        fbid: String,
        messageId: String,
    )

    fun edit(
        fbid: String,
        messageId: String,
        text: String,
    )

    fun markRead(
        fbid: String,
        messageId: String,
        timestamp: Long,
    )

    fun setTyping(
        fbid: String,
        typing: Boolean,
    )

    fun acceptRequest(fbid: String)

    fun deleteThread(fbid: String)

    fun download(
        url: String,
        destPath: String,
    )

    /** A JSON array of [IgUser] matching a name or username. */
    fun searchUsers(query: String): String
}

/** The codes the bridge puts before the colon of an error message. */
object IgError {
    const val LOGGED_OUT = "LOGGED_OUT"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val REJECTED = "REJECTED"

    fun codeOf(e: Throwable): String? {
        val message = e.message ?: return null
        val code = message.substringBefore(':', "")
        return code.takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() || c == '_' } }
    }
}
