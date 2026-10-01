// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import org.pingme.core.ui.theme.PingMeTheme

/**
 * One message bubble, styled from the theme: the network colour for outgoing messages
 * (UI_DESIGN.md 10.1), the user's bubble style, corner radius, and tails (4.2). [header]
 * sits above the text (a reply quote, a picture, a voice note); [footer] below it (time,
 * status).
 */
@Composable
fun MessageBubble(
    text: AnnotatedString?,
    outgoing: Boolean,
    network: NetworkId,
    transport: Transport,
    modifier: Modifier = Modifier,
    lastInGroup: Boolean = true,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val appearance = PingMeTheme.appearance
    val palette = PingMeTheme.networkColors
    val colors = if (outgoing) palette.outgoing(network, transport) else palette.incoming
    val styled =
        styleBubble(
            colors = colors,
            style = appearance.bubbleStyle,
            accent = palette.accent(network),
            surface = MaterialTheme.colorScheme.surface,
            onSurface = MaterialTheme.colorScheme.onSurface,
            outgoing = outgoing,
        )
    val tail = appearance.bubbleTails && lastInGroup && !styled.pill
    val shape =
        if (styled.pill) {
            BubbleShape.Pill
        } else {
            BubbleShape(
                corner = (appearance.bubbleCorner?.dp ?: PingMeTheme.shapes.bubbleCorner),
                outgoing = outgoing,
                tail = tail,
                groupedBelow = !lastInGroup,
            )
        }
    val tailPad = if (tail) BubbleShape.TAIL_WIDTH else 0.dp
    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(shape)
            .background(styled.background)
            .let { if (styled.border != null) it.border(1.dp, styled.border, shape) else it }
            .padding(
                start = 14.dp + if (outgoing) 0.dp else tailPad,
                end = 14.dp + if (outgoing) tailPad else 0.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides styled.content) {
            header()
            if (!text.isNullOrEmpty()) Text(text, style = PingMeTheme.messageText, color = styled.content)
            footer()
        }
    }
}

/** A bubble whose text is plain; see the other [MessageBubble] for text with links. */
@Composable
fun MessageBubble(
    text: String?,
    outgoing: Boolean,
    network: NetworkId,
    transport: Transport,
    modifier: Modifier = Modifier,
    lastInGroup: Boolean = true,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) = MessageBubble(text?.let(::AnnotatedString), outgoing, network, transport, modifier, lastInGroup, header, footer)
