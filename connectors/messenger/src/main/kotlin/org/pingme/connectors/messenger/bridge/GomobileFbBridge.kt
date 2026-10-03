// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger.bridge

import org.pingme.gobridge.fb.Fb
import org.pingme.gobridge.fb.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Go bridge itself: the gomobile classes from gobridge/build/gobridge.aar. Loading
 * this class loads the native library, so nothing on the JVM test path touches it.
 */
@Singleton
class GomobileFbBridge
    @Inject
    constructor() : FbBridge {
        override fun newSession(
            cookiesJson: String,
            sink: FbEventSink,
        ): FbSession = GomobileSession(Fb.newSession(cookiesJson) { sink.onEvent(it) })
    }

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
private class GomobileSession(
    private val session: Session,
) : FbSession {
    override fun cookiesJson(): String = session.cookiesJSON()

    override fun ownId(): String = session.ownID()

    override fun connect() = session.connect()

    override fun disconnect() = session.disconnect()

    override fun moreThreads(): Boolean = session.moreThreads()

    override fun threads(): String = session.threads()

    override fun thread(id: String): String = session.thread(id)

    override fun messages(
        thread: String,
        olderThan: String,
    ): String = session.messages(thread, olderThan)

    override fun sendText(
        thread: String,
        text: String,
        replyTo: String,
    ): String = session.sendText(thread, text, replyTo)

    override fun sendMedia(
        thread: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        text: String,
        replyTo: String,
    ): String = session.sendMedia(thread, path, mime, kind, fileName, text, replyTo)

    override fun sendReaction(
        thread: String,
        messageId: String,
        emoji: String,
        remove: Boolean,
    ) = session.sendReaction(thread, messageId, emoji, remove)

    override fun unsend(messageId: String) = session.unsend(messageId)

    override fun edit(
        messageId: String,
        text: String,
    ) = session.edit(messageId, text)

    override fun markRead(
        thread: String,
        timestamp: Long,
    ) = session.markRead(thread, timestamp)

    override fun setTyping(
        thread: String,
        typing: Boolean,
    ) = session.setTyping(thread, typing)

    override fun acceptRequest(thread: String) = session.acceptRequest(thread)

    override fun deleteThread(thread: String) = session.deleteThread(thread)

    override fun download(
        url: String,
        mime: String,
        destPath: String,
    ) = session.download(url, mime, destPath)

    override fun searchUsers(query: String): String = session.searchUsers(query)

    override fun startChat(userId: String): String = session.startChat(userId)
}
