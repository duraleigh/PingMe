// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.pingme.core.ui.theme.MotionIntensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** How a burst starts: picked from the bar, arriving from someone else, or a reaction taken back. */
enum class BurstKind { PICKED, INCOMING, REMOVED }

/** One reaction on its way. [from] is where it was picked; [to] is the bubble's corner. */
data class Burst(
    val id: Long,
    val emoji: String,
    val kind: BurstKind,
    val from: Offset?,
    val to: Offset,
    val startedAt: Long,
    /** The message it lands on, which wobbles. */
    val key: String? = null,
    /** Earns Extra's edge glow: a built-in special emoji or one the user added (UI_DESIGN.md 5.4). */
    val special: Boolean = false,
)

/**
 * Plays reaction bursts over the chat (UI_DESIGN.md 5.4): Pick, Land, Celebrate, and the
 * Extra edge glow, cut down by the motion level. [onLand] fires when the emoji reaches the
 * bubble, which wobbles on impact.
 */
@Stable
class BurstState {
    internal val bursts = mutableStateListOf<Burst>()
    private var next = 0L
    internal var clock by mutableLongStateOf(0L)

    fun play(
        emoji: String,
        kind: BurstKind,
        to: Offset,
        from: Offset? = null,
        key: String? = null,
    ) {
        bursts += Burst(next++, emoji, kind, from, to, NOT_STARTED, key)
    }
}

/** The whole timeline, in milliseconds from the start of a burst. */
internal object BurstTiming {
    const val PICK = 220L
    const val LAND = 340L
    const val CELEBRATE = 800L
    const val POP = 250L

    fun length(
        kind: BurstKind,
        motion: MotionIntensity,
    ): Long =
        when {
            motion == MotionIntensity.OFF -> 0L
            kind == BurstKind.REMOVED -> POP
            motion == MotionIntensity.SUBTLE -> (if (kind == BurstKind.PICKED) PICK else 0L) + LAND
            else -> (if (kind == BurstKind.PICKED) PICK else 0L) + LAND + CELEBRATE
        }
}

@Composable
fun ReactionBurstLayer(
    state: BurstState,
    motion: MotionIntensity,
    primary: Color,
    tertiary: Color,
    special: Set<String>,
    onLand: (Burst) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberTextMeasurer()
    val landed = remember { mutableSetOf<Long>() }
    val land by rememberUpdatedState(onLand)
    LaunchedEffect(state.bursts.size, motion) {
        while (state.bursts.isNotEmpty()) {
            withFrameMillis { now ->
                // Each burst's clock starts on the first frame that draws it.
                state.bursts.replaceAll {
                    if (it.startedAt ==
                        NOT_STARTED
                    ) {
                        it.copy(startedAt = now, special = it.emoji in SPECIAL + special)
                    } else {
                        it
                    }
                }
                state.clock = now
                state.bursts.toList().forEach { burst ->
                    val t = now - burst.startedAt
                    val landAt =
                        if (burst.kind == BurstKind.PICKED &&
                            motion != MotionIntensity.OFF
                        ) {
                            BurstTiming.PICK + BurstTiming.LAND
                        } else {
                            BurstTiming.LAND
                        }
                    if (burst.id !in landed && (t >= landAt || motion == MotionIntensity.OFF)) {
                        landed += burst.id
                        if (burst.kind != BurstKind.REMOVED) land(burst)
                    }
                    if (t >= BurstTiming.length(burst.kind, motion)) state.bursts.remove(burst)
                }
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        state.bursts.forEach { burst ->
            drawBurst(burst, state.clock - burst.startedAt, motion, text, primary, tertiary)
        }
    }
}

private fun DrawScope.drawBurst(
    burst: Burst,
    t: Long,
    motion: MotionIntensity,
    text: TextMeasurer,
    primary: Color,
    tertiary: Color,
) {
    if (motion == MotionIntensity.OFF || t < 0) return
    if (burst.kind == BurstKind.REMOVED) {
        val p = (t / BurstTiming.POP.toFloat()).coerceIn(0f, 1f)
        drawEmoji(text, burst.emoji, burst.to, 1f + p * POP_GROWTH, 1f - p)
        return
    }
    val pickTime = if (burst.kind == BurstKind.PICKED) BurstTiming.PICK else 0L
    val start = burst.from ?: (burst.to - Offset(0f, INCOMING_DROP * density))
    when {
        t < pickTime -> {
            // Pick: out of the bar at up to three times the size, with overshoot.
            val p = t / pickTime.toFloat()
            drawEmoji(text, burst.emoji, start, 1f + (PICK_SCALE - 1f) * overshoot(p), 1f)
        }

        t < pickTime + BurstTiming.LAND -> {
            // Land: along an arc to the bubble's corner, shrinking into a chip.
            val p = (t - pickTime) / BurstTiming.LAND.toFloat()
            val from = if (burst.kind == BurstKind.PICKED) PICK_SCALE else INCOMING_SCALE
            drawEmoji(text, burst.emoji, arc(start, burst.to, p), from + (1f - from) * p, 1f)
        }

        motion == MotionIntensity.FULL || motion == MotionIntensity.EXTRA -> {
            val p = ((t - pickTime - BurstTiming.LAND) / BurstTiming.CELEBRATE.toFloat()).coerceIn(0f, 1f)
            celebrate(burst, p, text, primary, tertiary)
            if (motion == MotionIntensity.EXTRA && burst.special) edgeGlow(glowFor(burst.emoji), 1f - p)
        }
    }
}

/** Celebrate: 16 to 24 copies burst outward with confetti, drifting up and fading; a ghost rises behind. */
private fun DrawScope.celebrate(
    burst: Burst,
    p: Float,
    text: TextMeasurer,
    primary: Color,
    tertiary: Color,
) {
    val random = Random(burst.id)
    val fade = 1f - p
    drawEmoji(text, burst.emoji, burst.to - Offset(0f, GHOST_RISE * density * p), GHOST_SCALE, fade * GHOST_ALPHA)
    repeat(random.nextInt(MIN_COPIES, MAX_COPIES + 1)) {
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val reach = (COPY_REACH_MIN + random.nextFloat() * COPY_REACH_SPREAD) * density
        val at = burst.to + Offset(cos(angle), sin(angle)) * reach * p - Offset(0f, DRIFT * density * p)
        drawEmoji(text, burst.emoji, at, COPY_SCALE, fade)
    }
    repeat(CONFETTI) { i ->
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val reach = (CONFETTI_REACH * random.nextFloat()) * density
        val at = burst.to + Offset(cos(angle), sin(angle)) * reach * p + Offset(0f, FALL * density * p * p)
        rotate(random.nextFloat() * FULL_TURN * (1 + p), at) {
            drawRect(
                if (i % 2 == 0) primary else tertiary,
                topLeft = at,
                size = Size(CONFETTI_W * density, CONFETTI_H * density),
                alpha = fade,
            )
        }
    }
}

/** Extra intensity: the screen's edges glow in the emoji's colour. */
private fun DrawScope.edgeGlow(
    colour: Color,
    strength: Float,
) {
    val edge = GLOW_WIDTH * density
    val c = colour.copy(alpha = GLOW_ALPHA * strength)
    drawRect(Brush.horizontalGradient(listOf(c, Color.Transparent), endX = edge))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c), startX = size.width - edge))
    drawRect(Brush.verticalGradient(listOf(c, Color.Transparent), endY = edge))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, c), startY = size.height - edge))
}

private fun DrawScope.drawEmoji(
    text: TextMeasurer,
    emoji: String,
    center: Offset,
    scale: Float,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val layout = text.measure(emoji, TextStyle(fontSize = EMOJI_SIZE))
    val topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f)
    scale(scale, center) { drawText(layout, topLeft = topLeft, alpha = alpha.coerceIn(0f, 1f)) }
}

/** A quadratic arc that rises above the straight line between the two points. */
private fun arc(
    from: Offset,
    to: Offset,
    p: Float,
): Offset {
    val control = Offset((from.x + to.x) / 2f, minOf(from.y, to.y) - ARC_LIFT)
    val q = 1f - p
    return from * (q * q) + control * (2 * q * p) + to * (p * p)
}

/** A spring-like curve that passes 1 and settles back. */
private fun overshoot(p: Float): Float {
    val back = OVERSHOOT
    val x = p - 1f
    return x * x * ((back + 1) * x + back) + 1f
}

private fun glowFor(emoji: String) =
    when (emoji) {
        "❤️" -> HEART_RED
        "🔥" -> FIRE_ORANGE
        "🎉" -> PARTY_GOLD
        else -> CLAP_AMBER
    }

private const val NOT_STARTED = -1L
private val HEART_RED = Color(0xFFE53950)
private val FIRE_ORANGE = Color(0xFFFF7A1A)
private val PARTY_GOLD = Color(0xFFFFC21A)
private val CLAP_AMBER = Color(0xFFF2B33D)

/** The emoji that earn Extra's edge glow (UI_DESIGN.md 5.4). */
private val SPECIAL = setOf("❤️", "🔥", "🎉", "👏")
private val EMOJI_SIZE = 22.sp
private const val PICK_SCALE = 3f
private const val INCOMING_SCALE = 2f
private const val INCOMING_DROP = 120f
private const val POP_GROWTH = 0.4f
private const val GHOST_SCALE = 4f
private const val GHOST_ALPHA = 0.35f
private const val GHOST_RISE = 80f
private const val MIN_COPIES = 16
private const val MAX_COPIES = 24
private const val COPY_SCALE = 0.8f
private const val COPY_REACH_MIN = 40f
private const val COPY_REACH_SPREAD = 80f
private const val DRIFT = 40f
private const val CONFETTI = 28
private const val CONFETTI_REACH = 140f
private const val FALL = 60f
private const val CONFETTI_W = 6f
private const val CONFETTI_H = 3f
private const val FULL_TURN = 360f
private const val GLOW_WIDTH = 48f
private const val GLOW_ALPHA = 0.45f
private const val ARC_LIFT = 120f
private const val OVERSHOOT = 1.7f
