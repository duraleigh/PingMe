// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import java.io.File

/** One size of a GIF: where it is and how big. */
data class Rendition(
    val url: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
)

/**
 * A GIF from an online provider: a small [preview] for the picker, the [full] one to send,
 * and a [small] one to offer when the full one is over the MMS limit (UI_DESIGN.md 5.5).
 */
data class Gif(
    val id: String,
    val title: String,
    val preview: Rendition,
    val full: Rendition,
    val small: Rendition?,
)

/** What a picked GIF does. */
class GifPicks(
    val onGif: (Gif) -> Unit,
    val onFavourite: (File) -> Unit,
)

/**
 * Where GIF search comes from (UI_DESIGN.md 5.5). Pluggable so another service can replace
 * GIPHY. Called only when the user searches or opens Trending (CLAUDE.md rule 8).
 */
interface GifProvider {
    /** Shown in the picker, as the provider's terms ask ("Powered by GIPHY"). */
    val attribution: String

    suspend fun trending(offset: Int): List<Gif>

    suspend fun search(
        query: String,
        offset: Int,
    ): List<Gif>
}
