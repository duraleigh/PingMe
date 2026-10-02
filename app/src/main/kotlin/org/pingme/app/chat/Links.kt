// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import org.pingme.core.model.LinkPreview

// Links in message text (UI_DESIGN.md 10.11, 10.12): tappable, and shown cleaned when the
// message carries a cleaned version.

/** Where each link sits in [text]: "https://..." and "www..." addresses. */
internal fun findLinks(text: String): List<IntRange> =
    LINK
        .findAll(text)
        .map { match ->
            // A sentence's closing punctuation is not part of the link.
            val end =
                match.range.last -
                    match.value
                        .reversed()
                        .takeWhile { it in TRAILING }
                        .length
            match.range.first..end
        }.filter { !it.isEmpty() }
        .toList()

/**
 * [text] with its links underlined and tappable, shown cleaned. The link the [preview]
 * card stands for leaves the text: the card opens it, and the other words stay (owner,
 * Gate G3). Null when nothing but that link was there.
 */
internal fun linked(
    text: String,
    preview: LinkPreview?,
    clean: (String) -> String = { it },
): AnnotatedString? {
    val carded = preview?.url
    val result =
        buildAnnotatedString {
            var at = 0
            for (range in findLinks(text)) {
                val raw = text.substring(range)
                if (raw == carded) {
                    // The space around the link goes with it, so the words close up.
                    append(text.substring(at, range.first).trimEnd())
                    at = range.last + 1
                    while (at < text.length && text[at] == ' ') at++
                    if (length > 0 && at < text.length) append(' ')
                    continue
                }
                append(text.substring(at, range.first))
                val shown = clean(raw)
                val target = if (shown.startsWith("http")) shown else "https://$shown"
                withLink(
                    LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))),
                ) {
                    append(shown)
                }
                at = range.last + 1
            }
            append(text.substring(at))
        }
    return result.takeIf { it.isNotBlank() }
}

private val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
private const val TRAILING = ".,;:!?)]}'"
