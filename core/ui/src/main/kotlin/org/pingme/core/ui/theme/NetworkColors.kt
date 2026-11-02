// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import kotlin.math.max
import kotlin.math.min

/** The colours of one message bubble. [outline] is drawn when set (the SMS fallback). */
@Immutable
data class BubbleColors(
    val container: Color,
    val content: Color,
    val outline: Color?,
)

/** Each network's signature colour (UI_DESIGN.md 10.1; values from the design mockup). */
object NetworkColors {
    val signatures: Map<NetworkId, Color> =
        mapOf(
            NetworkId.WHATSAPP to Color(0xFF1FA855),
            NetworkId.GVOICE to Color(0xFF0B6E62),
            NetworkId.SIGNAL to Color(0xFF3A76F0),
            NetworkId.TELEGRAM to Color(0xFF1B8AC4),
            NetworkId.MESSENGER to Color(0xFF5B5BD6),
            // A Page inbox is an account on the Messenger network (UI_DESIGN.md 6.5).
            NetworkId.FBPAGE to Color(0xFF5B5BD6),
            NetworkId.INSTAGRAM to Color(0xFFD6247A),
        )
}

/**
 * Network identity in colour (UI_DESIGN.md 10.1). Outgoing bubbles take the colour of the
 * network the message went out on: a light tint with dark text in light mode, a deep tone
 * with light text in dark mode, so the 4.5:1 contrast rule holds. Google Messages follows
 * the theme's primary colour, and its SMS fallback is the same hue with the colour drained
 * out, outlined. Native SMS mode is neutral. Incoming bubbles look the same everywhere.
 */
@Immutable
class NetworkPalette(
    private val scheme: ColorScheme,
    private val dark: Boolean,
    private val contrast: ContrastLevel,
    private val overrides: Map<NetworkId, Int>,
    private val accentOverrides: Map<NetworkId, Int> = emptyMap(),
) {
    /** Incoming bubbles: the same tonal surface on every network, Google Messages' shade (UI_DESIGN.md 3.2). */
    val incoming = BubbleColors(scheme.messagesBar(dark), scheme.onSurface, null)

    /** The network's colour for badges and the chat header accent. */
    fun accent(network: NetworkId): Color {
        // A colour the user picked is used exactly as picked, in dark mode too (owner,
        // 2026-10-04: the swatch is the colour; shifting its tone made picks look ignored).
        (accentOverrides[network] ?: overrides[network])?.let { return Color(it) }
        val signature = signature(network)
        return if (dark) Hct.fromInt(signature.toArgb()).withTone(DARK_ACCENT_TONE).asColor() else signature
    }

    /** Outgoing bubble colours for a message sent on [network] over [transport]. */
    fun outgoing(
        network: NetworkId,
        transport: Transport,
    ): BubbleColors =
        when {
            // Picked exactly; the text is whichever of black and white reads better on it.
            network in overrides -> {
                exact(Color(overrides.getValue(network)))
            }

            network == NetworkId.SMS && network !in overrides -> {
                BubbleColors(scheme.surfaceContainerHighest, scheme.onSurface, null)
            }

            network == NetworkId.GMESSAGES && (transport == Transport.SMS || transport == Transport.MMS) -> {
                tint(signature(network), chromaFactor = SMS_CHROMA_FACTOR).copy(outline = scheme.outline)
            }

            // Google Messages' own deep tone of the wallpaper's color, not Android's brighter
            // container: sampled #004787 on the owner's phone against #2f8fff (2026-11-02).
            network == NetworkId.GMESSAGES && network !in overrides -> {
                BubbleColors(scheme.primaryContainer.deepTone(dark), scheme.primaryContainer.contentTone(dark), null)
            }

            else -> {
                tint(signature(network), chromaFactor = 1.0)
            }
        }

    private fun signature(network: NetworkId): Color =
        overrides[network]?.let(::Color)
            ?: NetworkColors.signatures[network]
            ?: when (network) {
                NetworkId.DEMO -> scheme.tertiary
                NetworkId.SMS -> scheme.outline
                else -> scheme.primary
            }

    private fun tint(
        signature: Color,
        chromaFactor: Double,
    ): BubbleColors {
        val source = Hct.fromInt(signature.toArgb())
        // Light mode wants a soft pastel tint (the mockup's #d9f7e3 for WhatsApp); dark
        // mode can carry more colour at its deeper tone.
        val chroma = min(source.chroma, if (dark) MAX_CHROMA_DARK else MAX_CHROMA_LIGHT) * chromaFactor
        val high = contrast == ContrastLevel.HIGH
        val (containerTone, contentTone) =
            when {
                dark && high -> DARK_HIGH
                dark -> DARK
                high -> LIGHT_HIGH
                else -> LIGHT
            }
        return BubbleColors(
            container = Hct.from(source.hue, chroma, containerTone).asColor(),
            content = Hct.from(source.hue, min(chroma, CONTENT_CHROMA), contentTone).asColor(),
            outline = null,
        )
    }

    private fun exact(picked: Color): BubbleColors {
        val whiteReads = contrastRatio(picked, Color.White) >= contrastRatio(picked, Color.Black)
        val content = if (whiteReads) Color.White else Color.Black
        return BubbleColors(container = picked, content = content, outline = null)
    }

    private fun Hct.asColor() = Color(toInt())

    private companion object {
        val LIGHT = 92.0 to 10.0
        val LIGHT_HIGH = 95.0 to 5.0
        val DARK = 30.0 to 92.0
        val DARK_HIGH = 22.0 to 97.0
        const val DARK_ACCENT_TONE = 80.0
        const val MAX_CHROMA_LIGHT = 26.0
        const val MAX_CHROMA_DARK = 48.0
        const val CONTENT_CHROMA = 16.0
        const val SMS_CHROMA_FACTOR = 0.3
    }
}

/** WCAG contrast ratio between two colours: 1 (none) to 21 (black on white). */
fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    val la = a.luminance() + LUMINANCE_OFFSET
    val lb = b.luminance() + LUMINANCE_OFFSET
    return max(la, lb).toDouble() / min(la, lb)
}

/** The minimum contrast for text PingMe holds every colour choice to (UI_DESIGN.md 7). */
const val MIN_TEXT_CONTRAST = 4.5

private const val LUMINANCE_OFFSET = 0.05f

/** The tone Google Messages uses for a filled shape: deep in dark mode, pale in light. */
fun Color.deepTone(dark: Boolean): Color =
    Color(
        Hct
            .fromInt(toArgb())
            .withTone(if (dark) DEEP_TONE_DARK else DEEP_TONE_LIGHT)
            .toInt(),
    )

/** The tone that reads on [deepTone]. */
fun Color.contentTone(dark: Boolean): Color =
    Color(
        Hct
            .fromInt(toArgb())
            .withTone(if (dark) CONTENT_TONE_DARK else CONTENT_TONE_LIGHT)
            .toInt(),
    )

private const val DEEP_TONE_DARK = 30.0
private const val DEEP_TONE_LIGHT = 90.0
private const val CONTENT_TONE_DARK = 90.0
private const val CONTENT_TONE_LIGHT = 10.0

/**
 * The shade of Google Messages' bars and boxes: sampled #1e2025 on the owner's phone, a touch darker
 * than Android's own container there, #26282d (2026-11-02). Light mode keeps Android's.
 */
fun androidx.compose.material3.ColorScheme.messagesBar(dark: Boolean): Color =
    if (dark) {
        Color(
            Hct
                .fromInt(surfaceContainer.toArgb())
                .withTone(BAR_TONE_DARK)
                .toInt(),
        )
    } else {
        surfaceContainer
    }

private const val BAR_TONE_DARK = 12.0
