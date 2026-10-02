// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram.bridge

import org.pingme.gobridge.ig.Ig
import org.pingme.gobridge.ig.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Go bridge itself: the gomobile classes from gobridge/build/gobridge.aar. Loading
 * this class loads the native library, so nothing on the JVM test path touches it.
 */
@Singleton
class GomobileIgBridge
    @Inject
    constructor() : IgBridge {
        override fun newSession(
            cookiesJson: String,
            sink: IgEventSink,
        ): IgSession = GomobileSession(Ig.newSession(cookiesJson) { sink.onEvent(it) })
    }

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
private class GomobileSession(
    private val session: Session,
) : IgSession {
    override fun cookiesJson(): String = session.cookiesJSON()

    override fun ownId(): String = session.ownID()

    override fun connect() = session.connect()

    override fun disconnect() = session.disconnect()

    override fun listThreads(
        folder: String,
        cursor: String,
    ): String = session.listThreads(folder, cursor)

    override fun thread(fbid: String): String = session.thread(fbid)

    override fun messages(
        fbid: String,
        olderThan: String,
        count: Int,
    ): String = session.messages(fbid, olderThan, count.toLong())

    override fun sendText(
        fbid: String,
        text: String,
        replyTo: String,
    ): String = session.sendText(fbid, text, replyTo)

    override fun sendMedia(
        fbid: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        replyTo: String,
    ): String = session.sendMedia(fbid, path, mime, kind, fileName, replyTo)

    override fun sendReaction(
        fbid: String,
        messageId: String,
        emoji: String,
        remove: Boolean,
    ) = session.sendReaction(fbid, messageId, emoji, remove)

    override fun unsend(
        fbid: String,
        messageId: String,
    ) = session.unsend(fbid, messageId)

    override fun edit(
        fbid: String,
        messageId: String,
        text: String,
    ) = session.edit(fbid, messageId, text)

    override fun markRead(
        fbid: String,
        messageId: String,
        timestamp: Long,
    ) = session.markRead(fbid, messageId, timestamp)

    override fun setTyping(
        fbid: String,
        typing: Boolean,
    ) = session.setTyping(fbid, typing)

    override fun acceptRequest(fbid: String) = session.acceptRequest(fbid)

    override fun deleteThread(fbid: String) = session.deleteThread(fbid)

    override fun download(
        url: String,
        destPath: String,
    ) = session.download(url, destPath)

    override fun searchUsers(query: String): String = session.searchUsers(query)
}
