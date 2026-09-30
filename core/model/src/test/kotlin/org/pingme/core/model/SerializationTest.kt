// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Every model type must survive a JSON round trip, including each sealed-class case. */
class SerializationTest {
    private val json = Json

    private inline fun <reified T> roundTrip(value: T) {
        val serializer: KSerializer<T> = serializer()
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    private val at = Instant.parse("2026-09-30T12:00:00Z")
    private val account = AccountId("acc-1")
    private val sam = PersonId("p-sam")

    @Test
    fun accountWithEveryConnectionState() {
        listOf(
            ConnectionState.Connected,
            ConnectionState.Reconnecting(attempt = 3, nextAt = at),
            ConnectionState.ActionNeeded(reason = "Unpaired", deepLink = "package:com.google.android.apps.messaging"),
            ConnectionState.ActionNeeded(reason = "Log in again", deepLink = null),
            ConnectionState.Disabled,
            ConnectionState.Polled(lastCheckedAt = at),
        ).forEach { state ->
            roundTrip(
                Account(
                    id = account,
                    network = NetworkId.GMESSAGES,
                    displayName = "RCS",
                    colorArgb = 0xFF2F3DB5.toInt(),
                    state = state,
                    showInInbox = true,
                    notificationMode = NotificationMode.SILENT,
                    credentialRef = "cred-1",
                ),
            )
        }
    }

    @Test
    fun chatWithEveryAvatarSource() {
        listOf(AvatarSource.Contacts, AvatarSource.Network(account), AvatarSource.Initials).forEach { avatar ->
            roundTrip(
                Chat(
                    id = ChatId("c-1"),
                    accountId = account,
                    kind = ChatKind.GROUP,
                    title = "Design team",
                    participants = listOf(sam, PersonId("p-priya")),
                    unreadCount = 2,
                    lastActivityAt = at,
                    isPinned = true,
                    pinOrder = 0,
                    isMuted = true,
                    muteUntil = at,
                    isArchived = false,
                    isLowPriority = false,
                    isObscured = true,
                    folder = ChatFolder.GENERAL,
                    spaceId = SpaceId("s-1"),
                    mergedInto = ChatId("merged-1"),
                    avatarSource = avatar,
                    nameOverride = "Sam (work)",
                    defaultSendAccount = account,
                    networkRemoteId = "remote-c-1",
                ),
            )
        }
    }

    @Test
    fun messageWithEveryStatus() {
        listOf(
            MessageStatus.Sending,
            MessageStatus.Sent,
            MessageStatus.Delivered,
            MessageStatus.Read,
            MessageStatus.Failed(reason = "No signal"),
            MessageStatus.Scheduled(at = at),
        ).forEach { status ->
            roundTrip(
                Message(
                    id = MessageId("m-1"),
                    chatId = ChatId("c-1"),
                    senderId = sam,
                    sentAt = at,
                    receivedAt = at,
                    body = "Are you still coming tonight? https://example.com/?utm_source=x",
                    kind = MessageKind.VOICE,
                    attachments =
                        listOf(
                            Attachment(
                                id = AttachmentId("a-1"),
                                kind = AttachmentKind.VOICE,
                                mimeType = "audio/ogg",
                                fileName = "voice.ogg",
                                sizeBytes = 12_345,
                                localPath = "/data/voice.ogg",
                                remoteRef = "remote-a-1",
                                durationMs = 12_000,
                                width = null,
                                height = null,
                                isEphemeral = true,
                                savedAt = at,
                            ),
                        ),
                    replyTo = MessageId("m-0"),
                    quote = Quote(senderName = "Sam", text = "Are you still..."),
                    editedAt = at,
                    deletedForEveryone = false,
                    status = status,
                    reactions = listOf(Reaction(emoji = "❤️", senderId = sam, at = at)),
                    transport = Transport.RCS,
                    networkRemoteId = "remote-m-1",
                    linkPreview =
                        LinkPreview(
                            url = "https://example.com/?utm_source=x",
                            cleanedUrl = "https://example.com/",
                            title = "Example",
                            description = null,
                            imagePath = null,
                            fetchedAt = at,
                            source = LinkPreviewSource.LOCAL,
                        ),
                    isOutgoing = true,
                ),
            )
        }
    }

    @Test
    fun personAndSpaces() {
        roundTrip(
            Person(
                id = sam,
                accountId = account,
                displayName = "Sam Ortiz",
                phoneNumber = "+15555550123",
                networkHandle = "+15555550123",
                avatarPath = null,
                contactId = ContactId("42"),
            ),
        )
        roundTrip(Space(SpaceId("s-1"), account, "Neighbours", SpaceKind.WHATSAPP_COMMUNITY, listOf(ChatId("c-1"))))
        roundTrip(Space(SpaceId("s-2"), null, "Family", SpaceKind.CUSTOM, listOf(ChatId("c-1"), ChatId("c-2"))))
    }

    @Test
    fun capabilitiesWithEveryRule() {
        listOf(ReactionRule.AnyEmoji, ReactionRule.Set(listOf("👍", "❤️")), ReactionRule.TextFallback)
            .forEach { reactions ->
                listOf(null, TimeLimit.Unlimited, TimeLimit.Within(48.hours)).forEach { limit ->
                    roundTrip(
                        Capabilities(
                            reply = ReplyRule.QUOTED_TEXT,
                            deleteForMe = true,
                            deleteForEveryone = limit,
                            reactions = reactions,
                            gif = MediaRule.MMS_SIZE_LIMITED,
                            voiceNote = MediaRule.NATIVE,
                            typing = true,
                            readReceipts = false,
                            edit = limit,
                            nativePins = false,
                            folders = true,
                            startConversation = false,
                            createGroup = false,
                            block = false,
                            multiAccount = true,
                            calls = CallRule(audio = CallMethod.DIALER, video = CallMethod.MEET),
                        ),
                    )
                }
            }
    }
}
