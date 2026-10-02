// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.service.links.CleanLinks
import org.robolectric.RobolectricTestRunner

/** The bundled ClearURLs rules against links people actually paste (UI_DESIGN.md 10.11). */
@RunWith(RobolectricTestRunner::class)
class CleanLinksTest {
    private val links by lazy { CleanLinks.fromAssets(ApplicationProvider.getApplicationContext<Context>()) }

    @Test
    fun trackingParametersGoAndTheRestStays() {
        assertEquals(
            "https://example.com/post?id=5#top",
            links.clean("https://example.com/post?utm_source=mail&id=5&utm_campaign=x#top"),
        )
        assertEquals("https://example.com/plain", links.clean("https://example.com/plain"))
        assertEquals("https://example.com/post", links.clean("https://example.com/post?utm_medium=a"))
    }

    @Test
    fun amazonLosesItsReferralTailAndGoogleRedirectsUnwrap() {
        assertEquals(
            "https://www.amazon.com/Some-Product/dp/B000000000",
            links.clean("https://www.amazon.com/Some-Product/dp/B000000000/ref=sr_1_3?tag=someone-20&qid=1700000000"),
        )
        assertEquals(
            "https://example.org/page",
            links.clean("https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fpage&ved=abc"),
        )
    }

    @Test
    fun exceptionsAreLeftAlone() {
        val signIn = "https://accounts.google.com/o/oauth2/auth?client_id=1&state=utm_source"
        assertEquals(signIn, links.clean(signIn))
    }

    @Test
    fun everyLinkInATextIsCleaned() {
        assertEquals(
            "see https://a.com/x and https://b.com/y?p=1 now",
            links.cleanText("see https://a.com/x?utm_source=t and https://b.com/y?p=1&utm_term=z now"),
        )
    }
}
