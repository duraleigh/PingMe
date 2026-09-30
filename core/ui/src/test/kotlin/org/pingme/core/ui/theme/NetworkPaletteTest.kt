// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.min

/** UI_DESIGN.md 10.1: every bubble colour meets 4.5:1, and the networks stay apart. */
@RunWith(RobolectricTestRunner::class)
class NetworkPaletteTest {
    private fun palettes() =
        ThemePreset.entries.flatMap { preset ->
            listOf(false, true).flatMap { dark ->
                listOf(false, true).flatMap { amoled ->
                    ContrastLevel.entries.map { contrast ->
                        val scheme = seedScheme(Color(preset.seed), dark, amoled && dark, contrast)
                        "${preset.name} dark=$dark amoled=$amoled $contrast" to
                            NetworkPalette(scheme, dark, contrast, emptyMap())
                    }
                }
            }
        }

    private val sends =
        NetworkId.entries.map { it to Transport.NETWORK } +
            listOf(
                NetworkId.GMESSAGES to Transport.RCS,
                NetworkId.GMESSAGES to Transport.SMS,
                NetworkId.GMESSAGES to Transport.MMS,
            )

    @Test
    fun everyBubbleMeetsTheContrastMinimumInEveryTheme() {
        palettes().forEach { (name, palette) ->
            (
                sends.map { (n, t) ->
                    "$n/$t" to palette.outgoing(n, t)
                } + ("incoming" to palette.incoming)
            ).forEach { (what, colors) ->
                val ratio = contrastRatio(colors.container, colors.content)
                assertTrue("$name $what contrast $ratio", ratio >= MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun lightModeTintsAreLightAndDarkModeTonesAreDeep() {
        val light = palettes().first { !it.first.contains("dark=true") }.second
        val dark = palettes().first { it.first.contains("dark=true") }.second
        val tone = { c: Color -> Hct.fromInt(c.toArgb()).tone }
        assertTrue(tone(light.outgoing(NetworkId.WHATSAPP, Transport.NETWORK).container) > 80)
        assertTrue(tone(light.outgoing(NetworkId.WHATSAPP, Transport.NETWORK).content) < 20)
        assertTrue(tone(dark.outgoing(NetworkId.WHATSAPP, Transport.NETWORK).container) < 40)
        assertTrue(tone(dark.outgoing(NetworkId.WHATSAPP, Transport.NETWORK).content) > 80)
    }

    @Test
    fun theGreensAndTheBluesStayApart() {
        val hue = { n: NetworkId -> Hct.fromInt(NetworkColors.signatures.getValue(n).toArgb()).hue }

        fun apart(
            a: NetworkId,
            b: NetworkId,
        ) = min(abs(hue(a) - hue(b)), 360 - abs(hue(a) - hue(b)))
        // WhatsApp's green and Google Voice's teal-green; Signal's and Telegram's blues.
        assertTrue(apart(NetworkId.WHATSAPP, NetworkId.GVOICE) > 25)
        assertTrue(apart(NetworkId.SIGNAL, NetworkId.TELEGRAM) > 15)
        val tone = { n: NetworkId -> Hct.fromInt(NetworkColors.signatures.getValue(n).toArgb()).tone }
        assertTrue("Also apart in lightness", abs(tone(NetworkId.WHATSAPP) - tone(NetworkId.GVOICE)) > 15)
    }

    @Test
    fun smsFallbackIsTheRcsHueWithTheColourDrainedAndOutlined() {
        palettes().forEach { (name, palette) ->
            val rcs = palette.outgoing(NetworkId.GMESSAGES, Transport.RCS)
            val sms = palette.outgoing(NetworkId.GMESSAGES, Transport.SMS)
            val chroma = { c: Color -> Hct.fromInt(c.toArgb()).chroma }
            assertTrue("$name", chroma(sms.container) < chroma(rcs.container) || chroma(rcs.container) < 6)
            assertTrue("$name", sms.outline != null && rcs.outline == null)
        }
    }

    @Test
    fun overridesReplaceANetworksColour() {
        val scheme = seedScheme(Color(ThemePreset.PINGME.seed), dark = false, amoled = false, ContrastLevel.STANDARD)
        val orange = 0xFFFF8800.toInt()
        val palette = NetworkPalette(scheme, false, ContrastLevel.STANDARD, mapOf(NetworkId.WHATSAPP to orange))
        val hue = Hct.fromInt(palette.outgoing(NetworkId.WHATSAPP, Transport.NETWORK).container.toArgb()).hue
        assertEquals(Hct.fromInt(orange).hue, hue, 8.0)
        assertEquals(Color(orange), palette.accent(NetworkId.WHATSAPP))
    }
}
