// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.LinkPreviewMode
import org.pingme.core.model.LinkPreviewSource
import org.pingme.core.service.links.CleanLinks
import org.pingme.core.service.links.LinkPreviews
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Fetching a preview from a page, within the caps (UI_DESIGN.md 10.12, BUILD_PLAN.md P4.3). */
class LinkPreviewsTest : ServiceTest() {
    private val previews by lazy { LinkPreviews(context, CleanLinks("""{"providers":{}}"""), clock) }

    // A one-shot web server on a free port, answering every request with [body].
    private fun serve(body: String): String {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            socket.use { server ->
                repeat(2) {
                    runCatching {
                        server.accept().use { client ->
                            client.getInputStream().bufferedReader().readLine()
                            val bytes = body.toByteArray()
                            val head =
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                                    "Content-Length: ${bytes.size}\r\n\r\n"
                            client.getOutputStream().write(head.toByteArray() + bytes)
                        }
                    }
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/page?utm_source=test"
    }

    @Test
    fun aPagesTitleAndDescriptionBecomeThePreview() =
        runBlocking {
            val page = "<html><head><title>Hello</title><meta name=\"description\" content=\"World\"></head></html>"
            val url = serve(page)
            val preview = previews.fetch(url)!!
            assertEquals("Hello", preview.title)
            assertEquals("World", preview.description)
            assertEquals(url, preview.url)
            assertEquals(LinkPreviewSource.LOCAL, preview.source)
            assertNull(preview.imagePath)
        }

    @Test
    fun aPageWithNothingToShowGivesNoPreviewAndNeverIsNever() =
        runBlocking {
            assertNull(previews.fetch(serve("<html><body>nothing here</body></html>")))
            assertEquals(false, previews.allowed(LinkPreviewMode.NEVER))
            assertEquals(true, previews.allowed(LinkPreviewMode.ALWAYS))
        }
}
