// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice.bridge

import org.pingme.gobridge.gv.Gv
import org.pingme.gobridge.gv.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Go bridge itself: the gomobile classes from gobridge/build/gobridge.aar. Loading
 * this class loads the native library, so nothing on the JVM test path touches it.
 */
@Singleton
class GomobileGvBridge
    @Inject
    constructor() : GvBridge {
        override fun newSession(
            cookiesJson: String,
            sink: GvEventSink,
        ): GvSession = GomobileSession(Gv.newSession(cookiesJson) { sink.onEvent(it) })
    }

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
private class GomobileSession(
    private val session: Session,
) : GvSession {
    override fun cookiesJson(): String = session.cookiesJSON()

    override fun ownPhone(): String = session.ownPhone()

    override fun check(): String = session.check()

    override fun connect() = session.connect()

    override fun disconnect() = session.disconnect()

    override fun close() = session.close()

    override fun threads(): String = session.threads()

    override fun thread(
        threadId: String,
        count: Int,
        pageToken: String,
    ): String = session.thread(threadId, count.toLong(), pageToken)

    override fun nameOf(phone: String): String = session.nameOf(phone)

    override fun sendText(
        threadId: String,
        text: String,
    ): String = session.sendText(threadId, text)

    override fun sendMedia(
        threadId: String,
        path: String,
        mime: String,
        text: String,
    ): String = session.sendMedia(threadId, path, mime, text)

    override fun markRead(threadId: String) = session.markRead(threadId)

    override fun block(threadId: String) = session.block(threadId)

    override fun deleteThread(threadId: String) = session.deleteThread(threadId)

    override fun download(
        mediaId: String,
        destPath: String,
    ): String = session.download(mediaId, destPath)
}
