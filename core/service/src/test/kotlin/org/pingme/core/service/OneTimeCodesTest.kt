// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.service.notify.OneTimeCodes

/** One-time codes are spotted near a telling word, and Google's G- form anywhere (UI_DESIGN.md 10.6). */
class OneTimeCodesTest {
    @Test
    fun codesNearATellingWordAreFound() {
        assertEquals("482913", OneTimeCodes.find("Your verification code is 482913"))
        assertEquals("7731", OneTimeCodes.find("Use 7731 to sign in. It expires in 10 minutes."))
        assertEquals("123456", OneTimeCodes.find("G-123456 is your Google verification code"))
        assertEquals("55019", OneTimeCodes.find("OTP: 55019"))
        assertEquals("904411", OneTimeCodes.find("Your Chase authentication code is 904411."))
    }

    @Test
    fun plainNumbersAreLeftAlone() {
        assertEquals(null, OneTimeCodes.find("Call me at 555-0100 tonight"))
        assertEquals(null, OneTimeCodes.find("Dinner was $1234 split four ways"))
        assertEquals(null, OneTimeCodes.find("See you in 2026"))
        assertEquals(null, OneTimeCodes.find("The pin on the map is wrong"))
        assertEquals(null, OneTimeCodes.find(null))
        assertEquals(null, OneTimeCodes.find("Your code expires in 2026, so hurry"))
    }
}
