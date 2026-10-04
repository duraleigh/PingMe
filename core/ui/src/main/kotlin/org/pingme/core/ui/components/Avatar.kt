// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import coil3.compose.AsyncImage
import com.materialkolor.hct.Hct
import org.pingme.core.ui.R
import org.pingme.core.ui.theme.PingMeTheme

/**
 * A person or chat avatar in the theme's shape (UI_DESIGN.md 4.2): the [photo] when there
 * is one (the contact's, or the network's profile photo, UI_DESIGN.md 10.18), otherwise a
 * coloured tile with initials, or a plain person mark for someone known only by a number
 * (owner, Gate G7: digits are not initials).
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: RoundedPolygon = PingMeTheme.shapes.avatar,
    photo: String? = null,
) {
    val dark = MaterialTheme.colorScheme.surface.run { red + green + blue } < 1.5f
    val (fill, text) = remember(name, dark) { initialsColors(name, dark) }
    Box(
        modifier
            .size(size)
            .clip(shape.toShape())
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (looksLikeNumber(name)) {
            Icon(
                painterResource(R.drawable.ic_person),
                null,
                Modifier.size(size * ICON_SHARE),
                tint = text,
            )
        } else {
            Text(initials(name), color = text, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
        }
        // Drawn over the tile; while it loads, or if it cannot, the tile shows through.
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private const val ICON_SHARE = 0.55f
private const val MIN_NUMBER_DIGITS = 5

/** A name that is only a phone number or a raw address: digits are not initials. */
fun looksLikeNumber(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || '@' in trimmed) return true
    return trimmed.count { it.isDigit() } >= MIN_NUMBER_DIGITS && trimmed.all { it.isDigit() || it in "+ ()-." }
}

/** Up to two initials: "Sam Ortiz" is SO, "Mom" is M, "Design team" is DT. */
fun initials(name: String): String =
    name
        .split(' ', '-', '_')
        .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** A stable colour per name, light in light mode and deep in dark mode, with readable text. */
fun initialsColors(
    name: String,
    dark: Boolean,
): Pair<Color, Color> {
    val hue = (name.hashCode().toLong() and 0xFFFFFFFF) % 360
    val fill = Hct.from(hue.toDouble(), 36.0, if (dark) 30.0 else 88.0)
    val text = Hct.from(hue.toDouble(), 16.0, if (dark) 92.0 else 12.0)
    return Color(fill.toInt()) to Color(text.toInt())
}
