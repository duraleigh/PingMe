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
 * [text] with its links underlined and tappable. A link the [preview] has a cleaned
 * version of shows and opens the cleaned one; the original stays in the message.
 */
internal fun linked(
    text: String,
    preview: LinkPreview?,
): AnnotatedString =
    buildAnnotatedString {
        var at = 0
        for (range in findLinks(text)) {
            append(text.substring(at, range.first))
            val raw = text.substring(range)
            val shown = preview?.takeIf { it.url == raw }?.cleanedUrl ?: raw
            val target = if (shown.startsWith("http")) shown else "https://$shown"
            withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                append(shown)
            }
            at = range.last + 1
        }
        append(text.substring(at))
    }

private val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
private const val TRAILING = ".,;:!?)]}'"
