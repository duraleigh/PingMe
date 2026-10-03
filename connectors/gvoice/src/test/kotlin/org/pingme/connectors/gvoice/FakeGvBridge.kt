// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice

import kotlinx.serialization.builtins.ListSerializer
import org.pingme.connectors.gvoice.bridge.GvBridge
import org.pingme.connectors.gvoice.bridge.GvEvent
import org.pingme.connectors.gvoice.bridge.GvEventSink
import org.pingme.connectors.gvoice.bridge.GvMember
import org.pingme.connectors.gvoice.bridge.GvMessage
import org.pingme.connectors.gvoice.bridge.GvSession
import org.pingme.connectors.gvoice.bridge.GvThread
import org.pingme.connectors.gvoice.bridge.gvJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The Go bridge replaced by a pretend Google Voice: any complete set of cookies signs
 * in, connecting lists one thread with a short history, and sends are recorded so the
 * contract test can check them.
 */
class FakeGvBridge : GvBridge {
    val network = FakeGvoice()

    override fun newSession(
        cookiesJson: String,
        sink: GvEventSink,
    ): GvSession = FakeGvSession(network, sink)
}

class FakeGvoice {
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val sessions = CopyOnWriteArrayList<FakeGvSession>()
    private val clock = AtomicLong(1_759_310_000_000)
    private val counter = AtomicLong(100)

    fun now(): Long = clock.addAndGet(1000)

    fun nextId(): String = "i${counter.incrementAndGet()}"

    fun receive(
        thread: String,
        text: String,
    ) {
        val message = GvMessage(nextId(), thread, SAM, false, now(), "text", text)
        sessions.forEach { it.emit(GvEvent.Message(message)) }
    }

    companion object {
        const val ME = "+15555550100"
        const val SAM = "+15555550123"
        const val THREAD = "t.+15555550123"
    }
}

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
class FakeGvSession(
    private val network: FakeGvoice,
    private val sink: GvEventSink,
) : GvSession {
    @Volatile var connected = false
    private val pending = CopyOnWriteArrayList<GvEvent>()

    init {
        network.sessions += this
    }

    fun emit(event: GvEvent) {
        if (!connected) {
            pending += event
            return
        }
        sink.onEvent(gvJson.encodeToString(GvEvent.serializer(), event))
    }

    override fun cookiesJson(): String = """{"SID":"s","HSID":"h","SSID":"s","APISID":"a","SAPISID":"s"}"""

    override fun ownPhone(): String = FakeGvoice.ME

    override fun check(): String = FakeGvoice.ME

    override fun connect() {
        connected = true
        emit(GvEvent.Connected(FakeGvoice.ME))
        emit(GvEvent.Contacts(listOf(GvMember(FakeGvoice.SAM, "Sam Ortiz"))))
        emit(GvEvent.Thread(samThread()))
        emit(GvEvent.InboxLoaded)
        pending.forEach { emit(it) }
        pending.clear()
    }

    override fun disconnect() {
        connected = false
    }

    override fun close() = disconnect()

    override fun threads(): String = gvJson.encodeToString(ListSerializer(GvThread.serializer()), listOf(samThread()))

    override fun thread(
        threadId: String,
        count: Int,
        pageToken: String,
    ): String {
        val all = samHistory()
        val from = pageToken.removePrefix("p").toIntOrNull() ?: 0
        val page = all.drop(from).take(count)
        val next = if (from + page.size < all.size) "p${from + page.size}" else ""
        return gvJson.encodeToString(GvThread.serializer(), samThread().copy(messages = page, paginationToken = next))
    }

    override fun nameOf(phone: String): String = if (phone == FakeGvoice.SAM) "Sam Ortiz" else ""

    override fun sendText(
        threadId: String,
        text: String,
    ): String {
        network.sentTexts.getOrPut(threadId) { CopyOnWriteArrayList() } += text
        return gvJson.encodeToString(
            GvMessage.serializer(),
            GvMessage(network.nextId(), threadId, "", true, network.now(), "text", text, read = true),
        )
    }

    override fun sendMedia(
        threadId: String,
        path: String,
        mime: String,
        text: String,
    ): String =
        gvJson.encodeToString(
            GvMessage.serializer(),
            GvMessage(network.nextId(), threadId, "", true, network.now(), "media", text, read = true),
        )

    override fun markRead(threadId: String) = Unit

    override fun block(threadId: String) = Unit

    override fun deleteThread(threadId: String) = Unit

    override fun download(
        mediaId: String,
        destPath: String,
    ): String {
        File(destPath).writeBytes(byteArrayOf(1, 2, 3))
        return "image/jpeg"
    }

    private fun samThread() =
        GvThread(
            FakeGvoice.THREAD,
            phoneNumbers = listOf(FakeGvoice.SAM),
            contacts = listOf(GvMember(FakeGvoice.SAM, "Sam Ortiz")),
            read = false,
            lastAt = 1_759_300_003_000,
            messages = samHistory(),
        )

    private fun samHistory(): List<GvMessage> =
        listOf(
            GvMessage("i3", FakeGvoice.THREAD, FakeGvoice.SAM, false, 1_759_300_003_000, "text", "Did you see this?"),
            GvMessage("i2", FakeGvoice.THREAD, "", true, 1_759_300_002_000, "text", "Sounds good", read = true),
            GvMessage(
                "i1",
                FakeGvoice.THREAD,
                FakeGvoice.SAM,
                false,
                1_759_300_001_000,
                "text",
                "Lunch at noon?",
                read = true,
            ),
        )
}
