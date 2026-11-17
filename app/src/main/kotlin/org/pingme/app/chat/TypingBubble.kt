// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.ChatKind
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme

/**
 * Someone is typing: a bubble with moving dots where their next message will appear, as in
 * Google Messages (owner, 2026-11-01), not words in the header. It sits under the list, not in
 * it, so nobody reading older messages is moved by it. A group says who.
 */
@Composable
internal fun TypingBubble(
    names: List<String>,
    kind: ChatKind?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        names.isNotEmpty(),
        modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val spoken =
            when {
                names.size > 1 -> stringResource(R.string.chat_many_typing)
                names.isEmpty() -> ""
                else -> stringResource(R.string.chat_one_typing, names.single().substringBefore(' '))
            }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .testTag(TYPING_BUBBLE)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Dots()
            }
            if (kind == ChatKind.GROUP) {
                Text(
                    spoken,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Dots() {
    val moving = PingMeTheme.motion != MotionIntensity.OFF
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val transition = rememberInfiniteTransition(label = "typing")
        repeat(DOTS) { i ->
            val lift by transition.animateFloat(
                0f,
                if (moving) 1f else 0f,
                infiniteRepeatable(tween(BOUNCE_MS, delayMillis = i * STAGGER_MS), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier
                    .graphicsLayer {
                        translationY = -lift * 3.dp.toPx()
                        alpha = FAINT + (1 - FAINT) * lift
                    }.size(7.dp)
                    .background(colour, CircleShape),
            )
        }
    }
}

const val TYPING_BUBBLE = "typing_bubble"
private const val DOTS = 3
private const val BOUNCE_MS = 350
private const val STAGGER_MS = 150
private const val FAINT = 0.5f
