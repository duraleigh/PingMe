// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled emoji table (UI_DESIGN.md 5.4: full picker with search and skin tones). */
class EmojiCatalogTest {
    private val catalog = File("src/main/assets/${EmojiCatalog.ASSET}").inputStream().use(EmojiCatalog::read)

    @Test
    fun everyGroupIsThereWithTheDefaultReactions() {
        assertEquals(10, catalog.groups.size)
        assertEquals("Smileys & Emotion", catalog.groups.first().name)
        val all = catalog.groups.flatMap { g -> g.emoji.map { it.value } }
        listOf("❤️", "😂", "👍", "😮", "😢", "🔥").forEach { assertTrue(it, it in all) }
        assertTrue("thousands of emoji", all.size > 1500)
    }

    @Test
    fun skinTonesFoldIntoTheirBase() {
        val wave = catalog.groups.flatMap { it.emoji }.single { it.name == "waving hand" }
        assertEquals(5, wave.tones.size)
        assertTrue(
            catalog.groups.flatMap { it.emoji }.none {
                "skin tone" in it.name &&
                    it.name.startsWith("waving hand:")
            },
        )
    }

    @Test
    fun searchFindsByWordsInTheName() {
        assertEquals("😂", catalog.search("tears of joy").first().value)
        assertTrue(catalog.search("cat").any { it.value == "🐱" })
        assertTrue(catalog.search("  ").isEmpty())
    }

    @Test
    fun groupTabsJumpPastTheRecentRow() {
        val first =
            catalog.groups
                .first()
                .emoji.size
        assertEquals(listOf(0, 1 + first), groupStarts(catalog, recentCount = 0).take(2))
        assertEquals(listOf(4, 5 + first), groupStarts(catalog, recentCount = 3).take(2))
    }
}
