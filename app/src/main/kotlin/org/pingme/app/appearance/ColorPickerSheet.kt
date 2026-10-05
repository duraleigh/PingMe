// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.materialkolor.hct.Hct
import org.pingme.app.R
import org.pingme.core.ui.components.ColorSwatch
import org.pingme.core.ui.components.PingMeSheet
import org.pingme.core.ui.components.SliderSetting

/**
 * Picks a colour in Material's HCT space (hue, colourfulness, lightness), with quick
 * swatches and a hex code box. Used for seed colours, manual palettes, network colours,
 * chat wallpapers, and a chat's own bubble colour.
 */
@Composable
internal fun ColorPickerSheet(
    title: String,
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val pick = remember(initial) { Pick(initial) }
    PingMeSheet(onDismiss) {
        // Scrolls, so Done is reachable on a short screen (owner, 2026-10-04).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            Box(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color(pick.colour)),
            )
            QuickSwatches(pick)
            HexBox(pick)
            Sliders(pick)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                if (onReset != null) {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) { Text(stringResource(R.string.reset)) }
                }
                Button(onClick = {
                    onPick(pick.colour)
                    onDismiss()
                }) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/**
 * The colour being picked: the sliders' hue, colourfulness, and lightness, or a hex code
 * typed in, which is kept exactly (owner, 2026-10-05) until a slider or swatch takes over.
 */
private class Pick(
    initial: Int,
) {
    var hue by mutableFloatStateOf(Hct.fromInt(initial).hue.toFloat())
    var chroma by mutableFloatStateOf(
        Hct
            .fromInt(initial)
            .chroma
            .toFloat()
            .coerceIn(CHROMA),
    )
    var tone by mutableFloatStateOf(
        Hct
            .fromInt(initial)
            .tone
            .toFloat()
            .coerceIn(TONE),
    )
    private var exact by mutableStateOf<Int?>(null)
    private var typed by mutableStateOf<String?>(null)

    /** A screen can show only so much colourfulness at a given hue and lightness. */
    val maxChroma: Float get() =
        Hct
            .from(
                hue.toDouble(),
                GAMUT_PROBE,
                tone.toDouble(),
            ).chroma
            .toFloat()
            .coerceAtLeast(1f)
    val shownChroma: Float get() = chroma.coerceIn(0f, maxChroma)
    val colour: Int get() = exact ?: Hct.from(hue.toDouble(), shownChroma.toDouble(), tone.toDouble()).toInt()

    /** What the hex box shows: the code being typed, else the colour's own code. */
    val hex: String get() = typed ?: hexOf(colour)

    fun slide(change: () -> Unit) {
        change()
        exact = null
        typed = null
    }

    fun type(text: String) {
        typed = text.take(HEX_LENGTH)
        parseHex(text)?.let { argb ->
            val at = Hct.fromInt(argb)
            hue = at.hue.toFloat()
            chroma = at.chroma.toFloat().coerceIn(CHROMA)
            tone = at.tone.toFloat().coerceIn(TONE)
            exact = argb
        }
    }
}

@Composable
private fun QuickSwatches(pick: Pick) {
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QUICK_HUES.forEach { quickHue ->
            val quick = Hct.from(quickHue, QUICK_CHROMA, QUICK_TONE).toInt()
            ColorSwatch(Color(quick), selected = quick == pick.colour, description = "Hue $quickHue", onClick = {
                pick.slide {
                    pick.hue = quickHue.toFloat()
                    pick.chroma = QUICK_CHROMA.toFloat()
                    pick.tone = QUICK_TONE.toFloat()
                }
            })
        }
    }
}

@Composable
private fun HexBox(pick: Pick) {
    OutlinedTextField(
        value = pick.hex,
        onValueChange = pick::type,
        label = { Text(stringResource(R.string.picker_hex)) },
        singleLine = true,
        isError = parseHex(pick.hex) == null,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    )
}

@Composable
private fun Sliders(pick: Pick) {
    val resources = LocalResources.current
    SliderSetting(stringResource(R.string.picker_hue), pick.hue, 0f..HUE_MAX, { "${it.toInt()}°" }, { v ->
        pick.slide { pick.hue = v }
    })
    // The slider stops at what the screen can show and says so, so the number set is the
    // number kept (owner, 2026-10-04: picks of 65 reopened as 42, 35, 30).
    SliderSetting(
        stringResource(R.string.picker_colourfulness),
        pick.shownChroma,
        0f..pick.maxChroma,
        { resources.getString(R.string.picker_colourfulness_of, it.toInt(), pick.maxChroma.toInt()) },
        { v -> pick.slide { pick.chroma = v } },
    )
    SliderSetting(stringResource(R.string.picker_lightness), pick.tone, TONE, { "${it.toInt()}" }, { v ->
        pick.slide { pick.tone = v }
    })
}

private const val HUE_MAX = 359f
private const val HEX_LENGTH = 7
private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000.toInt()
private const val RGB_MASK = 0xFFFFFF

/** A colourfulness no screen reaches; the HCT solver hands back the most it can show. */
private const val GAMUT_PROBE = 200.0
private val CHROMA = 0f..90f
private val TONE = 10f..95f
private val QUICK_HUES = listOf(0.0, 25.0, 50.0, 80.0, 110.0, 145.0, 180.0, 210.0, 240.0, 270.0, 300.0, 330.0)
private const val QUICK_CHROMA = 48.0
private const val QUICK_TONE = 45.0

/** "#RRGGBB" for an opaque colour. */
internal fun hexOf(argb: Int): String = "#%06X".format(argb and RGB_MASK)

/** The colour a typed "#RRGGBB" or "RRGGBB" names, or null while it is not one yet. */
internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != HEX_DIGITS || !digits.all { it.isLetterOrDigit() }) return null
    return digits.toIntOrNull(HEX_RADIX)?.let { it or OPAQUE }
}
