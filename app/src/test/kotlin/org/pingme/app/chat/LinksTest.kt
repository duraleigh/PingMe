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
        val preview =
            LinkPreview(
                tracked,
                "https://shop.example/item",
                "Item",
                null,
                null,
                Instant.fromEpochMilliseconds(0),
                LinkPreviewSource.NETWORK,
            )
        val shown = linked("Look: $tracked", preview)
        assertEquals("Look: https://shop.example/item", shown.text)
        val link = shown.getLinkAnnotations(0, shown.length).single().item as LinkAnnotation.Url
        assertEquals("https://shop.example/item", link.url)
    }

    @Test
    fun aBareWwwLinkOpensOverHttps() {
        val link = linked("www.pingme.org", null).getLinkAnnotations(0, 14).single().item as LinkAnnotation.Url
        assertEquals("https://www.pingme.org", link.url)
    }
}
