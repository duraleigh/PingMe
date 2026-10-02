// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

import kotlinx.serialization.builtins.ListSerializer
import org.pingme.connectors.whatsapp.bridge.WaBridge
import org.pingme.connectors.whatsapp.bridge.WaChat
import org.pingme.connectors.whatsapp.bridge.WaEvent
import org.pingme.connectors.whatsapp.bridge.WaEventSink
import org.pingme.connectors.whatsapp.bridge.WaMedia
import org.pingme.connectors.whatsapp.bridge.WaMessage
import org.pingme.connectors.whatsapp.bridge.WaParticipant
import org.pingme.connectors.whatsapp.bridge.WaSession
import org.pingme.connectors.whatsapp.bridge.waJson
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The Go bridge replaced by a pretend WhatsApp: linking succeeds with any number, the
 * connection brings a short history, and sends, reactions, and revokes are recorded so
 * the contract test can check them.
 */
class FakeWaBridge : WaBridge {
    val network = FakeWhatsapp()

    override fun newSession(
        dbPath: String,
        sink: WaEventSink,
    ): WaSession = FakeWaSession(network, dbPath, sink)
}

/** The pretend network's state and its test hooks. */
class FakeWhatsapp {
    val linked = ConcurrentHashMap<String, String>()
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val reactions = ConcurrentHashMap<String, MutableList<String>>()
    val revoked = CopyOnWriteArraySet<String>()
    val sessions = CopyOnWriteArrayList<FakeWaSession>()
    private val clock = AtomicLong(1_759_310_000_000)

    fun now(): Long = clock.addAndGet(1000)

    /** Someone else sends [text] into [chat]. */
    fun receive(
        chat: String,
        text: String,
    ) {
        val message =
            WaMessage(
                id =
                    UUID
                        .randomUUID()
                        .toString()
                        .uppercase()
                        .take(ID_LENGTH),
                chat = chat,
                sender = if (chat.endsWith("@g.us")) SAM else chat,
                senderPhone = SAM.substringBefore('@'),
                pushName = "Sam",
                fromMe = false,
                timestamp = now(),
                kind = "text",
                text = text,
            )
        sessions.filter { it.isLoggedIn() }.forEach { it.emit(WaEvent.Message(message)) }
    }

    fun remoteTyping(chat: String) {
        sessions.filter { it.isLoggedIn() }.forEach { it.emit(WaEvent.Typing(chat, SAM, typing = true)) }
    }

    fun myReactionsOn(key: String): List<String> = reactions[key].orEmpty()

    companion object {
        const val SAM = "15555550123@s.whatsapp.net"
        const val ME = "15555550100@s.whatsapp.net"
        const val GROUP = "120363@g.us"
        const val COMMUNITY = "120999@g.us"
        private const val ID_LENGTH = 20
    }
}

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
class FakeWaSession(
    private val network: FakeWhatsapp,
    private val dbPath: String,
    private val sink: WaEventSink,
) : WaSession {
    @Volatile var connected = false
    private val pending = CopyOnWriteArrayList<WaEvent>()

    init {
        network.sessions += this
    }

    /** Delivers now, or once connected, as WhatsApp would queue what came while offline. */
    fun emit(event: WaEvent) {
        if (!connected) {
            pending += event
            return
        }
        sink.onEvent(waJson.encodeToString(WaEvent.serializer(), event))
    }

    override fun isLoggedIn(): Boolean = network.linked.containsKey(dbPath)

    override fun ownId(): String = FakeWhatsapp.ME

    override fun ownPhone(): String = FakeWhatsapp.ME.substringBefore('@')

    override fun ownLid(): String = "9876@lid"

    override fun pushName(): String = "Owner"

    override fun connect() {
        if (!isLoggedIn()) throw IllegalStateException("NOT_LOGGED_IN: this phone number is not linked")
        connected = true
        // Delivered before connect returns, as a queued stream would be read right after connecting.
        emit(WaEvent.Connected(ownId(), ownPhone(), ownLid(), pushName()))
        emit(WaEvent.History("RECENT", 100, samChat(), samHistory()))
        emit(WaEvent.History("RECENT", 100, group(), emptyList()))
        pending.forEach { emit(it) }
        pending.clear()
    }

    override fun disconnect() {
        connected = false
    }

    override fun close() = disconnect()

    override fun logout() {
        network.linked.remove(dbPath)
    }

    override fun pairCode(phone: String): String {
        require(phone.length >= PHONE_MIN) { "phone number too short" }
        network.linked[dbPath] = phone
        // Pairing connects first, as the real bridge does, so its answer is not queued.
        connected = true
        thread(isDaemon = true) {
            Thread.sleep(PAIR_DELAY_MS)
            emit(WaEvent.PairSuccess(ownId(), ownPhone()))
        }
        return "ABCD-EFGH"
    }

    override fun listGroups(): String =
        waJson.encodeToString(ListSerializer(WaChat.serializer()), listOf(group(), community()))

    override fun groupInfo(jid: String): String = waJson.encodeToString(WaChat.serializer(), group())

    override fun contacts(): String =
        waJson.encodeToString(
            ListSerializer(WaParticipant.serializer()),
            listOf(WaParticipant(FakeWhatsapp.SAM, "15555550123", "Sam Ortiz")),
        )

    override fun contactName(jid: String): String = if (jid == FakeWhatsapp.SAM) "Sam Ortiz" else ""

    override fun phoneOf(jid: String): String = ""

    override fun sendText(
        chat: String,
        text: String,
        replyJson: String,
    ): String {
        network.sentTexts.getOrPut(chat) { CopyOnWriteArrayList() } += text
        return waJson.encodeToString(WaMessage.serializer(), mine(chat, "text", text))
    }

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
    ): String {
        val media =
            WaMedia(
                type = "WhatsApp Image Keys",
                mime = mime,
                directPath = "/v/$fileName",
                fileName = fileName,
                fileLength = File(path).length(),
            )
        return waJson.encodeToString(WaMessage.serializer(), mine(chat, kind, caption).copy(media = media))
    }

    private fun mine(
        chat: String,
        kind: String,
        text: String,
    ) = WaMessage(
        id =
            UUID
                .randomUUID()
                .toString()
                .uppercase()
                .take(ID_LENGTH),
        chat = chat,
        sender = ownId(),
        senderPhone = ownPhone(),
        fromMe = true,
        timestamp = network.now(),
        kind = kind,
        text = text,
        status = "sent",
    )

    override fun sendReaction(
        chat: String,
        targetId: String,
        targetSender: String,
        targetFromMe: Boolean,
        emoji: String,
    ) {
        val list = network.reactions.getOrPut("$chat/$targetId") { CopyOnWriteArrayList() }
        list.clear()
        if (emoji.isNotEmpty()) list += emoji
    }

    override fun revoke(
        chat: String,
        targetId: String,
        targetSender: String,
        targetFromMe: Boolean,
    ) {
        network.revoked += "$chat/$targetId"
    }

    override fun edit(
        chat: String,
        targetId: String,
        text: String,
    ) = Unit

    override fun markRead(
        chat: String,
        sender: String,
        idsJson: String,
        timestamp: Long,
    ) = Unit

    override fun setTyping(
        chat: String,
        typing: Boolean,
    ) = Unit

    override fun download(
        mediaJson: String,
        destPath: String,
    ) {
        File(destPath).writeBytes(byteArrayOf(1, 2, 3))
    }

    override fun requestHistory(
        chat: String,
        lastId: String,
        lastTimestamp: Long,
        lastFromMe: Boolean,
        count: Int,
    ) = Unit

    override fun checkNumber(phone: String): String = "$phone@s.whatsapp.net"

    override fun createGroup(
        name: String,
        participantsJson: String,
    ): String = waJson.encodeToString(WaChat.serializer(), group().copy(id = "120777@g.us", name = name))

    override fun block(jid: String) = Unit

    override fun profilePictureUrl(jid: String): String = ""

    private fun samChat() =
        WaChat(
            FakeWhatsapp.SAM,
            name = "",
            isGroup = false,
            unread = 1,
            lastMessageAt = 1_759_310_000_000,
            participants = listOf(WaParticipant(FakeWhatsapp.SAM, "15555550123")),
        )

    private fun samHistory() =
        listOf(
            WaMessage(
                "H3",
                FakeWhatsapp.SAM,
                FakeWhatsapp.SAM,
                "15555550123",
                "Sam",
                false,
                1_759_310_000_000,
                "text",
                "See you at 7",
            ),
            WaMessage(
                "H2",
                FakeWhatsapp.SAM,
                ownId(),
                ownPhone(),
                "",
                true,
                1_759_309_000_000,
                "text",
                "Leaving now",
                status = "read",
            ),
            WaMessage(
                "H1",
                FakeWhatsapp.SAM,
                FakeWhatsapp.SAM,
                "15555550123",
                "Sam",
                false,
                1_759_308_000_000,
                "text",
                "What time?",
            ),
        )

    private fun group() =
        WaChat(
            FakeWhatsapp.GROUP,
            name = "Hiking crew",
            isGroup = true,
            lastMessageAt = 1_759_200_000_000,
            communityId = FakeWhatsapp.COMMUNITY,
            participants =
                listOf(
                    WaParticipant(ownId(), ownPhone(), isMe = true, isAdmin = true),
                    WaParticipant(FakeWhatsapp.SAM, "15555550123", "Sam Ortiz"),
                ),
        )

    private fun community() = WaChat(FakeWhatsapp.COMMUNITY, name = "Outdoors", isGroup = true, isCommunity = true)

    private companion object {
        const val ID_LENGTH = 20
        const val PHONE_MIN = 7
        const val PAIR_DELAY_MS = 50L
    }
}
