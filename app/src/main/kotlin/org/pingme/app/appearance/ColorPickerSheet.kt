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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.materialkolor.hct.Hct
import org.pingme.app.R
import org.pingme.core.ui.components.ColorSwatch
import org.pingme.core.ui.components.SliderSetting

/**
 * Picks a colour in Material's HCT space (hue, colourfulness, lightness), with quick
 * swatches. Used for seed colours, manual palettes, network colours, and wallpapers.
 */
@Composable
fun ColorPickerSheet(
    title: String,
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val start = remember(initial) { Hct.fromInt(initial) }
    var hue by remember { mutableFloatStateOf(start.hue.toFloat()) }
    var chroma by remember { mutableFloatStateOf(start.chroma.toFloat().coerceIn(CHROMA)) }
    var tone by remember { mutableFloatStateOf(start.tone.toFloat().coerceIn(TONE)) }
    val colour = Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrolls, so Done is reachable on a short screen (owner, 2026-10-04).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            Box(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color(colour)),
            )
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QUICK_HUES.forEach { quickHue ->
                    val quick = Hct.from(quickHue, QUICK_CHROMA, QUICK_TONE).toInt()
                    ColorSwatch(Color(quick), selected = quick == colour, description = "Hue $quickHue", onClick = {
                        hue = quickHue.toFloat()
                        chroma = QUICK_CHROMA.toFloat()
                        tone = QUICK_TONE.toFloat()
                    })
                }
            }
            SliderSetting(stringResource(R.string.picker_hue), hue, 0f..HUE_MAX, { "${it.toInt()}°" }, { hue = it })
            SliderSetting(stringResource(R.string.picker_colourfulness), chroma, CHROMA, { "${it.toInt()}" }, {
                chroma =
                    it
            })
            SliderSetting(stringResource(R.string.picker_lightness), tone, TONE, { "${it.toInt()}" }, { tone = it })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                if (onReset != null) {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) { Text(stringResource(R.string.reset)) }
                }
                Button(onClick = {
                    onPick(colour)
                    onDismiss()
                }) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

private const val HUE_MAX = 359f
private val CHROMA = 0f..90f
private val TONE = 10f..95f
private val QUICK_HUES = listOf(0.0, 25.0, 50.0, 80.0, 110.0, 145.0, 180.0, 210.0, 240.0, 270.0, 300.0, 330.0)
private const val QUICK_CHROMA = 48.0
private const val QUICK_TONE = 45.0
