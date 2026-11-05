// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import org.pingme.core.service.Names
import java.text.Normalizer

/**
 * A name reduced to what two networks would agree on (owner, Phase 7: suggestions by
 * similar name or username): case, accents, punctuation, emoji, and decorations go; dots,
 * underscores, and dashes read as spaces, so "sam.ortiz" and "Sam Ortiz" meet; anything
 * after a "|" or in brackets is a tag line, not the name.
 */
object NameKey {
    private const val MIN_LENGTH = 3

    /** The key, or null for a name too short or bare to compare. */
    fun of(name: String): String? {
        if (Names.isBare(name)) return null
        val core = name.substringBefore('|').replace(BRACKETS, " ")
        val plain = Normalizer.normalize(core, Normalizer.Form.NFD).replace(MARKS, "")
        val key =
            plain
                .lowercase()
                .replace(SEPARATORS, " ")
                .filter { it.isLetterOrDigit() || it == ' ' }
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        return key.takeIf { it.length >= MIN_LENGTH }
    }

    private val BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val MARKS = Regex("\\p{M}+")
    private val SEPARATORS = Regex("[._\\-]+")
}
