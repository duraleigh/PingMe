// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages.bridge

import org.pingme.gobridge.gm.Gm
import org.pingme.gobridge.gm.Login
import org.pingme.gobridge.gm.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Go bridge itself: the gomobile classes from gobridge/build/gobridge.aar. Loading
 * this class loads the native library, so nothing on the JVM test path touches it.
 */
@Singleton
class GomobileGmBridge
    @Inject
    constructor() : GmBridge {
        override val cookieNames: String get() = Gm.CookieNames
        override val signInUrl: String get() = Gm.SignInURL

        override fun newLogin(cookiesJson: String): GmLogin = GomobileLogin(Gm.newLogin(cookiesJson))

        override fun newSession(
            authJson: String,
            sink: GmEventSink,
        ): GmSession = GomobileSession(Gm.newSession(authJson) { sink.onEvent(it) })
    }

private class GomobileLogin(
    private val login: Login,
) : GmLogin {
    override fun start(): String = login.start()

    override fun finish(): String = login.finish()

    override fun cancel() = login.cancel()
}

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
private class GomobileSession(
    private val session: Session,
) : GmSession {
    override fun connect() = session.connect()

    override fun disconnect() = session.disconnect()

    override fun authJson(): String = session.authJSON()

    override fun setActive() = session.setActive()

    override fun unpair() = session.unpair()

    override fun listConversations(
        folder: String,
        count: Int,
        cursorJson: String,
    ): String = session.listConversations(folder, count.toLong(), cursorJson)

    override fun getConversation(conversationId: String): String = session.getConversation(conversationId)

    override fun fetchMessages(
        conversationId: String,
        count: Int,
        cursorJson: String,
    ): String = session.fetchMessages(conversationId, count.toLong(), cursorJson)

    override fun sendMessage(requestJson: String) = session.sendMessage(requestJson)

    override fun uploadMedia(
        path: String,
        fileName: String,
        mime: String,
    ): String = session.uploadMedia(path, fileName, mime)

    override fun downloadMedia(
        mediaId: String,
        keyBase64: String,
        destPath: String,
    ) = session.downloadMedia(mediaId, keyBase64, destPath)

    override fun requestFullSizeMedia(
        messageId: String,
        partId: String,
    ) = session.requestFullSizeMedia(messageId, partId)

    override fun sendReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
        action: String,
    ) = session.sendReaction(conversationId, messageId, emoji, action)

    override fun deleteMessage(messageId: String) = session.deleteMessage(messageId)

    override fun markRead(
        conversationId: String,
        messageId: String,
    ) = session.markRead(conversationId, messageId)

    override fun setTyping(conversationId: String) = session.setTyping(conversationId)

    override fun getOrCreateConversation(
        numbersJson: String,
        groupName: String,
    ): String = session.getOrCreateConversation(numbersJson, groupName)
}
