// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.connectors.gmessages.bridge.GmMedia
import org.pingme.connectors.gmessages.bridge.GmMessage
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Which send a message from the phone answers (owner, 2026-11-02: one picture took another's place). */
class SendPairingTest {
    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val picture = listOf(GmMedia(partId = "0", mediaId = "m", mime = "image/jpeg"))

    private fun send(
        text: String,
        pictures: Int,
        at: Instant = now,
    ) = Outgoing("7", text, pictures, at, waiter = null)

    private fun phone(
        text: String,
        at: Instant,
        tmpId: String = "",
        media: List<GmMedia> = emptyList(),
    ) = GmMessage(
        id = "307321",
        conversationId = "7",
        timestamp = at.toEpochMilliseconds() * 1000,
        fromMe = true,
        tmpId = tmpId,
        text = text,
        media = media,
    )

    private fun answer(
        msg: GmMessage,
        sends: Map<String, Outgoing>,
    ) = sendAnswered(msg, sends, now, 24.hours)

    @Test
    fun anOlderPictureDoesNotAnswerANewPicturesSend() {
        val sends = mapOf("new" to send("", 1))
        assertNull(answer(phone("", now - 30.seconds, media = picture), sends))
    }

    @Test
    fun anOlderPictureWithItsOwnTagDoesNotAnswerEither() {
        val sends = mapOf("new" to send("", 1))
        assertNull(answer(phone("", now + 1.seconds, tmpId = "earlier", media = picture), sends))
    }

    @Test
    fun theSendsOwnTagAnswersIt() {
        val sends = mapOf("new" to send("", 1))
        assertEquals("new", answer(phone("", now + 1.seconds, tmpId = "new", media = picture), sends))
    }

    @Test
    fun anUntaggedCopyMadeAfterTheSendWithTheSameTextAndPicturesAnswersIt() {
        val sends = mapOf("new" to send("Gotcha", 1))
        assertEquals("new", answer(phone("Gotcha", now + 2.seconds, media = picture), sends))
    }

    @Test
    fun theSameTextWithADifferentNumberOfPicturesDoesNot() {
        val sends = mapOf("new" to send("", 1))
        assertNull(answer(phone("", now + 1.seconds), sends))
    }

    @Test
    fun ofTwoWaitingSendsTheOlderIsAnsweredFirst() {
        val sends = mapOf("first" to send("ok", 0, now - 1.minutes), "second" to send("ok", 0))
        assertEquals("first", answer(phone("ok", now + 1.seconds), sends))
    }

    @Test
    fun someoneElsesMessageAnswersNothing() {
        val sends = mapOf("new" to send("", 1))
        assertNull(answer(phone("", now + 1.seconds, media = picture).copy(fromMe = false), sends))
    }
}
