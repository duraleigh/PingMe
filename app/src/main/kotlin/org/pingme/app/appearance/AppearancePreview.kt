// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.components.MessageBubble
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.SenderNameColors

/**
 * The live preview at the top of the Appearance studio (UI_DESIGN.md 3.5): a fake
 * conversation drawn with [appearance], updating the moment anything changes.
 */
@Composable
fun AppearancePreview(
    appearance: Appearance,
    modifier: Modifier = Modifier,
) {
    PingMeTheme(appearance) {
        Surface(modifier) {
            Box {
                Wallpaper(appearance.wallpaper, Modifier.matchParentSize())
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(stringResource(R.string.preview_sam), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.preview_sam), style = PingMeTheme.chatTitle)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(
                                    8.dp,
                                ).clip(CircleShape)
                                .background(PingMeTheme.networkColors.accent(NetworkId.GMESSAGES)),
                        )
                    }
                    Incoming(stringResource(R.string.preview_incoming), appearance, sender = null)
                    Outgoing(
                        stringResource(R.string.preview_outgoing),
                        NetworkId.GMESSAGES,
                        Transport.RCS,
                        last = false,
                    )
                    Outgoing(stringResource(R.string.preview_sms), NetworkId.GMESSAGES, Transport.SMS, last = true)
                    Outgoing(
                        stringResource(R.string.preview_whatsapp),
                        NetworkId.WHATSAPP,
                        Transport.NETWORK,
                        last = true,
                    )
                    Incoming(
                        stringResource(R.string.preview_group),
                        appearance,
                        sender = stringResource(R.string.preview_priya),
                    )
                }
            }
        }
    }
}

@Composable
private fun Incoming(
    text: String,
    appearance: Appearance,
    sender: String?,
) {
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
        if (appearance.showAvatarsInChat) {
            Avatar(sender ?: stringResource(R.string.preview_sam), size = (appearance.avatarSize * AVATAR_IN_CHAT).dp)
            Spacer(Modifier.width(8.dp))
        }
        MessageBubble(
            text = text,
            outgoing = false,
            network = NetworkId.GMESSAGES,
            transport = Transport.RCS,
            header = {
                if (sender != null) {
                    val colour =
                        if (appearance.senderNameColors == SenderNameColors.AUTO) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    Text(sender, style = MaterialTheme.typography.labelLarge, color = colour)
                }
            },
        )
    }
}

@Composable
private fun Outgoing(
    text: String,
    network: NetworkId,
    transport: Transport,
    last: Boolean,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
        MessageBubble(text = text, outgoing = true, network = network, transport = transport, lastInGroup = last)
    }
}

/** The chat background: nothing, a colour, a gradient, or a picture (UI_DESIGN.md 4.3). */
@Composable
fun Wallpaper(
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
) {
    when (wallpaper) {
        ChatWallpaper.None -> {
            Unit
        }

        is ChatWallpaper.Colour -> {
            Box(modifier.background(Color(wallpaper.argb)))
        }

        is ChatWallpaper.Gradient -> {
            Box(modifier.background(Brush.verticalGradient(listOf(Color(wallpaper.from), Color(wallpaper.to)))))
        }

        is ChatWallpaper.Image -> {
            val bitmap = remember(wallpaper.path) { BitmapFactory.decodeFile(wallpaper.path)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.fillMaxSize().let { if (wallpaper.blurred) it.blur(WALLPAPER_BLUR) else it },
                )
            }
        }
    }
}

/** Avatars beside bubbles are smaller than inbox avatars. */
private const val AVATAR_IN_CHAT = 0.6f
private val WALLPAPER_BLUR = 16.dp
