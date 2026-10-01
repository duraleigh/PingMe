// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.core.model.AttachmentKind
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The send button (tap to send, hold for more) and the small file formats attachments use (UI_DESIGN.md 3.2, 5.8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aTapSendsAndHoldingOffersTheOtherWays() {
        val sent = mutableListOf<String>()
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                SendButton(
                    enabled = true,
                    onSend = { sent += "network" },
                    onSendSms = { sent += "sms" },
                    onSendLater = { sent += "later" },
                )
            }
        }
        compose.onNode(hasContentDescription("Send")).performTouchInput { click() }
        assertEquals("a tap just sends, no menu", listOf("network"), sent)
        compose.onNodeWithText("Send as SMS").assertDoesNotExist()
        compose.onNode(hasContentDescription("Send")).performTouchInput { longClick() }
        compose.onNodeWithText("Send later").assertExists()
        compose.onNodeWithText("Send as SMS").performClick()
        assertEquals(listOf("network", "sms"), sent)
    }

    @Test
    fun withNothingElseToOfferHoldingJustSends() {
        var sent = 0
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                SendButton(enabled = true, onSend = { sent++ }, onSendSms = null)
            }
        }
        compose.onNode(hasContentDescription("Send")).performTouchInput { longClick() }
        compose.onNodeWithText("Send later").assertDoesNotExist()
        assertEquals(1, sent)
    }

    @Test
    fun aPlaceIsReadBackFromItsFile() {
        val file =
            temp
                .newFile(
                    "here.geojson",
                ).apply { writeText("""{"type":"Point","coordinates":[-0.1276,51.5072]}""") }
        assertEquals(51.5072 to -0.1276, runBlocking { readPoint(file) })
    }

    @Test
    fun aContactCardGivesItsName() {
        val card = temp.newFile("sam.vcf").apply { writeText("BEGIN:VCARD\nVERSION:3.0\nFN:Sam Ortiz\nEND:VCARD\n") }
        assertEquals("Sam Ortiz", runBlocking { vCardName(card) })
    }

    @Test
    fun filesAreSortedByType() {
        assertEquals(AttachmentKind.GIF, OutgoingFiles.kindFor("image/gif"))
        assertEquals(AttachmentKind.IMAGE, OutgoingFiles.kindFor("image/png"))
        assertEquals(AttachmentKind.VIDEO, OutgoingFiles.kindFor("video/mp4"))
        assertEquals(AttachmentKind.CONTACT, OutgoingFiles.kindFor("text/x-vcard"))
        assertEquals(AttachmentKind.LOCATION, OutgoingFiles.kindFor(OutgoingFiles.GEO_JSON))
        assertEquals(AttachmentKind.FILE, OutgoingFiles.kindFor("application/pdf"))
    }
}
