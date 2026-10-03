// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice.bridge

/**
 * The Go bridge's Google Voice surface (gobridge/gv), as Kotlin sees it. The real one
 * wraps the gomobile classes; tests replace it with a fake, so no test ever loads the
 * native library.
 *
 * Every call blocks until Google Voice answers (up to a minute), so callers run them on
 * an IO dispatcher. Failures are exceptions whose message starts with a code such as
 * `LOGGED_OUT:` (see [GvError]).
 */
interface GvBridge {
    /** Opens a session with the google.com cookies (a JSON object of name to value). */
    fun newSession(
        cookiesJson: String,
        sink: GvEventSink,
    ): GvSession
}

fun interface GvEventSink {
    fun onEvent(json: String)
}

/** One signed-in account. Method and parameter order follow gobridge/gv/session.go. */
@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
interface GvSession {
    fun cookiesJson(): String

    fun ownPhone(): String

    /** Asks Google Voice who this is; throws when the cookies do not sign in. Returns the number. */
    fun check(): String

    /** Lists the threads (one "thread" event each), then stays connected. */
    fun connect()

    fun disconnect()

    fun close()

    /** A JSON array of [GvThread] with their newest messages. */
    fun threads(): String

    /** One [GvThread] with up to [count] messages older than the page token ("" for the newest). */
    fun thread(
        threadId: String,
        count: Int,
        pageToken: String,
    ): String

    fun nameOf(phone: String): String

    fun sendText(
        threadId: String,
        text: String,
    ): String

    fun sendMedia(
        threadId: String,
        path: String,
        mime: String,
        text: String,
    ): String

    fun markRead(threadId: String)

    fun block(threadId: String)

    fun deleteThread(threadId: String)

    /** Fetches a picture or file by its media id; returns its MIME type. */
    fun download(
        mediaId: String,
        destPath: String,
    ): String
}

/** The codes the bridge puts before the colon of an error message. */
object GvError {
    const val LOGGED_OUT = "LOGGED_OUT"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val REJECTED = "REJECTED"

    fun codeOf(e: Throwable): String? {
        val message = e.message ?: return null
        val code = message.substringBefore(':', "")
        return code.takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() || c == '_' } }
    }
}
