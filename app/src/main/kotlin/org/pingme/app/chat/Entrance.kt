// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.pingme.core.model.Message
import org.pingme.core.ui.theme.BubbleEntrance
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme

/**
 * A message that arrives, or that you send, while the chat is open springs into place from
 * the sender's side, as the motion level says (UI_DESIGN.md 4.5). History, and a bubble that
 * has already come in once, are simply there.
 */
@Composable
fun Entrance(
    message: Message,
    ui: ChatUi,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val key = message.id.value
    val spec = BubbleEntrance.at(PingMeTheme.motion)
    val play = remember(key) { spec != null && message.sentAt > ui.openedAt && ui.enters(message) }
    val progress = remember(key) { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(key) { if (play && spec != null) progress.animateTo(1f, spring(spec.dampingRatio, spec.stiffness)) }
    val rise = with(LocalDensity.current) { (spec?.riseDp ?: 0f).dp.toPx() }
    val from = spec?.fromScale ?: 1f
    val springing =
        if (!play) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                val p = progress.value
                val scale = from + (1f - from) * p
                alpha = p.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
                translationY = rise * (1f - p)
                // Grows out of the bottom corner on the sender's side, where a bubble's tail is.
                transformOrigin = TransformOrigin(if (message.isOutgoing) 1f else 0f, 1f)
            }
        }
    Box(modifier.then(springing)) { content() }
}

/** Rows glide and fade as a list changes, except at Off (UI_DESIGN.md 4.5). */
fun Modifier.itemMotion(
    item: LazyItemScope,
    level: MotionIntensity,
): Modifier =
    with(item) {
        if (level == MotionIntensity.OFF) animateItem(null, null, null) else animateItem()
    }
