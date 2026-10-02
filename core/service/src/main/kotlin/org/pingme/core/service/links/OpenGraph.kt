// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.links

import java.net.URL

/**
 * The title, description, and picture a page publishes for link previews (UI_DESIGN.md
 * 10.12): Open Graph tags first, then Twitter's, then the plain title and description.
 * A small regex reader, not a browser: it only needs the head of the page.
 */
object OpenGraph {
    data class Page(
        val title: String?,
        val description: String?,
        val image: String?,
    ) {
        val isEmpty get() = title == null && description == null && image == null
    }

    fun parse(
        html: String,
        base: String,
    ): Page {
        val head = html.take(MAX_SCAN)
        val meta = metaTags(head)
        val plainTitle =
            TITLE
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.let(::clean)
        val title = meta["og:title"] ?: meta["twitter:title"] ?: plainTitle
        val description = meta["og:description"] ?: meta["twitter:description"] ?: meta["description"]
        val picture = meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"]
        val image = picture?.let { absolute(it, base) }
        return Page(title?.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() }, image)
    }

    // property="og:title" content="..." in either order, single or double quotes.
    private fun metaTags(head: String): Map<String, String> {
        val found = HashMap<String, String>()
        META.findAll(head).forEach { tag ->
            val attributes = ATTRIBUTE.findAll(tag.groupValues[1]).toList()
            val key = attributes.firstOrNull { it.groupValues[1].lowercase() in KEYS }?.groupValues?.get(3)
            val content = attributes.firstOrNull { it.groupValues[1].equals("content", true) }?.groupValues?.get(3)
            if (key != null && content != null) found.putIfAbsent(key.lowercase(), clean(content))
        }
        return found
    }

    private fun absolute(
        link: String,
        base: String,
    ): String? = runCatching { URL(URL(base), link.trim()).toString() }.getOrNull()

    private fun clean(text: String): String =
        Entities
            .decode(text)
            .replace(Regex("\\s+"), " ")
            .trim()

    private const val MAX_SCAN = 200_000
    private val META = Regex("""<meta\s+([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""([a-zA-Z:_-]+)\s*=\s*(["'])(.*?)\2""")
    private val TITLE =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val KEYS = setOf("property", "name")
}

/**
 * HTML character references in a page's title and description: named, decimal, and hex,
 * decoded again when a site encoded them twice ("&amp;#x20;"; owner, Gate G3: gibberish
 * in a card).
 */
internal object Entities {
    fun decode(text: String): String {
        var out = text
        repeat(PASSES) {
            val next = once(out)
            if (next == out) return out
            out = next
        }
        return out
    }

    private fun once(text: String): String =
        REFERENCE.replace(text) { match ->
            val name = match.groupValues[1]
            val decoded =
                when {
                    name.startsWith("#x", ignoreCase = true) -> codePoint(name.substring(2).toIntOrNull(HEX))
                    name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull())
                    else -> NAMED[name]
                }
            decoded ?: match.value
        }

    private fun codePoint(value: Int?): String? =
        value
            ?.takeIf { it in 1..MAX_CODE_POINT && it !in SURROGATES }
            ?.let { String(Character.toChars(it)) }

    private const val PASSES = 3
    private const val HEX = 16
    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATES = 0xD800..0xDFFF
    private val REFERENCE = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});")
    private val NAMED =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
            "hellip" to "…",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "laquo" to "«",
            "raquo" to "»",
            "bull" to "•",
            "middot" to "·",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "deg" to "°",
            "euro" to "€",
            "pound" to "£",
            "yen" to "¥",
            "cent" to "¢",
            "times" to "×",
            "divide" to "÷",
            "plusmn" to "±",
            "frac12" to "½",
            "frac14" to "¼",
            "frac34" to "¾",
            "sect" to "§",
            "para" to "¶",
            "iexcl" to "¡",
            "iquest" to "¿",
            "shy" to "",
            "ensp" to " ",
            "emsp" to " ",
            "thinsp" to " ",
            "zwj" to "",
            "zwnj" to "",
            "eacute" to "é",
            "egrave" to "è",
            "ecirc" to "ê",
            "aacute" to "á",
            "agrave" to "à",
            "acirc" to "â",
            "auml" to "ä",
            "ouml" to "ö",
            "uuml" to "ü",
            "oacute" to "ó",
            "uacute" to "ú",
            "iacute" to "í",
            "ntilde" to "ñ",
            "ccedil" to "ç",
            "szlig" to "ß",
            "Eacute" to "É",
            "Auml" to "Ä",
            "Ouml" to "Ö",
            "Uuml" to "Ü",
        )
}
