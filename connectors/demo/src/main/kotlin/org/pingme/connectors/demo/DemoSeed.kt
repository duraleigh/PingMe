// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatKind
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.LinkPreviewSource
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Person
import org.pingme.core.model.Quote
import org.pingme.core.model.Reaction
import org.pingme.core.model.Transport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The demo's cast and conversations (BUILD_PLAN.md P1.5). Chosen to exercise every part of
 * the UI: direct and group chats, unread and read, replies, reactions, pictures, a voice
 * note, a GIF, a file, a link preview, Instagram-style General and Requests folders, and
 * enough chats to fill the pinned grid.
 */
internal object DemoSeed {
    /** A person in the cast: remote ID and display name. */
    private val cast =
        listOf(
            "sam" to "Sam Ortiz",
            "priya" to "Priya Shah",
            "leo" to "Leo Park",
            "mom" to "Mom",
            "dad" to "Dad",
            "alex" to "Alex Kim",
            "taylor" to "Taylor Reed",
            "jordan" to "Jordan Blake",
            "casey" to "Casey Nguyen",
            "riley" to "Riley Chen",
            "morgan" to "Morgan Diaz",
            "nina" to "Nina Patel",
            "omar" to "Omar Haddad",
        )

    fun populate(
        world: DemoWorld,
        now: Instant,
    ) {
        val account = world.accountId
        cast.forEachIndexed { index, (id, name) ->
            world.people[account.person(id)] =
                Person(account.person(id), account, name, "+1555010%04d".format(index), id, null, null)
        }
        val script = Script(world, now)
        script.chat("sam", "Sam Ortiz", unread = 2) {
            me(2.hours, "Dinner at 7 still on?")
            them("sam", 110.minutes, "Yes! Leaving at 7", reactions = listOf("❤️"))
            me(100.minutes, "Perfect, see you there")
            them("sam", 3.minutes, "Are you still coming tonight?")
            them("sam", 1.minutes, "I saved you a seat", replyTo = "Are you still coming tonight?")
        }
        script.chat("design", "Design team", listOf("priya", "leo", "sam"), unread = 3, kind = ChatKind.GROUP) {
            them("leo", 5.hours, "Standup moved to 10")
            me(4.hours, "Thanks for the heads up")
            them("priya", 40.minutes, "new mocks are up", attachment = Media.PICTURE)
            them("priya", 39.minutes, "Take a look at the inbox one first")
            them("sam", 12.minutes, "Love the colours", reactions = listOf("🔥", "👍"))
        }
        script.chat("mom", "Mom", unread = 1) {
            them("mom", 1.days, "Did you get home safe?")
            me(23.hours, "Yes, all good")
            them("mom", 20.minutes, null, attachment = Media.VOICE)
        }
        script.chat("dad", "Dad") {
            them("dad", 2.days, "Call me when you can")
            me(2.days - 1.hours, "Will do tonight")
        }
        script.chat("alex", "Alex Kim") {
            them("alex", 6.hours, "This place looks great https://example.com/menu?utm_source=share", preview = true)
            me(5.hours, "Booked for Friday")
        }
        script.chat("taylor", "Taylor Reed", unread = 1) {
            me(3.days, "Here's the lease", attachment = Media.FILE)
            them("taylor", 2.days, "Signed and sent back")
            them("taylor", 2.hours, null, attachment = Media.GIF)
        }
        script.chat("book-club", "Book club", listOf("nina", "omar", "riley"), kind = ChatKind.GROUP) {
            them("nina", 4.days, "Next pick: The Left Hand of Darkness")
            them("omar", 3.days, "Already halfway through")
            me(3.days - 2.hours, "Ordering it now")
        }
        script.chat("jordan", "Jordan Blake", folder = ChatFolder.GENERAL, unread = 1) {
            them("jordan", 8.hours, "Loved your post about the trip")
        }
        script.chat("casey", "Casey Nguyen", folder = ChatFolder.REQUESTS, unread = 1) {
            them("casey", 30.minutes, "Hi! We met at the conference")
        }
        script.chat("gym", "Gym crew", listOf("riley", "morgan", "leo"), kind = ChatKind.GROUP) {
            them("riley", 1.days, "6am tomorrow?")
            them("morgan", 23.hours, "I'm in")
        }
        script.chat("riley", "Riley Chen") { them("riley", 5.days, "Thanks for the ride!") }
        script.chat("morgan", "Morgan Diaz") { me(6.days, "Happy birthday!") }
        script.chat("nina", "Nina Patel") { them("nina", 7.days, "Coffee next week?") }
        script.chat("omar", "Omar Haddad") { them("omar", 8.days, "Sent you the photos", attachment = Media.PICTURE) }
    }

    /** The kinds of demo media, and the file each one downloads as. */
    enum class Media(
        val extension: String,
    ) {
        PICTURE("png"),
        VOICE("wav"),
        GIF("gif"),
        FILE("txt"),
        ;

        /** The file's bytes; [variant] picks the colours so pictures differ. */
        fun bytes(variant: Int): ByteArray {
            val colours = PALETTE.shuffled(kotlin.random.Random(variant))
            return when (this) {
                PICTURE -> DemoMedia.png(PICTURE_SIZE, PICTURE_SIZE, colours[0], colours[1])
                VOICE -> DemoMedia.wav(VOICE_MS)
                GIF -> DemoMedia.gif(GIF_SIZE, colours.take(GIF_FRAMES), GIF_FRAME_CS)
                FILE -> "PingMe demo file.\nNothing real is in here.\n".toByteArray()
            }
        }
    }

    private val PALETTE = listOf(0x6750A4, 0x7D5260, 0x386A20, 0x006A6A, 0xB3261E, 0x8B5000)
    private const val GIF_FRAMES = 3
    private const val GIF_FRAME_CS = 30

    /** Builds one chat's history, oldest first, with times relative to now. */
    private class Script(
        val world: DemoWorld,
        val now: Instant,
    ) {
        private var counter = 0

        fun chat(
            remoteId: String,
            title: String,
            participants: List<String> = listOf(remoteId),
            unread: Int = 0,
            kind: ChatKind = ChatKind.DIRECT,
            folder: ChatFolder? = null,
            lines: ChatScript.() -> Unit,
        ) {
            val account = world.accountId
            val id = account.chat(remoteId)
            world.chats[id] =
                DemoWorld.ChatState(id, kind, title, participants.map { account.person(it) }, unread, folder, remoteId)
            world.messages[id] = ChatScript(id).apply(lines).messages
        }

        inner class ChatScript(
            private val chatId: org.pingme.core.model.ChatId,
        ) {
            val messages = mutableListOf<Message>()

            fun me(
                ago: Duration,
                text: String?,
                attachment: Media? = null,
            ) = add(world.me, ago, text, attachment, reactions = emptyList(), replyTo = null, preview = false)

            fun them(
                who: String,
                ago: Duration,
                text: String?,
                attachment: Media? = null,
                reactions: List<String> = emptyList(),
                replyTo: String? = null,
                preview: Boolean = false,
            ) = add(world.person(world.accountId.person(who)), ago, text, attachment, reactions, replyTo, preview)

            private fun add(
                sender: Person,
                ago: Duration,
                text: String?,
                media: Media?,
                reactions: List<String>,
                replyTo: String?,
                preview: Boolean,
            ) {
                val remote = "seed-${counter++}"
                val outgoing = sender == world.me
                val at = now - ago
                val quoted = replyTo?.let { body -> messages.first { it.body == body } }
                val attachments = listOfNotNull(media?.let { attachment(remote, it) })
                messages +=
                    Message(
                        id = world.accountId.message(remote),
                        chatId = chatId,
                        senderId = sender.id,
                        sentAt = at,
                        receivedAt = at,
                        body = text,
                        kind = media?.kind ?: MessageKind.TEXT,
                        attachments = attachments,
                        replyTo = quoted?.id,
                        quote = quoted?.let { Quote(world.person(it.senderId).displayName, it.body.orEmpty()) },
                        editedAt = null,
                        deletedForEveryone = false,
                        status = if (outgoing) MessageStatus.Read else MessageStatus.Delivered,
                        reactions =
                            reactions.map {
                                Reaction(it, if (outgoing) world.people.keys.first() else world.me.id, at + 1.minutes)
                            },
                        transport = Transport.NETWORK,
                        networkRemoteId = remote,
                        linkPreview = if (preview) linkPreview(at) else null,
                        isOutgoing = outgoing,
                    )
            }

            private fun attachment(
                remote: String,
                media: Media,
            ): Attachment {
                val (mime, name, size) =
                    when (media) {
                        Media.PICTURE -> Triple("image/png", "mockup.png", PICTURE_SIZE)
                        Media.VOICE -> Triple("audio/wav", null, null)
                        Media.GIF -> Triple("image/gif", "celebrate.gif", GIF_SIZE)
                        Media.FILE -> Triple("text/plain", "Lease-2026.txt", null)
                    }
                return Attachment(
                    id = world.accountId.attachment("$remote-${media.name.lowercase()}"),
                    kind = media.attachmentKind,
                    mimeType = mime,
                    fileName = name,
                    sizeBytes = 0,
                    localPath = null,
                    remoteRef = media.name,
                    durationMs = if (media == Media.VOICE) VOICE_MS.toLong() else null,
                    width = size,
                    height = size,
                    isEphemeral = false,
                    savedAt = null,
                )
            }

            private fun linkPreview(at: Instant) =
                LinkPreview(
                    url = "https://example.com/menu?utm_source=share",
                    cleanedUrl = "https://example.com/menu",
                    title = "Luna Kitchen — Menu",
                    description = "Seasonal plates and wood-fired bread.",
                    imagePath = null,
                    fetchedAt = at,
                    source = LinkPreviewSource.NETWORK,
                )
        }
    }

    private val Media.kind
        get() =
            when (this) {
                Media.PICTURE -> MessageKind.IMAGE
                Media.VOICE -> MessageKind.VOICE
                Media.GIF -> MessageKind.GIF
                Media.FILE -> MessageKind.FILE
            }

    private val Media.attachmentKind
        get() =
            when (this) {
                Media.PICTURE -> AttachmentKind.IMAGE
                Media.VOICE -> AttachmentKind.VOICE
                Media.GIF -> AttachmentKind.GIF
                Media.FILE -> AttachmentKind.FILE
            }

    const val PICTURE_SIZE = 240
    const val GIF_SIZE = 96
    const val VOICE_MS = 4_000

    /** What scripted people say when live activity is on. */
    val chatter =
        listOf(
            "On my way!",
            "Did you see this?",
            "Haha yes",
            "Can we move it to tomorrow?",
            "Running 5 minutes late",
            "Sounds good to me",
            "Who's bringing snacks?",
            "Just landed ✈️",
            "Thank you!!",
            "Let me check and get back to you",
        )

    val replies = listOf("Got it 👍", "Haha", "Sounds good", "Love that", "Ok!", "See you soon")
}
