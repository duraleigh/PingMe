// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.signal.bridge.SigBridge
import org.pingme.connectors.signal.bridge.SigChat
import org.pingme.connectors.signal.bridge.SigEvent
import org.pingme.connectors.signal.bridge.SigEventSink
import org.pingme.connectors.signal.bridge.SigLookup
import org.pingme.connectors.signal.bridge.SigMember
import org.pingme.connectors.signal.bridge.SigMessage
import org.pingme.connectors.signal.bridge.SigReaction
import org.pingme.connectors.signal.bridge.SigSession
import org.pingme.connectors.signal.bridge.SigTarget
import org.pingme.connectors.signal.bridge.sigJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * The Go bridge replaced by a pretend Signal: linking shows one QR code and succeeds at
 * once, connecting lists one chat with a short transferred history, and sends,
 * reactions, and deletes are recorded so the contract test can check them.
 */
class FakeSigBridge : SigBridge {
    val network = FakeSignal()

    override fun newSession(
        dbPath: String,
        sink: SigEventSink,
    ): SigSession = FakeSigSession(network, sink, File(dbPath))
}

/** The pretend network's state and its test hooks. */
class FakeSignal {
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val reactions = ConcurrentHashMap<String, MutableList<String>>()
    val revoked = CopyOnWriteArraySet<String>()
    val sessions = CopyOnWriteArrayList<FakeSigSession>()
    private val clock = AtomicLong(1_759_310_000_000)

    fun now(): Long = clock.addAndGet(1000)

    fun receive(
        chat: String,
        text: String,
    ) {
        val ts = now()
        val message = SigMessage("$SAM:$ts", chat, SAM, "+15555550123", false, ts, "text", text)
        sessions.forEach { it.emit(SigEvent.Message(message)) }
    }

    fun remoteTyping(chat: String) {
        sessions.forEach { it.emit(SigEvent.Typing(chat, SAM, typing = true)) }
    }

    companion object {
        const val ME = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val SAM = "11111111-2222-3333-4444-555555555555"
        const val PHONE = "+15555550100"
    }
}

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
class FakeSigSession(
    private val network: FakeSignal,
    private val sink: SigEventSink,
    private val db: File,
) : SigSession {
    @Volatile var connected = false
    private val pending = CopyOnWriteArrayList<SigEvent>()

    init {
        network.sessions += this
    }

    /** Delivers now, or once connected, as Signal would hold what came while offline. */
    fun emit(event: SigEvent) {
        if (!connected) {
            pending += event
            return
        }
        sink.onEvent(sigJson.encodeToString(SigEvent.serializer(), event))
    }

    override fun isLoggedIn(): Boolean = db.exists()

    override fun ownId(): String = FakeSignal.ME

    override fun ownPhone(): String = FakeSignal.PHONE

    override fun startLink(deviceName: String) {
        sink.onEvent(
            sigJson.encodeToString(SigEvent.serializer(), SigEvent.LinkQr("sgnl://linkdevice?uuid=x&pub_key=y", 1)),
        )
        db.parentFile?.mkdirs()
        db.writeText("linked")
        sink.onEvent(sigJson.encodeToString(SigEvent.serializer(), SigEvent.LinkDone(FakeSignal.ME, FakeSignal.PHONE)))
    }

    override fun cancelLink() = Unit

    override fun connect() {
        require(db.exists()) { "NOT_LINKED: this Signal account is not linked" }
        connected = true
        emit(SigEvent.Connected(ownId(), ownPhone()))
        emit(SigEvent.Transfer("done"))
        emit(SigEvent.Chats(listOf(samChat())))
        pending.forEach { emit(it) }
        pending.clear()
    }

    override fun disconnect() {
        connected = false
    }

    override fun close() = disconnect()

    override fun unlink() {
        db.delete()
    }

    override fun contacts(): String =
        sigJson.encodeToString(
            ListSerializer(SigMember.serializer()),
            listOf(SigMember(FakeSignal.SAM, "+15555550123", "Sam Ortiz")),
        )

    override fun chats(): String = sigJson.encodeToString(ListSerializer(SigChat.serializer()), listOf(samChat()))

    override fun chatInfo(chat: String): String = sigJson.encodeToString(SigChat.serializer(), samChat())

    override fun messages(
        chat: String,
        beforeTimestamp: Long,
        count: Int,
    ): String {
        val all = samHistory().filter { beforeTimestamp == 0L || it.timestamp < beforeTimestamp }
        return sigJson.encodeToString(ListSerializer(SigMessage.serializer()), all.take(count))
    }

    override fun sendText(
        chat: String,
        text: String,
        quoteJson: String,
    ): String {
        network.sentTexts.getOrPut(chat) { CopyOnWriteArrayList() } += text
        val ts = network.now()
        return sigJson.encodeToString(
            SigMessage.serializer(),
            SigMessage("${FakeSignal.ME}:$ts", chat, FakeSignal.ME, "", true, ts, "text", text, status = "sent"),
        )
    }

    override fun sendMedia(
        chat: String,
        path: String,
        mime: String,
        fileName: String,
        caption: String,
        voice: Boolean,
        quoteJson: String,
    ): String {
        val ts = network.now()
        val kind =
            if (voice) {
                "voice"
            } else if (mime.startsWith("image/")) {
                "image"
            } else {
                "document"
            }
        return sigJson.encodeToString(
            SigMessage.serializer(),
            SigMessage("${FakeSignal.ME}:$ts", chat, FakeSignal.ME, "", true, ts, kind, caption, status = "sent"),
        )
    }

    override fun sendReaction(
        chat: String,
        targetId: String,
        emoji: String,
        remove: Boolean,
    ) {
        val list = network.reactions.getOrPut("$chat/$targetId") { CopyOnWriteArrayList() }
        if (remove) list.remove(emoji) else list += emoji
    }

    override fun revoke(
        chat: String,
        targetId: String,
    ) {
        network.revoked += "$chat/$targetId"
        val ts = network.now()
        val revoke =
            SigMessage(
                "${FakeSignal.ME}:$ts",
                chat,
                FakeSignal.ME,
                "",
                true,
                ts,
                "revoke",
                revoke = SigTarget(targetId.substringAfter(':').toLong()),
            )
        emit(SigEvent.Message(revoke))
    }

    override fun edit(
        chat: String,
        targetId: String,
        text: String,
    ): String {
        val ts = network.now()
        return sigJson.encodeToString(
            SigMessage.serializer(),
            SigMessage("${FakeSignal.ME}:$ts", chat, FakeSignal.ME, "", true, ts, "edit", text),
        )
    }

    override fun markRead(
        chat: String,
        idsJson: String,
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

    /** Sam and anyone whose number ends in an even digit are on the pretend Signal; the odd ones are not. */
    override fun lookupNumbers(phonesJson: String): String {
        val phones = sigJson.decodeFromString(ListSerializer(String.serializer()), phonesJson)
        val found =
            phones.filter { it == "+15555550123" || it.last().digitToInt() % 2 == 0 }.map {
                SigLookup(
                    it,
                    if (it ==
                        "+15555550123"
                    ) {
                        FakeSignal.SAM
                    } else {
                        "PNI:" + java.util.UUID.nameUUIDFromBytes(it.toByteArray())
                    },
                )
            }
        return sigJson.encodeToString(ListSerializer(SigLookup.serializer()), found)
    }

    /** Every number is on the pretend Signal: Sam's is Sam, any other gets an id of its own. */
    override fun checkNumber(phone: String): String =
        if (phone ==
            "+15555550123"
        ) {
            FakeSignal.SAM
        } else {
            java.util.UUID
                .nameUUIDFromBytes(phone.toByteArray())
                .toString()
        }

    private fun samChat() =
        SigChat(
            FakeSignal.SAM,
            isGroup = false,
            name = "Sam Ortiz",
            members = listOf(SigMember(FakeSignal.SAM, "+15555550123", "Sam Ortiz")),
            unread = 1,
            lastAt = 1_759_300_003_000,
            count = 3,
        )

    private fun samHistory(): List<SigMessage> =
        listOf(
            SigMessage(
                "${FakeSignal.SAM}:1759300003000",
                FakeSignal.SAM,
                FakeSignal.SAM,
                "+15555550123",
                false,
                1_759_300_003_000,
                "text",
                "Did you see this?",
            ),
            SigMessage(
                "${FakeSignal.ME}:1759300002000",
                FakeSignal.SAM,
                FakeSignal.ME,
                "",
                true,
                1_759_300_002_000,
                "text",
                "Sounds good",
                status = "read",
                reactions = listOf(SigReaction(sender = FakeSignal.SAM, emoji = "👍", timestamp = 1_759_300_002_500)),
            ),
            SigMessage(
                "${FakeSignal.SAM}:1759300001000",
                FakeSignal.SAM,
                FakeSignal.SAM,
                "+15555550123",
                false,
                1_759_300_001_000,
                "text",
                "Lunch at noon?",
            ),
        )
}
