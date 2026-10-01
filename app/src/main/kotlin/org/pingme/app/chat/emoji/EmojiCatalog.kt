// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.emoji

import java.io.InputStream

/** One emoji, with its skin-tone versions when it has them (UI_DESIGN.md 5.4). */
data class Emoji(
    val value: String,
    val name: String,
    val tones: List<String> = emptyList(),
)

data class EmojiGroup(
    val name: String,
    val emoji: List<Emoji>,
)

/**
 * Every emoji Unicode lists, by group, from the table bundled in assets (made from Unicode's
 * emoji-test.txt). Nothing is fetched: the picker works offline.
 */
class EmojiCatalog(
    val groups: List<EmojiGroup>,
) {
    private val all = groups.flatMap { it.emoji }

    /** Emoji whose name has every word of [query], best matches (name starts with it) first. */
    fun search(query: String): List<Emoji> {
        val words =
            query
                .trim()
                .lowercase()
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all
            .filter { emoji -> words.all { it in emoji.name } }
            .sortedBy { if (it.name.startsWith(words.first())) 0 else 1 }
    }

    companion object {
        const val ASSET = "emoji/emoji.tsv"

        /** Reads the bundled table. Skin-tone versions fold into their base emoji. */
        fun read(input: InputStream): EmojiCatalog {
            val groups = mutableListOf<Pair<String, MutableList<Emoji>>>()
            input.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val (first, second) = line.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    if (first == "G") {
                        groups += second to mutableListOf()
                    } else {
                        groups.lastOrNull()?.second?.let { add(it, Emoji(first, second)) }
                    }
                }
            }
            return EmojiCatalog(groups.map { (name, list) -> EmojiGroup(name, list) })
        }

        /** "waving hand: medium skin tone" becomes a tone of "waving hand". */
        private fun add(
            list: MutableList<Emoji>,
            emoji: Emoji,
        ) {
            val base = emoji.name.substringBefore(':')
            val isTone = SKIN_TONE in emoji.name && emoji.name.contains(':')
            val index = if (isTone) list.indexOfLast { it.name == base } else -1
            if (index >= 0) {
                list[index] = list[index].copy(tones = list[index].tones + emoji.value)
            } else {
                list += emoji
            }
        }

        private const val SKIN_TONE = "skin tone"
    }
}
