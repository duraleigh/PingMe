// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp.bridge

import org.pingme.gobridge.wa.Session
import org.pingme.gobridge.wa.Wa
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Go bridge itself: the gomobile classes from gobridge/build/gobridge.aar. Loading
 * this class loads the native library, so nothing on the JVM test path touches it.
 */
@Singleton
class GomobileWaBridge
    @Inject
    constructor() : WaBridge {
        override fun newSession(
            dbPath: String,
            sink: WaEventSink,
        ): WaSession = GomobileSession(Wa.newSession(dbPath) { sink.onEvent(it) })
    }

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
private class GomobileSession(
    private val session: Session,
) : WaSession {
    override fun isLoggedIn(): Boolean = session.isLoggedIn

    override fun ownId(): String = session.ownID()

    override fun ownPhone(): String = session.ownPhone()

    override fun ownLid(): String = session.ownLID()

    override fun pushName(): String = session.pushName()

    override fun connect() = session.connect()

    override fun disconnect() = session.disconnect()

    override fun close() = session.close()

    override fun logout() = session.logout()

    override fun pairCode(phone: String): String = session.pairCode(phone)

    override fun listGroups(): String = session.listGroups()

    override fun groupInfo(jid: String): String = session.groupInfo(jid)

    override fun contacts(): String = session.contacts()

    override fun contactName(jid: String): String = session.contactName(jid)

    override fun phoneOf(jid: String): String = session.phoneOf(jid)

    override fun sendText(
        chat: String,
        text: String,
        replyJson: String,
    ): String = session.sendText(chat, text, replyJson)

    override fun sendMedia(
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
    ): String = session.sendMedia(chat, path, mime, kind, fileName, caption, seconds, width, height, replyJson)

    override fun sendReaction(
        chat: String,
        targetId: String,
        targetSender: String,
        targetFromMe: Boolean,
        emoji: String,
    ) = session.sendReaction(chat, targetId, targetSender, targetFromMe, emoji)

    override fun revoke(
        chat: String,
        targetId: String,
        targetSender: String,
        targetFromMe: Boolean,
    ) = session.revoke(chat, targetId, targetSender, targetFromMe)

    override fun edit(
        chat: String,
        targetId: String,
        text: String,
    ) = session.edit(chat, targetId, text)

    override fun markRead(
        chat: String,
        sender: String,
        idsJson: String,
        timestamp: Long,
    ) = session.markRead(chat, sender, idsJson, timestamp)

    override fun setTyping(
        chat: String,
        typing: Boolean,
    ) = session.setTyping(chat, typing)

    override fun download(
        mediaJson: String,
        destPath: String,
    ) = session.download(mediaJson, destPath)

    override fun requestHistory(
        chat: String,
        lastId: String,
        lastTimestamp: Long,
        lastFromMe: Boolean,
        count: Int,
    ) = session.requestHistory(chat, lastId, lastTimestamp, lastFromMe, count.toLong())

    override fun checkNumber(phone: String): String = session.checkNumber(phone)

    override fun createGroup(
        name: String,
        participantsJson: String,
    ): String = session.createGroup(name, participantsJson)

    override fun block(jid: String) = session.block(jid)

    override fun profilePictureUrl(jid: String): String = session.profilePictureURL(jid)
}
