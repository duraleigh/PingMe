// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal.bridge

import org.pingme.gobridge.sig.Session
import org.pingme.gobridge.sig.Sig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Go bridge itself: the gomobile classes from gobridge/build/gobridge.aar. Loading
 * this class loads the native library, so nothing on the JVM test path touches it.
 */
@Singleton
class GomobileSigBridge
    @Inject
    constructor() : SigBridge {
        override fun newSession(
            dbPath: String,
            sink: SigEventSink,
        ): SigSession = GomobileSession(Sig.newSession(dbPath) { sink.onEvent(it) })
    }

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
private class GomobileSession(
    private val session: Session,
) : SigSession {
    override fun isLoggedIn(): Boolean = session.isLoggedIn

    override fun ownId(): String = session.ownID()

    override fun ownPhone(): String = session.ownPhone()

    override fun startLink(deviceName: String) = session.startLink(deviceName)

    override fun cancelLink() = session.cancelLink()

    override fun connect() = session.connect()

    override fun disconnect() = session.disconnect()

    override fun close() = session.close()

    override fun unlink() = session.unlink()

    override fun contacts(): String = session.contacts()

    override fun chats(): String = session.chats()

    override fun chatInfo(chat: String): String = session.chatInfo(chat)

    override fun messages(
        chat: String,
        beforeTimestamp: Long,
        count: Int,
    ): String = session.messages(chat, beforeTimestamp, count.toLong())

    override fun sendText(
        chat: String,
        text: String,
        quoteJson: String,
    ): String = session.sendText(chat, text, quoteJson)

    override fun sendMedia(
        chat: String,
        path: String,
        mime: String,
        fileName: String,
        caption: String,
        voice: Boolean,
        quoteJson: String,
    ): String = session.sendMedia(chat, path, mime, fileName, caption, voice, quoteJson)

    override fun sendReaction(
        chat: String,
        targetId: String,
        emoji: String,
        remove: Boolean,
    ) = session.sendReaction(chat, targetId, emoji, remove)

    override fun revoke(
        chat: String,
        targetId: String,
    ) = session.revoke(chat, targetId)

    override fun edit(
        chat: String,
        targetId: String,
        text: String,
    ): String = session.edit(chat, targetId, text)

    override fun markRead(
        chat: String,
        idsJson: String,
    ) = session.markRead(chat, idsJson)

    override fun setTyping(
        chat: String,
        typing: Boolean,
    ) = session.setTyping(chat, typing)

    override fun download(
        mediaJson: String,
        destPath: String,
    ) = session.download(mediaJson, destPath)

    override fun checkNumber(phone: String): String = session.checkNumber(phone)
}
