// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages.bridge

/**
 * The Go bridge's surface (gobridge/gm), as Kotlin sees it. The real one wraps the
 * gomobile classes; tests replace it with a fake that replays a recorded session, so
 * no test ever loads the native library.
 *
 * Every call blocks until the phone answers (up to a minute), so callers run them on
 * an IO dispatcher. Failures are exceptions whose message starts with a code such as
 * `LOGGED_OUT:` (see [GmError]).
 */
interface GmBridge {
    /** Starts Google account pairing with the sign-in cookies (name to value). */
    fun newLogin(cookiesJson: String): GmLogin

    /** Restores a paired session from the JSON a login gave. */
    fun newSession(
        authJson: String,
        sink: GmEventSink,
    ): GmSession

    /** Cookie names the pairing needs, comma separated. */
    val cookieNames: String

    /** The Google sign-in page to open. */
    val signInUrl: String
}

fun interface GmEventSink {
    fun onEvent(json: String)
}

interface GmLogin {
    /** Returns the emoji the user must tap in Google Messages. */
    fun start(): String

    /** Waits for the tap; returns a [GmLoginResult] as JSON. */
    fun finish(): String

    fun cancel()
}

/** One live pairing. Method and parameter order follow gobridge/gm/session.go. */
@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
interface GmSession {
    fun connect()

    fun disconnect()

    fun authJson(): String

    fun setActive()

    fun unpair()

    /** [folder] is "inbox", "archive", or "spam"; returns a [GmConversationPage]. */
    fun listConversations(
        folder: String,
        count: Int,
        cursorJson: String,
    ): String

    fun getConversation(conversationId: String): String

    /** Returns a [GmMessagePage], newest first, older than the cursor when given. */
    fun fetchMessages(
        conversationId: String,
        count: Int,
        cursorJson: String,
    ): String

    /** Takes a [GmSendRequest]; the message comes back as an event with the same tmpId. */
    fun sendMessage(requestJson: String)

    /** Returns the [GmMedia] to put in a send request. */
    fun uploadMedia(
        path: String,
        fileName: String,
        mime: String,
    ): String

    fun downloadMedia(
        mediaId: String,
        keyBase64: String,
        destPath: String,
    )

    fun requestFullSizeMedia(
        messageId: String,
        partId: String,
    )

    /** [action] is "add", "remove", or "switch". */
    fun sendReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
        action: String,
    )

    fun deleteMessage(messageId: String)

    fun markRead(
        conversationId: String,
        messageId: String,
    )

    fun setTyping(conversationId: String)

    /** [numbersJson] is a JSON array of phone numbers; returns a [GmConversation]. */
    fun getOrCreateConversation(
        numbersJson: String,
        groupName: String,
    ): String
}

/** The code the Go bridge puts before the colon of an error message. */
object GmError {
    const val LOGGED_OUT = "LOGGED_OUT"
    const val PHONE_NOT_RESPONDING = "PHONE_NOT_RESPONDING"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val REJECTED = "REJECTED"
    const val PAIR_MISSING_COOKIES = "PAIR_MISSING_COOKIES"
    const val PAIR_NO_DEVICES = "PAIR_NO_DEVICES"
    const val PAIR_PHONE_NOT_RESPONDING = "PAIR_PHONE_NOT_RESPONDING"
    const val PAIR_NO_PERMISSION = "PAIR_NO_PERMISSION"
    const val PAIR_WRONG_EMOJI = "PAIR_WRONG_EMOJI"
    const val PAIR_CANCELLED = "PAIR_CANCELLED"
    const val PAIR_TIMEOUT = "PAIR_TIMEOUT"

    /** The code of a bridge failure, or null when it has none. */
    fun codeOf(e: Throwable): String? = e.message?.substringBefore(':', "")?.takeIf { it.matches(CODE) }

    private val CODE = Regex("[A-Z_]+")
}
