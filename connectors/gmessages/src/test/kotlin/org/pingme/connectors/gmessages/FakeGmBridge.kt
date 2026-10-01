// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.gmessages.bridge.GmBridge
import org.pingme.connectors.gmessages.bridge.GmConversation
import org.pingme.connectors.gmessages.bridge.GmConversationPage
import org.pingme.connectors.gmessages.bridge.GmCursor
import org.pingme.connectors.gmessages.bridge.GmEvent
import org.pingme.connectors.gmessages.bridge.GmEventSink
import org.pingme.connectors.gmessages.bridge.GmLogin
import org.pingme.connectors.gmessages.bridge.GmLoginResult
import org.pingme.connectors.gmessages.bridge.GmMedia
import org.pingme.connectors.gmessages.bridge.GmMessage
import org.pingme.connectors.gmessages.bridge.GmMessagePage
import org.pingme.connectors.gmessages.bridge.GmParticipant
import org.pingme.connectors.gmessages.bridge.GmReaction
import org.pingme.connectors.gmessages.bridge.GmSendRequest
import org.pingme.connectors.gmessages.bridge.GmSession
import org.pingme.connectors.gmessages.bridge.GmSettings
import org.pingme.connectors.gmessages.bridge.SessionFixture
import org.pingme.connectors.gmessages.bridge.gmJson
import org.pingme.core.connector.CredentialStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Go bridge replaced by a pretend phone that replays the recorded session
 * (gobridge/gm/testdata/session.json) and answers the way Google Messages does: a send
 * is echoed back as a message event with the same tmpId, a reaction comes back on the
 * message, a delete comes back as a deleted message.
 */
class FakeGmBridge(
    fixture: SessionFixture = SessionFixture.load(),
) : GmBridge {
    val phone = FakePhone(fixture)
    override val cookieNames = "SID,HSID,OSID,SSID,APISID,SAPISID,__Secure-1PSIDTS"
    override val signInUrl = "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web"

    override fun newLogin(cookiesJson: String): GmLogin {
        val cookies = gmJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), cookiesJson)
        val missing = REQUIRED.filter { cookies[it].isNullOrEmpty() }
        require(missing.isEmpty()) { "PAIR_MISSING_COOKIES: sign-in cookies are incomplete: $missing" }
        return object : GmLogin {
            override fun start() = "🦊"

            override fun finish() =
                gmJson.encodeToString(GmLoginResult.serializer(), GmLoginResult(AUTH, "$EMAIL/1", EMAIL))

            override fun cancel() = Unit
        }
    }

    override fun newSession(
        authJson: String,
        sink: GmEventSink,
    ): GmSession {
        require(authJson == AUTH) { "LOGGED_OUT: unknown session" }
        return FakeSession(phone, sink)
    }

    companion object {
        const val EMAIL = "owner@example.com"
        const val AUTH = """{"fake":"session"}"""
        val REQUIRED = listOf("SID", "HSID", "OSID", "SSID", "APISID", "SAPISID")
    }
}

/** The pretend phone's state and its test hooks. */
class FakePhone(
    fixture: SessionFixture,
) {
    val conversations = ConcurrentHashMap<String, GmConversation>()
    val messages = ConcurrentHashMap<String, MutableList<GmMessage>>()
    val settings: GmSettings
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val typingSent = AtomicInteger()
    val readMarks = ConcurrentHashMap<String, String>()
    private val ids = AtomicInteger()

    @Volatile var sink: GmEventSink? = null

    /** Events from before a session attached, delivered once it has: a real phone keeps them too. */
    private val waiting = mutableListOf<GmEvent>()

    init {
        val page = gmJson.decodeFromString(GmConversationPage.serializer(), fixture.conversationsJson)
        page.conversations.forEach { conversations[it.id] = it }
        page.conversations.forEach { conv ->
            val list = gmJson.decodeFromString(GmMessagePage.serializer(), fixture.messagesJson(conv.id)).messages
            messages[conv.id] = list.toMutableList()
        }
        settings =
            fixture.events
                .map { gmJson.decodeFromString(GmEvent.serializer(), it) }
                .filterIsInstance<GmEvent.Settings>()
                .first()
                .settings
    }

    fun emit(event: GmEvent) {
        val target = synchronized(waiting) { sink.also { if (it == null) waiting += event } } ?: return
        target.onEvent(gmJson.encodeToString(GmEvent.serializer(), event))
    }

    internal fun attach(newSink: GmEventSink) {
        val queued =
            synchronized(waiting) {
                sink = newSink
                waiting.toList().also { waiting.clear() }
            }
        emit(GmEvent.Settings(settings))
        emit(GmEvent.Ready(resync = false))
        queued.forEach(::emit)
    }

    private fun now() = System.currentTimeMillis() * MICROS

    /** Someone else texts into a conversation. */
    fun receive(
        conversationId: String,
        text: String,
    ) {
        val other = other(conversationId)
        val message =
            GmMessage(
                id = "r${ids.incrementAndGet()}",
                conversationId = conversationId,
                participantId = other.id,
                timestamp = now(),
                status = INCOMING_COMPLETE,
                statusName = "INCOMING_COMPLETE",
                direction = "incoming",
                transport = "RCS",
                text = text,
                sender = other,
            )
        messages.getValue(conversationId).add(0, message)
        emit(GmEvent.Message(message))
    }

    fun remoteTyping(conversationId: String) =
        emit(GmEvent.Typing(conversationId, other(conversationId).number, typing = true))

    /** Your reactions on a message, as the phone has them. */
    fun myReactionsOn(messageId: String): List<String> =
        find(messageId)
            ?.reactions
            ?.filter { ME in it.participantIds }
            ?.map { it.emoji }
            .orEmpty()

    fun find(messageId: String): GmMessage? =
        messages.values.firstNotNullOfOrNull { list ->
            list.firstOrNull {
                it.id ==
                    messageId
            }
        }

    private fun other(conversationId: String): GmParticipant =
        conversations.getValue(conversationId).participants.first { !it.isMe && it.isVisible }

    internal fun send(request: GmSendRequest): GmMessage {
        val message =
            GmMessage(
                id = "s${ids.incrementAndGet()}",
                conversationId = request.conversationId,
                participantId = ME,
                timestamp = now(),
                status = OUTGOING_COMPLETE,
                statusName = "OUTGOING_COMPLETE",
                direction = "outgoing",
                fromMe = true,
                sent = true,
                transport = if (conversations[request.conversationId]?.outgoingIsRcs == true) "RCS" else "SMS",
                tmpId = request.tmpId,
                text = request.text,
                media = request.media,
                replyToId = request.replyToId,
            )
        messages.getOrPut(request.conversationId) { mutableListOf() }.add(0, message)
        if (request.text.isNotEmpty()) sentTexts.getOrPut(request.conversationId) { mutableListOf() }.add(request.text)
        emit(GmEvent.Message(message))
        return message
    }

    internal fun react(
        messageId: String,
        emoji: String,
        action: String,
    ) {
        val message = requireNotNull(find(messageId)) { "REJECTED: no such message" }
        val without =
            message.reactions.mapNotNull { r ->
                r.copy(participantIds = r.participantIds - ME).takeIf { it.participantIds.isNotEmpty() }
            }
        val reactions = if (action == "remove") without else without + GmReaction(emoji, listOf(ME))
        val updated = message.copy(reactions = reactions)
        replace(updated)
        emit(GmEvent.Message(updated))
    }

    internal fun delete(messageId: String) {
        val message = requireNotNull(find(messageId)) { "REJECTED: no such message" }
        messages.getValue(message.conversationId).removeAll { it.id == messageId }
        emit(GmEvent.Message(message.copy(direction = "deleted", status = DELETED, statusName = "MESSAGE_DELETED")))
    }

    internal fun openConversation(
        numbers: List<String>,
        name: String,
    ): GmConversation {
        val id = "n${ids.incrementAndGet()}"
        val me = GmParticipant(ME, "+15555550100", isMe = true, isVisible = true)
        val others = numbers.mapIndexed { i, n -> GmParticipant("p$id$i", n, formattedNumber = n, isVisible = true) }
        val conversation =
            GmConversation(
                id = id,
                name = name,
                isGroup = numbers.size > 1,
                type = "RCS",
                participants = listOf(me) + others,
                otherParticipantIds = others.map { it.id },
                outgoingId = ME,
                outgoingIsRcs = true,
                lastMessageAt = now(),
            )
        conversations[id] = conversation
        messages[id] = mutableListOf()
        return conversation
    }

    internal fun page(
        conversationId: String,
        count: Int,
        cursor: GmCursor?,
    ): GmMessagePage {
        val all =
            messages[conversationId]
                .orEmpty()
                .sortedByDescending { it.timestamp }
                .filter { cursor == null || it.timestamp / MICROS < cursor.lastItemTimestamp }
        val page = all.take(count)
        val next = page.lastOrNull()?.takeIf { all.size > count }?.let { GmCursor(it.id, it.timestamp / MICROS) }
        return GmMessagePage(page, next, all.size.toLong())
    }

    private fun replace(message: GmMessage) {
        val list = messages.getValue(message.conversationId)
        val index = list.indexOfFirst { it.id == message.id }
        if (index >= 0) list[index] = message
    }

    companion object {
        const val ME = "2"
        const val MICROS = 1000L
        const val INCOMING_COMPLETE = 100
        const val OUTGOING_COMPLETE = 1
        const val DELETED = 300
    }
}

private class FakeSession(
    private val phone: FakePhone,
    private val sink: GmEventSink,
) : GmSession {
    override fun connect() = phone.attach(sink)

    override fun disconnect() {
        if (phone.sink === sink) phone.sink = null
    }

    override fun authJson() = FakeGmBridge.AUTH

    override fun setActive() = Unit

    override fun unpair() = Unit

    override fun listConversations(
        folder: String,
        count: Int,
        cursorJson: String,
    ): String =
        gmJson.encodeToString(GmConversationPage.serializer(), GmConversationPage(phone.conversations.values.toList()))

    override fun getConversation(conversationId: String): String =
        gmJson.encodeToString(GmConversation.serializer(), phone.conversations.getValue(conversationId))

    override fun fetchMessages(
        conversationId: String,
        count: Int,
        cursorJson: String,
    ): String {
        val cursor = cursorJson.takeIf { it.isNotEmpty() }?.let { gmJson.decodeFromString(GmCursor.serializer(), it) }
        return gmJson.encodeToString(GmMessagePage.serializer(), phone.page(conversationId, count, cursor))
    }

    override fun sendMessage(requestJson: String) {
        phone.send(gmJson.decodeFromString(GmSendRequest.serializer(), requestJson))
    }

    override fun uploadMedia(
        path: String,
        fileName: String,
        mime: String,
    ): String =
        gmJson.encodeToString(
            GmMedia.serializer(),
            GmMedia(mediaId = "m-$fileName", name = fileName, mime = mime, size = File(path).length(), key = "a2V5"),
        )

    override fun downloadMedia(
        mediaId: String,
        keyBase64: String,
        destPath: String,
    ) {
        File(destPath).writeBytes("fake media $mediaId".toByteArray())
    }

    override fun requestFullSizeMedia(
        messageId: String,
        partId: String,
    ) = Unit

    override fun sendReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
        action: String,
    ) = phone.react(messageId, emoji, action)

    override fun deleteMessage(messageId: String) = phone.delete(messageId)

    override fun markRead(
        conversationId: String,
        messageId: String,
    ) {
        phone.readMarks[conversationId] = messageId
    }

    override fun setTyping(conversationId: String) {
        phone.typingSent.incrementAndGet()
    }

    override fun getOrCreateConversation(
        numbersJson: String,
        groupName: String,
    ): String {
        val numbers = gmJson.decodeFromString(ListSerializer(String.serializer()), numbersJson)
        return gmJson.encodeToString(GmConversation.serializer(), phone.openConversation(numbers, groupName))
    }
}

class MemoryCredentialStore : CredentialStore {
    private val secrets = ConcurrentHashMap<String, ByteArray>()

    override suspend fun save(
        ref: String,
        secret: ByteArray,
    ) {
        secrets[ref] = secret
    }

    override suspend fun load(ref: String) = secrets[ref]

    override suspend fun delete(ref: String) {
        secrets.remove(ref)
    }
}
