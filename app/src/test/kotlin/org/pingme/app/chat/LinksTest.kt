// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.LinkPreviewSource
import kotlin.time.Instant

/** Links in message text are tappable and shown cleaned (UI_DESIGN.md 10.11, 10.12). */
class LinksTest {
    @Test
    fun linksAreFoundWithoutTheirClosingPunctuation() {
        val text = "See https://example.org/a?b=1. Or www.pingme.org, thanks!"
        val found = findLinks(text).map { text.substring(it) }
        assertEquals(listOf("https://example.org/a?b=1", "www.pingme.org"), found)
    }

    @Test
    fun aCleanedLinkIsShownAndOpenedInsteadOfTheTrackedOne() {
        val tracked = "https://shop.example/item?utm_source=x"
        val shown = linked("Look: $tracked", null) { it.substringBefore('?') }!!
        assertEquals("Look: https://shop.example/item", shown.text)
        val link = shown.getLinkAnnotations(0, shown.length).single().item as LinkAnnotation.Url
        assertEquals("https://shop.example/item", link.url)
    }

    @Test
    fun theLinkACardStandsForLeavesTheTextAndTheOtherWordsStay() {
        // The card opens it; the words around it close up (owner, Gate G3).
        val url = "https://news.example/story"
        val preview =
            LinkPreview(url, url, "Story", null, null, Instant.fromEpochMilliseconds(0), LinkPreviewSource.LOCAL)
        assertEquals("Read this and tell me", linked("Read this $url and tell me", preview)!!.text)
        assertEquals("Read this", linked("Read this $url", preview)!!.text)
        assertEquals(null, linked(url, preview))
        // Another link in the same message keeps its place.
        assertEquals("www.pingme.org", linked("$url www.pingme.org", preview)!!.text)
    }

    @Test
    fun aBareWwwLinkOpensOverHttps() {
        val link = linked("www.pingme.org", null)!!.getLinkAnnotations(0, 14).single().item as LinkAnnotation.Url
        assertEquals("https://www.pingme.org", link.url)
    }
}
