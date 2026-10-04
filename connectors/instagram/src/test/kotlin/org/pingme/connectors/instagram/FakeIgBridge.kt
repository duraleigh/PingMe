// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.instagram.bridge.IgBridge
import org.pingme.connectors.instagram.bridge.IgEvent
import org.pingme.connectors.instagram.bridge.IgEventSink
import org.pingme.connectors.instagram.bridge.IgMedia
import org.pingme.connectors.instagram.bridge.IgMessage
import org.pingme.connectors.instagram.bridge.IgSession
import org.pingme.connectors.instagram.bridge.IgThread
import org.pingme.connectors.instagram.bridge.IgThreadPage
import org.pingme.connectors.instagram.bridge.IgUser
import org.pingme.connectors.instagram.bridge.igJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * The Go bridge replaced by a pretend Instagram: any complete set of cookies signs in,
 * connecting lists one chat with a short history and one request, and sends, reactions,
 * and unsends are recorded so the contract test can check them.
 */
class FakeIgBridge : IgBridge {
    val network = FakeInstagram()

    override fun newSession(
        cookiesJson: String,
        sink: IgEventSink,
    ): IgSession {
        val cookies = igJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), cookiesJson)
        val complete = listOf("sessionid", "csrftoken", "ds_user_id").all { !cookies[it].isNullOrEmpty() }
        require(complete) { "LOGGED_OUT: sign-in cookies are incomplete" }
        return FakeIgSession(network, sink)
    }
}

/** The pretend network's state and its test hooks. */
class FakeInstagram {
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val reactions = ConcurrentHashMap<String, MutableList<String>>()
    val unsent = CopyOnWriteArraySet<String>()
    val accepted = CopyOnWriteArraySet<String>()
    val sessions = CopyOnWriteArrayList<FakeIgSession>()
    private val clock = AtomicLong(1_759_310_000_000)
    private val counter = AtomicLong(100)

    fun now(): Long = clock.addAndGet(1000)

    fun nextId(): String = "m${counter.incrementAndGet()}"

    fun receive(
        thread: String,
        text: String,
    ) {
        val message = IgMessage(nextId(), thread, SAM, now(), "text", text)
        sessions.forEach { it.emit(IgEvent.Message(message)) }
    }

    fun remoteTyping(thread: String) {
        sessions.forEach { it.emit(IgEvent.Typing(thread, SAM, typing = true)) }
    }

    companion object {
        const val ME = "100"
        const val SAM = "200"
        const val THREAD = "340282366841710300949128000"
        const val REQUEST = "340282366841710300949128001"
    }
}

@Suppress("TooManyFunctions") // One function per bridge call; that is the contract.
class FakeIgSession(
    private val network: FakeInstagram,
    private val sink: IgEventSink,
) : IgSession {
    @Volatile var connected = false
    private val pending = CopyOnWriteArrayList<IgEvent>()

    init {
        network.sessions += this
    }

    /** Delivers now, or once connected, as Instagram would hold what came while offline. */
    fun emit(event: IgEvent) {
        if (!connected) {
            pending += event
            return
        }
        sink.onEvent(igJson.encodeToString(IgEvent.serializer(), event))
    }

    override fun cookiesJson(): String = """{"sessionid":"s","csrftoken":"c","ds_user_id":"${FakeInstagram.ME}"}"""

    override fun ownId(): String = FakeInstagram.ME

    override fun connect() {
        connected = true
        emit(IgEvent.Connected(ownId(), cookiesJson()))
        emit(IgEvent.Thread(samThread()))
        emit(IgEvent.InboxLoaded(""))
        pending.forEach { emit(it) }
        pending.clear()
    }

    override fun disconnect() {
        connected = false
    }

    override fun listThreads(
        folder: String,
        cursor: String,
    ): String =
        igJson.encodeToString(
            IgThreadPage.serializer(),
            IgThreadPage(if (folder == "PENDING") listOf(requestThread()) else listOf(samThread())),
        )

    /** Any thread asked for exists: Sam's for his id, a General thread with a named stranger for any other. */
    var mediaUrls: Map<String, String> = emptyMap()

    override fun mediaUrl(
        fbid: String,
        attachmentId: String,
    ): String = mediaUrls[attachmentId] ?: throw IllegalStateException("no address for $attachmentId")

    override fun thread(fbid: String): String =
        igJson.encodeToString(
            IgThread.serializer(),
            if (fbid == FakeInstagram.THREAD) {
                samThread()
            } else {
                IgThread(
                    fbid,
                    longId = "${fbid}L",
                    folder = "GENERAL",
                    lastMessageAt = 1_759_200_000_000,
                    users = listOf(IgUser("400", "6", "newperson", "New Person"), users()[1]),
                )
            },
        )

    override fun messages(
        fbid: String,
        olderThan: String,
        count: Int,
    ): String {
        val all = samHistory()
        val from = if (olderThan.isEmpty()) 0 else all.indexOfFirst { it.id == olderThan } + 1
        return igJson.encodeToString(ListSerializer(IgMessage.serializer()), all.drop(from).take(count))
    }

    override fun sendText(
        fbid: String,
        text: String,
        replyTo: String,
    ): String {
        network.sentTexts.getOrPut(fbid) { CopyOnWriteArrayList() } += text
        return igJson.encodeToString(
            IgMessage.serializer(),
            IgMessage(network.nextId(), fbid, ownId(), network.now(), "text", text),
        )
    }

    override fun sendMedia(
        fbid: String,
        path: String,
        mime: String,
        kind: String,
        fileName: String,
        replyTo: String,
    ): String =
        igJson.encodeToString(
            IgMessage.serializer(),
            IgMessage(
                network.nextId(),
                fbid,
                ownId(),
                network.now(),
                kind,
                media = listOf(IgMedia(kind, "https://cdn/$fileName", mime = mime)),
            ),
        )

    override fun sendReaction(
        fbid: String,
        messageId: String,
        emoji: String,
        remove: Boolean,
    ) {
        val list = network.reactions.getOrPut("$fbid/$messageId") { CopyOnWriteArrayList() }
        list.clear()
        if (!remove) list += emoji
    }

    override fun unsend(
        fbid: String,
        messageId: String,
    ) {
        network.unsent += "$fbid/$messageId"
    }

    override fun edit(
        fbid: String,
        messageId: String,
        text: String,
    ) = Unit

    override fun markRead(
        fbid: String,
        messageId: String,
        timestamp: Long,
    ) = Unit

    override fun setTyping(
        fbid: String,
        typing: Boolean,
    ) = Unit

    override fun acceptRequest(fbid: String) {
        network.accepted += fbid
    }

    override fun deleteThread(fbid: String) = Unit

    override fun download(
        url: String,
        destPath: String,
    ) {
        File(destPath).writeBytes(byteArrayOf(1, 2, 3))
    }

    override fun searchUsers(query: String): String = "[]"

    private fun users() =
        listOf(
            IgUser(FakeInstagram.SAM, "9", "sam", "Sam Ortiz"),
            IgUser(FakeInstagram.ME, "8", "me", "Me", isMe = true),
        )

    private fun samThread() =
        IgThread(
            FakeInstagram.THREAD,
            longId = "${FakeInstagram.THREAD}L",
            lastMessageAt = 1_759_310_000_000,
            readAt = 1_759_300_000_000,
            users = users(),
            messages = samHistory().take(2),
        )

    private fun requestThread() =
        IgThread(
            FakeInstagram.REQUEST,
            longId = "${FakeInstagram.REQUEST}L",
            systemFolder = "PENDING",
            lastMessageAt = 1_759_200_000_000,
            users = listOf(IgUser("300", "7", "stranger", "A Stranger"), users()[1]),
            messages = listOf(IgMessage("r1", FakeInstagram.REQUEST, "300", 1_759_200_000_000, "text", "hey")),
        )

    private fun samHistory() =
        listOf(
            IgMessage("h3", FakeInstagram.THREAD, FakeInstagram.SAM, 1_759_310_000_000, "text", "See you at 7"),
            IgMessage("h2", FakeInstagram.THREAD, FakeInstagram.ME, 1_759_309_000_000, "text", "Leaving now"),
            IgMessage("h1", FakeInstagram.THREAD, FakeInstagram.SAM, 1_759_308_000_000, "text", "What time?"),
        )
}
