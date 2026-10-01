// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.pingme.app.R
import org.pingme.core.model.Message
import org.pingme.core.model.ReactionRule
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * Press and hold on a bubble (UI_DESIGN.md 3.3): the bubble lifts, the rest dims, the
 * reaction bar sits above it and the actions below. [bubble] is where the bubble is on
 * screen; [lifted] draws it again on top.
 */
@Composable
fun MessageOverlay(
    message: Message,
    bubble: Rect,
    quick: List<String>,
    rule: ReactionRule?,
    networkName: String,
    actions: List<MessageAction>,
    onReact: (emoji: String, from: Offset) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    lifted: @Composable () -> Unit,
) {
    val lift = remember(message.id) { Animatable(1f) }
    val moving = PingMeTheme.motion != MotionIntensity.OFF
    LaunchedEffect(message.id) {
        if (moving) {
            lift.animateTo(
                LIFT,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Box(
                Modifier
                    .offset {
                        androidx.compose.ui.unit
                            .IntOffset(0, bubble.top.toInt())
                    }.graphicsLayer {
                        scaleX = lift.value
                        scaleY = lift.value
                        shadowElevation = (lift.value - 1f) * SHADOW
                    },
            ) { lifted() }
            AroundBubble(bubble) {
                ReactionBar(quick, rule, networkName, onReact, onMore)
                ActionCard(actions, onDismiss)
            }
        }
    }
}

/** Places the bar just above the bubble and the card just below, flipping when there is no room. */
@Composable
private fun AroundBubble(
    bubble: Rect,
    content: @Composable () -> Unit,
) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val (bar, card) = measurables.map { it.measure(loose) }
        val gap = GAP.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            val barY = (bubble.top.toInt() - bar.height - gap).coerceAtLeast(gap)
            val below = bubble.bottom.toInt() + gap
            val cardY =
                if (below + card.height <=
                    constraints.maxHeight
                ) {
                    below
                } else {
                    (barY - card.height - gap).coerceAtLeast(gap)
                }
            val barX =
                (bubble.center.x.toInt() - bar.width / 2).coerceIn(
                    gap,
                    (constraints.maxWidth - bar.width - gap).coerceAtLeast(gap),
                )
            val cardX =
                (bubble.center.x.toInt() - card.width / 2).coerceIn(
                    gap,
                    (
                        constraints.maxWidth - card.width -
                            gap
                    ).coerceAtLeast(gap),
                )
            bar.place(barX, barY)
            card.place(cardX, cardY)
        }
    }
}

/** The quick reactions and "+" (UI_DESIGN.md 5.4). Emoji the network does not allow show greyed. */
@Composable
private fun ReactionBar(
    quick: List<String>,
    rule: ReactionRule?,
    networkName: String,
    onReact: (String, Offset) -> Unit,
    onMore: () -> Unit,
) {
    val reason = stringResource(R.string.not_on_network, networkName)
    Surface(shape = CircleShape, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            quick.forEach { emoji ->
                val allowed = rule !is ReactionRule.Set || emoji in rule.allowed
                var at = Offset.Zero
                val label = stringResource(R.string.action_react_with, emoji)
                Box(
                    Modifier
                        .size(REACTION_CELL)
                        .alpha(if (allowed) 1f else GREYED)
                        .onGloballyPositioned { at = it.boundsInRoot().center }
                        .semantics {
                            contentDescription = label
                            if (!allowed) stateDescription = reason
                        }.clickable(enabled = allowed) { onReact(emoji, at) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = REACTION_SIZE) }
            }
            Box(Modifier.size(REACTION_CELL).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
                Icon(painterResource(UiR.drawable.ic_add_reaction), stringResource(R.string.action_more_reactions))
            }
        }
    }
}

@Composable
private fun ActionCard(
    actions: List<MessageAction>,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.width(CARD_WIDTH).padding(vertical = 6.dp)) {
            val colours = MaterialTheme.colorScheme
            actions.forEach { action ->
                val colour = if (action.destructive) colours.error else colours.onSurface
                ListItem(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    leadingContent = { Icon(painterResource(action.icon), null, tint = colour) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(stringResource(action.label), color = colour) }
            }
        }
    }
}

private const val SCRIM = 0.32f
private const val LIFT = 1.04f
private const val SHADOW = 400f
private const val GREYED = 0.35f
private val GAP = 8.dp
private val REACTION_CELL = 48.dp
private val REACTION_SIZE = 28.sp
private val CARD_WIDTH = 220.dp
