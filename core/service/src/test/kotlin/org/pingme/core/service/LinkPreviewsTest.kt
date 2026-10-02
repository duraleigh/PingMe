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

    private val requests = mutableListOf<List<String>>()

    // A one-shot web server on a free port, answering every request with [body]; a request
    // for a path in [redirects] answers with a redirect to that target instead.
    private fun serve(
        body: String,
        redirects: Map<String, String> = emptyMap(),
    ): String {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            socket.use { server ->
                repeat(2 + redirects.size) {
                    runCatching {
                        server.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                            synchronized(requests) { requests += lines }
                            val path = lines.first().split(" ")[1].substringBefore('?')
                            val target = redirects[path]
                            val answer =
                                if (target != null) {
                                    "HTTP/1.1 302 Found\r\nLocation: $target\r\nContent-Length: 0\r\n\r\n".toByteArray()
                                } else {
                                    val bytes = body.toByteArray()
                                    val head =
                                        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                                            "Content-Length: ${bytes.size}\r\n\r\n"
                                    head.toByteArray() + bytes
                                }
                            client.getOutputStream().write(answer)
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
    fun theCardLinksWhereARedirectEndsAndAsksLikeABrowser() =
        runBlocking {
            // A short link bounces to the real page: the card shows the real site (owner, Gate G3).
            val url = serve("<html><head><title>Landed</title></head></html>", mapOf("/page" to "/real/story"))
            val preview = previews.fetch(url)!!
            assertEquals("Landed", preview.title)
            assertEquals(url, preview.url)
            assertEquals(true, preview.cleanedUrl.endsWith("/real/story"))
            val agent = synchronized(requests) { requests.first().first { it.startsWith("User-Agent:") } }
            assertEquals(true, agent.contains("Chrome/") && agent.contains("Android"))
        }

    @Test
    fun aPageWithNothingToShowGivesNoPreviewAndNeverIsNever() =
        runBlocking {
            assertNull(previews.fetch(serve("<html><body>nothing here</body></html>")))
            assertEquals(false, previews.allowed(LinkPreviewMode.NEVER))
            assertEquals(true, previews.allowed(LinkPreviewMode.ALWAYS))
        }
}
