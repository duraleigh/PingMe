// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.service.links.OpenGraph

/** Reading a page's title, description, and picture for a preview (UI_DESIGN.md 10.12). */
class OpenGraphTest {
    @Test
    fun openGraphTagsWinOverPlainOnesAndRelativePicturesResolve() {
        val html =
            """
            <html><head><title>Plain &amp; simple</title>
            <meta name="description" content="meta description">
            <meta content="OG title" property="og:title" />
            <meta property='og:description' content='OG &quot;desc&quot;'>
            <meta property="og:image" content="/pics/a.jpg">
            </head><body>ignored</body></html>
            """.trimIndent()
        val page = OpenGraph.parse(html, "https://example.com/post/1")
        assertEquals("OG title", page.title)
        assertEquals("OG \"desc\"", page.description)
        assertEquals("https://example.com/pics/a.jpg", page.image)
    }

    @Test
    fun characterReferencesAreDecodedEvenWhenEncodedTwice() {
        val html =
            """
            <html><head><title>Tom &amp;amp; Jerry &amp;#x2013; it&#8217;s on &#x1F600;</title>
            <meta name="description" content="Caf&eacute; &amp;#x20;open&hellip;">
            </head></html>
            """.trimIndent()
        val page = OpenGraph.parse(html, "https://example.com")
        assertEquals("Tom & Jerry – it’s on 😀", page.title)
        assertEquals("Café open…", page.description)
    }

    @Test
    fun aPageWithoutTagsStillHasItsTitle() {
        val page = OpenGraph.parse("<html><head><title>  Just a\n title </title></head></html>", "https://e.com")
        assertEquals("Just a title", page.title)
        assertNull(page.description)
        assertNull(page.image)
        assertEquals(true, OpenGraph.parse("<html></html>", "https://e.com").isEmpty)
    }
}
