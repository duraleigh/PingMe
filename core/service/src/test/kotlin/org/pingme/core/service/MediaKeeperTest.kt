// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.attachment
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind

/** "Save all incoming media" (UI_DESIGN.md 10.16): fetch on arrival, keep each file once. */
class MediaKeeperTest : ServiceTest() {
    private fun photo(remote: String) =
        Attachment(
            id = accountId.attachment("$remote-p"),
            kind = AttachmentKind.IMAGE,
            mimeType = "image/png",
            fileName = "p.png",
            sizeBytes = 10,
            localPath = null,
            remoteRef = "ref",
            durationMs = null,
            width = null,
            height = null,
            isEphemeral = false,
            savedAt = null,
        )

    private fun withPhoto(
        remote: String,
        outgoing: Boolean = false,
    ) = messageSnapshot(remote, outgoing = outgoing).let {
        it.copy(message = it.message.copy(attachments = listOf(photo(remote))))
    }

    private suspend fun saveAll(on: Boolean) = settings.updateApp { it.copy(media = it.media.copy(saveAllMedia = on)) }

    private fun document(remote: String) =
        photo(
            remote,
        ).copy(id = accountId.attachment("$remote-d"), kind = AttachmentKind.FILE, mimeType = "application/pdf")

    @Test
    fun picturesDownloadOnArrivalButFilesWaitUntilShown() =
        runBlocking {
            // A received picture is there when the chat opens (owner, Gate G2); a document waits.
            val both =
                withPhoto(
                    "m1",
                ).let { it.copy(message = it.message.copy(attachments = listOf(photo("m1"), document("m1")))) }
            keeper.arrived(both.message)
            keeper.arrived(withPhoto("m2", outgoing = true).message)
            assertEquals(listOf(photo("m1").id), keeper.downloads)
        }

    @Test
    fun everyIncomingFileDownloadsOnArrivalWhenOn() =
        runBlocking {
            saveAll(true)
            val both =
                withPhoto(
                    "m1",
                ).let { it.copy(message = it.message.copy(attachments = listOf(photo("m1"), document("m1")))) }
            keeper.arrived(both.message)
            assertEquals(listOf(photo("m1").id, document("m1").id), keeper.downloads)
            assertTrue(keeper.downloads.isNotEmpty())
        }

    @Test
    fun aDownloadIsKeptOnceWhenOn() =
        runBlocking {
            accounts.upsert(account())
            applier.applyChats(listOf(chatSnapshot()))
            applier.apply(ConnectorEvent.NewMessage(accountId, withPhoto("m1")))
            val file = temp.newFile("p.png")
            keeper.downloaded(photo("m1"), file)
            assertNull(messages.attachment(photo("m1").id)?.savedAt)
            saveAll(true)
            keeper.downloaded(photo("m1"), file)
            assertNotNull(messages.attachment(photo("m1").id)?.savedAt)
        }
}
