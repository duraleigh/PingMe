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
        text
            .replace(ENTITY) { ENTITIES[it.value] ?: it.value }
            .replace(Regex("\\s+"), " ")
            .trim()

    private const val MAX_SCAN = 200_000
    private val META = Regex("""<meta\s+([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""([a-zA-Z:_-]+)\s*=\s*(["'])(.*?)\2""")
    private val TITLE =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val KEYS = setOf("property", "name")
    private val ENTITY = Regex("&(?:amp|quot|#39|apos|lt|gt|#x27|nbsp);")
    private val ENTITIES =
        mapOf(
            "&amp;" to "&",
            "&quot;" to "\"",
            "&#39;" to "'",
            "&apos;" to "'",
            "&#x27;" to "'",
            "&lt;" to "<",
            "&gt;" to ">",
            "&nbsp;" to " ",
        )
}
