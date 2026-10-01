// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reading GIPHY's replies, and when the picker goes online (UI_DESIGN.md 5.5, CLAUDE.md rule 8). */
@RunWith(RobolectricTestRunner::class)
class GifTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun giphyRepliesBecomeGifs() {
        val reply =
            """
            {"data":[
              {"id":"abc","title":"Happy dance","images":{
                "fixed_width_downsampled":{"url":"https://media.giphy.com/abc/200w_d.gif","width":"200","height":"150","size":"90000"},
                "downsized":{"url":"https://media.giphy.com/abc/giphy-downsized.gif","width":"480","height":"360","size":"1500000"},
                "fixed_width_small":{"url":"https://media.giphy.com/abc/100w.gif","width":"100","height":"75","size":"60000"}}},
              {"id":"no-images","title":"Broken"},
              {"id":"plain","title":"","images":{
                "fixed_width":{"url":"https://media.giphy.com/plain/200w.gif","width":"200","height":"200","size":"100"},
                "original":{"url":"https://media.giphy.com/plain/giphy.gif","width":"400","height":"400","size":"200"}}}
            ],"pagination":{},"meta":{}}
            """.trimIndent()
        val gifs = GiphyProvider.parse(reply)
        assertEquals(listOf("abc", "plain"), gifs.map { it.id })
        val first = gifs.first()
        assertEquals(1_500_000L, first.full.bytes)
        assertEquals(200, first.preview.width)
        assertEquals(90_000L, first.small?.bytes)
        assertNull("no smaller size to shrink to", gifs.last().small)
    }

    @Test
    fun searchesAskForTheWordsAndNothingAboutTheUser() =
        runTest {
            val asked = mutableListOf<String>()
            val giphy =
                GiphyProvider("KEY") { url ->
                    asked += url
                    """{"data":[]}"""
                }
            giphy.search("happy dance", offset = 30)
            val url = asked.single()
            assertTrue(url.startsWith("https://api.giphy.com/v1/gifs/search?"))
            assertTrue("q=happy+dance" in url && "offset=30" in url && "api_key=KEY" in url)
            assertTrue("no user id", "customer_id" !in url && "random_id" !in url)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nothingGoesOnlineUntilTheUserSearchesOrOpensTrending() {
        val scope = TestScope(StandardTestDispatcher())
        val store =
            FakeGifStore(
                androidx.test.core.app.ApplicationProvider
                    .getApplicationContext(),
                temp.root,
            )
        store.gifs = listOf(gif("1", "Happy"), gif("2", "Sad"))
        val search = GifSearch(scope, store)
        search.open()
        scope.advanceUntilIdle()
        assertTrue("opening the picker stays offline", store.queries.isEmpty())

        search.search("hap")
        scope.advanceUntilIdle()
        assertEquals(listOf("hap"), store.queries)
        assertEquals(
            listOf("1"),
            search.state.value.results
                .map { it.id },
        )

        search.search("")
        search.showTab(GifTab.TRENDING)
        scope.advanceUntilIdle()
        assertEquals(listOf("hap", "trending"), store.queries)
    }

    companion object {
        fun gif(
            id: String,
            title: String,
        ) = Gif(
            id,
            title,
            Rendition("https://example.invalid/$id-p.gif", 200, 150, 100),
            Rendition("https://example.invalid/$id.gif", 480, 360, 2_000_000),
            Rendition("https://example.invalid/$id-s.gif", 100, 75, 50_000),
        )
    }
}
