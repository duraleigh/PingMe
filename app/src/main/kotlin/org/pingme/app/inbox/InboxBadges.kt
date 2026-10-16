// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.pingme.app.R
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.InboxDensity
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.SwipeAction
import kotlin.math.roundToInt
import kotlin.time.Instant
import org.pingme.core.ui.R as UiR

// Small pieces the inbox rows and pinned tiles share: badges, the folder tag, typing dots.

@Composable
fun UnreadBadge(
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    size: Dp = UNREAD_DOT,
    outlined: Boolean = false,
) {
    val description = stringResource(R.string.inbox_unread)
    // A dot, not a number: a chat is unread or it is not; nobody needs the count per chat, and
    // some networks (Google Messages) do not even report one (owner, 2026-10-01). Big enough
    // to see at a glance (owner, Gate G2: the small badge was invisible). [outlined] rims it
    // in the background colour, so it stands off an avatar or ring under it.
    val colour = if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    Box(
        modifier
            .semantics { this.contentDescription = description }
            .size(size)
            .then(if (outlined) Modifier.border(DOT_RIM, MaterialTheme.colorScheme.surface, CircleShape) else Modifier)
            .padding(if (outlined) DOT_RIM else 0.dp)
            .background(colour, CircleShape),
    )
}

private val UNREAD_DOT = 14.dp
private val DOT_RIM = 2.dp

/** The network's short name on its accent colour (UI_DESIGN.md 10.1). */
@Composable
fun NetworkBadge(
    network: NetworkId,
    modifier: Modifier = Modifier,
) {
    // The badge is the network's bubble colour, the first swatch in Appearance, with text
    // that reads on it (owner, 2026-10-04: a faint tint matched neither swatch).
    val accent =
        PingMeTheme.networkColors
            .outgoing(network, org.pingme.core.model.Transport.NETWORK)
            .container
    Text(
        badgeLabel(network),
        modifier
            .background(accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color =
            org.pingme.core.ui.components
                .readableOn(accent),
    )
}

@Composable
internal fun FolderTag(text: String) {
    Text(
        text,
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Three bouncing dots over the avatar while someone types. Still when motion is off. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val moving = PingMeTheme.motion != MotionIntensity.OFF
    val colour = MaterialTheme.colorScheme.primary
    Surface(
        modifier,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val transition = rememberInfiniteTransition(label = "typing")
            repeat(DOTS) { i ->
                val lift by transition.animateFloat(
                    0f,
                    if (moving) 1f else 0f,
                    infiniteRepeatable(tween(DOT_BOUNCE_MS, delayMillis = i * DOT_STAGGER_MS), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .graphicsLayer {
                            translationY = -lift * 3.dp.toPx()
                            alpha = DOT_ALPHA + (1 - DOT_ALPHA) * lift
                        }.size(5.dp)
                        .background(colour, CircleShape),
                )
            }
        }
    }
}

private const val DOTS = 3
private const val DOT_BOUNCE_MS = 350
private const val DOT_STAGGER_MS = 150
private const val DOT_ALPHA = 0.4f
