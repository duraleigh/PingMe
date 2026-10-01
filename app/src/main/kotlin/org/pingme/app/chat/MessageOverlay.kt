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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
 * reaction bar sits above it and the actions below, as Google Messages lays it out. The three
 * never overlap: near the top or bottom of the screen the bubble moves so all of them fit, and
 * a bubble too tall to fit shows as a clipped preview. [bubble] is where the bubble is on
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
    modifier: Modifier = Modifier,
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
    // The status bar and the gesture bar are kept clear; read here, as a popup has no insets of its own.
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing
    val clear = Clearance(safe.getTop(density), safe.getBottom(density))
    // Where the app's window sits on screen, since [bubble] is measured in it.
    var appOnScreen by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { appOnScreen = it.positionOnScreen() - it.positionInRoot() })
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        var popupOnScreen by remember { mutableStateOf<Offset?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { popupOnScreen = it.positionOnScreen() }
                .background(Color.Black.copy(alpha = SCRIM))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            val here = popupOnScreen ?: return@Box
            AroundBubble(bubble.translate(appOnScreen - here), clear) {
                ReactionBar(quick, rule, networkName, onReact, onMore)
                Box(
                    Modifier
                        .testTag(HELD_BUBBLE)
                        .clipToBounds()
                        .graphicsLayer {
                            // No shadow: it would fall from the whole row, a line across the screen.
                            scaleX = lift.value
                            scaleY = lift.value
                        },
                ) { lifted() }
                ActionCard(actions, onDismiss)
            }
        }
    }
}

/** How much of the top and bottom of the screen the overlay keeps clear, in pixels. */
private class Clearance(
    val top: Int,
    val bottom: Int,
)

/**
 * Lays out the reaction bar, the held bubble, and the action card, top to bottom with a gap
 * between, keeping the bubble where it was as far as the screen allows (UI_DESIGN.md 3.3).
 */
@Composable
private fun AroundBubble(
    bubble: Rect,
    clear: Clearance,
    content: @Composable () -> Unit,
) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val gap = GAP.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val (barPart, heldPart, cardPart) = measurables
        val bar = barPart.measure(loose)
        val card = cardPart.measure(loose)
        val top = clear.top + gap
        val bottom = constraints.maxHeight - clear.bottom - gap
        // What is left for the bubble between the bar and the card; a taller one is clipped.
        val room = (bottom - top - bar.height - card.height - 2 * gap).coerceAtLeast(gap)
        val held = heldPart.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = room))
        val highest = top + bar.height + gap
        val lowest = bottom - card.height - gap - held.height
        val heldY = maxOf(highest, minOf(lowest, bubble.top.toInt()))
        val widest = constraints.maxWidth
        layout(constraints.maxWidth, constraints.maxHeight) {
            fun across(width: Int): Int {
                val centred = bubble.center.x.toInt() - width / 2
                return centred.coerceIn(gap, maxOf(gap, widest - width - gap))
            }
            held.place(0, heldY)
            bar.place(across(bar.width), heldY - gap - bar.height)
            card.place(across(card.width), heldY + held.height + gap)
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
    Surface(
        Modifier.testTag(REACTION_BAR),
        shape = CircleShape,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
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
        Modifier.testTag(ACTION_CARD),
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

const val REACTION_BAR = "reaction-bar"
const val HELD_BUBBLE = "held-bubble"
const val ACTION_CARD = "action-card"
private const val SCRIM = 0.32f
private const val LIFT = 1.04f
private const val GREYED = 0.35f
private val GAP = 8.dp
private val REACTION_CELL = 48.dp
private val REACTION_SIZE = 28.sp
private val CARD_WIDTH = 220.dp
