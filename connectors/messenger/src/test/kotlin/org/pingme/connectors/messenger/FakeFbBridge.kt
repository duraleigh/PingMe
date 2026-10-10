// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.messenger.bridge.FbBridge
import org.pingme.connectors.messenger.bridge.FbEvent
import org.pingme.connectors.messenger.bridge.FbEventSink
import org.pingme.connectors.messenger.bridge.FbMedia
import org.pingme.connectors.messenger.bridge.FbMessage
import org.pingme.connectors.messenger.bridge.FbSession
import org.pingme.connectors.messenger.bridge.FbThread
import org.pingme.connectors.messenger.bridge.FbUser
import org.pingme.connectors.messenger.bridge.fbJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * The Go bridge replaced by a pretend Messenger: any complete set of cookies signs in,
 * connecting lists one chat with a short history and one request, and sends, reactions,
 * and unsends are recorded so the contract test can check them.
 */
class FakeFbBridge : FbBridge {
    val network = FakeMessenger()

    override fun newSession(
        cookiesJson: String,
        storePath: String,
        sink: FbEventSink,
    ): FbSession {
        val cookies = fbJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), cookiesJson)
        val complete = listOf("xs", "c_user", "datr").all { !cookies[it].isNullOrEmpty() }
        require(complete) { "LOGGED_OUT: sign-in cookies are incomplete" }
        return FakeFbSession(network, sink)
    }
}

/** The pretend network's state and its test hooks. */
class FakeMessenger {
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val reactions = ConcurrentHashMap<String, MutableList<String>>()
    val unsent = CopyOnWriteArraySet<String>()
    val accepted = CopyOnWriteArraySet<String>()
    val sessions = CopyOnWriteArrayList<FakeFbSession>()
    private val clock = AtomicLong(1_759_310_000_000)
    private val counter = AtomicLong(100)

    fun now(): Long = clock.addAndGet(1000)

    fun nextId(): String = "mid.\$m${counter.incrementAndGet()}"

    fun receive(
        thread: String,
        text: String,
    ) {
        val message = FbMessage(nextId(), thread, SAM, now(), "text", text)
        sessions.forEach { it.emit(FbEvent.Message(message)) }
    }

    fun remoteTyping(thread: String) {
        sessions.forEach { it.emit(FbEvent.Typing(thread, SAM, typing = true)) }
    }

    companion object {
        const val ME = "100"
        const val SAM = "200"
        const val THREAD = "200"
        const val REQUEST = "300"
    }
}

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
class FakeFbSession(
    private val network: FakeMessenger,
    private val sink: FbEventSink,
) : FbSession {
    @Volatile var connected = false
    private val pending = CopyOnWriteArrayList<FbEvent>()
    private val threads = ConcurrentHashMap<String, FbThread>()

    init {
        network.sessions += this
    }

    /** Delivers now, or once connected, as Messenger would hold what came while offline. */
    fun emit(event: FbEvent) {
        if (!connected) {
            pending += event
            return
        }
        sink.onEvent(fbJson.encodeToString(FbEvent.serializer(), event))
    }

    override fun cookiesJson(): String = """{"xs":"x","c_user":"${FakeMessenger.ME}","datr":"d"}"""

    override fun ownId(): String = FakeMessenger.ME

    override fun connect() {
        connected = true
        emit(FbEvent.Connected(ownId(), cookiesJson()))
        threads[FakeMessenger.THREAD] = samThread()
        emit(FbEvent.Thread(samThread()))
        emit(FbEvent.InboxLoaded)
        emit(FbEvent.Live)
        pending.forEach { emit(it) }
        pending.clear()
    }

    override fun disconnect() {
        connected = false
    }

    override fun moreThreads(): Boolean {
        threads[FakeMessenger.REQUEST] = requestThread()
        emit(FbEvent.Thread(requestThread()))
        return false
    }

    override fun threads(): String =
        fbJson.encodeToString(
            ListSerializer(FbThread.serializer()),
            threads.values.sortedByDescending { it.lastMessageAt },
        )

    override fun thread(id: String): String = fbJson.encodeToString(FbThread.serializer(), threads.getValue(id))

    override fun messages(
        thread: String,
        olderThan: String,
    ): String {
        val all = samHistory()
        val from = if (olderThan.isEmpty()) 0 else all.indexOfFirst { it.id == olderThan } + 1
        return fbJson.encodeToString(ListSerializer(FbMessage.serializer()), all.drop(from))
    }

    override fun sendText(
        thread: String,
        text: String,
        replyTo: String,
    ): String {
        network.sentTexts.getOrPut(thread) { CopyOnWriteArrayList() } += text
        return fbJson.encodeToString(
            FbMessage.serializer(),
            FbMessage(network.nextId(), thread, ownId(), network.now(), "text", text, replyTo = replyTo),
        )
    }

    override fun sendMedia(
        thread: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        text: String,
        replyTo: String,
    ): String =
        fbJson.encodeToString(
            FbMessage.serializer(),
            FbMessage(
                network.nextId(),
                thread,
                ownId(),
                network.now(),
                kind,
                text,
                media = listOf(FbMedia(kind, "https://cdn/$fileName", mime = mime)),
            ),
        )

    override fun sendReaction(
        thread: String,
        messageId: String,
        emoji: String,
        remove: Boolean,
    ) {
        val list = network.reactions.getOrPut("$thread/$messageId") { CopyOnWriteArrayList() }
        list.clear()
        if (!remove) list += emoji
    }

    override fun unsend(messageId: String) {
        network.unsent += "${FakeMessenger.THREAD}/$messageId"
    }

    override fun edit(
        messageId: String,
        text: String,
    ) = Unit

    override fun markRead(
        thread: String,
        timestamp: Long,
    ) = Unit

    override fun setTyping(
        thread: String,
        typing: Boolean,
    ) = Unit

    override fun acceptRequest(thread: String) {
        network.accepted += thread
    }

    override fun deleteThread(thread: String) {
        threads.remove(thread)
    }

    override fun download(
        url: String,
        mime: String,
        destPath: String,
    ) {
        File(destPath).writeBytes(byteArrayOf(1, 2, 3))
    }

    override fun searchUsers(query: String): String =
        fbJson.encodeToString(ListSerializer(FbUser.serializer()), listOf(FbUser("400", "Dee Found")))

    override fun startChat(userId: String): String {
        threads.putIfAbsent(userId, FbThread(userId, users = listOf(users()[1], FbUser(userId, "Dee Found"))))
        return userId
    }

    private fun users() =
        listOf(
            FbUser(FakeMessenger.SAM, "Sam Ortiz", "https://p/200"),
            FbUser(FakeMessenger.ME, "Me", isMe = true),
        )

    private fun samThread() =
        FbThread(
            FakeMessenger.THREAD,
            title = "Sam Ortiz",
            folder = "inbox",
            lastMessageAt = 1_759_310_000_000,
            readAt = 1_759_300_000_000,
            users = users(),
            messages = samHistory().take(2),
            moreBefore = true,
        )

    private fun requestThread() =
        FbThread(
            FakeMessenger.REQUEST,
            title = "A Stranger",
            folder = "pending",
            lastMessageAt = 1_759_200_000_000,
            users = listOf(users()[1], FbUser("300", "A Stranger")),
            messages = listOf(FbMessage("mid.\$r1", FakeMessenger.REQUEST, "300", 1_759_200_000_000, "text", "hey")),
        )

    private fun samHistory() =
        listOf(
            FbMessage("mid.\$h3", FakeMessenger.THREAD, FakeMessenger.SAM, 1_759_310_000_000, "text", "See you at 7"),
            FbMessage("mid.\$h2", FakeMessenger.THREAD, FakeMessenger.ME, 1_759_309_000_000, "text", "Leaving now"),
            FbMessage("mid.\$h1", FakeMessenger.THREAD, FakeMessenger.SAM, 1_759_308_000_000, "text", "What time?"),
        )
}
