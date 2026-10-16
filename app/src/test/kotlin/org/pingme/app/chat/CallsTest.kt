// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.CallMethod
import org.pingme.core.model.NetworkId
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The call icons do what each app on the owner's phone answers (UI_DESIGN.md 10.17; the
 * apps' intent filters and contact rows were read off the phone on 2026-10-06).
 */
@RunWith(RobolectricTestRunner::class)
class CallsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val calls = Calls(app)
    private val number = CallTarget("+1 (202) 555-0123")

    private fun started(): Intent = shadowOf(app).nextStartedActivity ?: error("nothing was started")

    @Test
    fun googleMessagesVideoUsesMeetsOwnCallAction() {
        // Meet ignores a plain view of a tel: number; only its own call action starts a call.
        assertEquals(CallOutcome.CALLING, calls.start(NetworkId.GMESSAGES, CallMethod.MEET, true, number))
        val intent = started()
        assertEquals(Calls.MEET_CALL, intent.action)
        assertEquals("tel:+1 (202) 555-0123", intent.dataString)
        assertEquals(Calls.MEET, intent.`package`)
    }

    @Test
    fun googleVoiceDialsInsideItsOwnApp() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        assertEquals(CallOutcome.CALLING, calls.start(NetworkId.GVOICE, CallMethod.APP_DIALER, false, number))
        val intent = started()
        assertEquals(Intent.ACTION_CALL, intent.action)
        assertEquals(NetworkId.GVOICE.appPackage, intent.`package`)
    }

    @Test
    fun withoutThePhonePermissionItAsksOnceThenFillsTheDialer() {
        assertEquals(CallOutcome.NEEDS_PERMISSION, calls.start(NetworkId.GMESSAGES, CallMethod.DIALER, false, number))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(
            CallOutcome.OPENED_DIALER,
            calls.start(NetworkId.GMESSAGES, CallMethod.DIALER, false, number, asked = true),
        )
        assertEquals(Intent.ACTION_DIAL, started().action)
    }

    @Test
    fun withThePhonePermissionItDialsAtOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        assertEquals(CallOutcome.CALLING, calls.start(NetworkId.GMESSAGES, CallMethod.DIALER, false, number))
        val intent = started()
        assertEquals(Intent.ACTION_CALL, intent.action)
        assertNull(intent.`package`)
    }

    @Test
    fun signalVideoUsesSignalsVideoRow() {
        assertTrue(Calls.callMimeType(NetworkId.SIGNAL, video = true)!!.endsWith(".videocall"))
        assertTrue(Calls.callMimeType(NetworkId.SIGNAL, video = false)!!.endsWith(".call"))
        assertTrue(Calls.callMimeType(NetworkId.TELEGRAM, video = true)!!.endsWith(".call.video"))
        assertTrue(Calls.callMimeType(NetworkId.WHATSAPP, video = false)!!.endsWith(".voip.call"))
    }

    @Test
    fun whatsAppWithoutItsContactRowsOpensTheChatAndSaysWhy() {
        // The owner's phone has no WhatsApp call rows (WhatsApp has not been let at the contacts).
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(
            CallOutcome.OPENED_CHAT_NEEDS_SYNC,
            calls.start(NetworkId.WHATSAPP, CallMethod.CONTACT_APP_CALL, false, number),
        )
        val intent = started()
        assertEquals("https://wa.me/12025550123", intent.dataString)
        assertEquals("com.whatsapp", intent.`package`)
    }

    @Test
    fun instagramAndMessengerOpenTheChatItself() {
        val thread = CallTarget(phone = null, thread = "340282366841710300949128")
        assertEquals(
            CallOutcome.OPENED_CHAT_NO_CALLS,
            calls.start(NetworkId.INSTAGRAM, CallMethod.OPEN_THREAD, false, thread),
        )
        var intent = started()
        assertEquals("https://www.instagram.com/direct/t/340282366841710300949128/", intent.dataString)
        assertEquals("com.instagram.android", intent.`package`)

        assertEquals(
            CallOutcome.OPENED_CHAT_NO_CALLS,
            calls.start(NetworkId.MESSENGER, CallMethod.OPEN_THREAD, true, thread),
        )
        intent = started()
        assertEquals("https://www.messenger.com/t/340282366841710300949128", intent.dataString)
        assertEquals("com.facebook.orca", intent.`package`)
    }

    @Test
    fun noticesNameTheNetworkAndSayNothingWhenTheCallStarted() {
        val resources = app.resources
        assertNull(callNotice(resources, CallOutcome.CALLING, NetworkId.WHATSAPP))
        assertTrue(callNotice(resources, CallOutcome.OPENED_CHAT_NO_CALLS, NetworkId.INSTAGRAM)!!.contains("Instagram"))
        assertTrue(callNotice(resources, CallOutcome.OPENED_CHAT_NEEDS_SYNC, NetworkId.WHATSAPP)!!.contains("WhatsApp"))
        assertTrue(
            callExplanation(resources, NetworkId.TELEGRAM, CallMethod.CONTACT_APP_CALL, true).contains("Telegram"),
        )
        assertTrue(callExplanation(resources, NetworkId.GVOICE, CallMethod.APP_DIALER, false).contains("Google Voice"))
    }
}
