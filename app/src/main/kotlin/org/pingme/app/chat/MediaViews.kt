// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import org.pingme.app.R
import org.pingme.app.chat.attach.firstFrame
import org.pingme.app.chat.attach.iconFor
import org.pingme.app.chat.attach.launch
import org.pingme.app.chat.attach.openFile
import org.pingme.app.chat.attach.readPoint
import org.pingme.app.chat.attach.vCardName
import org.pingme.app.chat.voice.VoiceBubble
import org.pingme.app.chat.voice.VoicePlayer
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import org.pingme.core.ui.R as UiR

/**
 * An attachment inside its bubble (UI_DESIGN.md 5.8): pictures and GIFs inline, videos as a
 * frame with a play mark, places and contacts as cards, other files as a line. [progress]
 * is how much of a sending message's media has gone.
 */
@Composable
internal fun AttachmentView(
    attachment: Attachment,
    onNeed: (Attachment) -> Unit,
    progress: Float? = null,
    player: VoicePlayer? = null,
    autoplay: Boolean = true,
    transcripts: org.pingme.app.chat.voice.Transcripts? = null,
) {
    val need by rememberUpdatedState(onNeed)
    LaunchedEffect(attachment.id, attachment.localPath) { need(attachment) }
    val context = LocalContext.current
    val open = { attachment.localPath?.let { openFile(context, File(it), attachment.mimeType) } }
    Box(Modifier.padding(bottom = 6.dp)) {
        when (attachment.kind) {
            AttachmentKind.GIF -> {
                if (autoplay) Picture(attachment) else GifOnTap(attachment)
            }

            AttachmentKind.IMAGE, AttachmentKind.STICKER -> {
                Picture(attachment)
            }

            AttachmentKind.VIDEO -> {
                VideoFrame(attachment) { open() }
            }

            AttachmentKind.VOICE -> {
                VoiceBubble(attachment, player, transcripts = transcripts)
            }

            AttachmentKind.LOCATION -> {
                PlaceCard(attachment)
            }

            AttachmentKind.CONTACT -> {
                FileLine(attachment, contactName(attachment)) { open() }
            }

            else -> {
                FileLine(attachment, attachment.fileName ?: attachment.mimeType) { open() }
            }
        }
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.Center).size(PROGRESS),
            )
        }
    }
}

@Composable
private fun Picture(attachment: Attachment) {
    AsyncImage(
        model = attachment.localPath?.let(::File),
        contentDescription = attachment.fileName ?: stringResource(R.string.kind_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier.mediaFrame(),
    )
}

// Data saver: a GIF shows still with a GIF mark until tapped (UI_DESIGN.md 5.5).
@Composable
private fun GifOnTap(attachment: Attachment) {
    var playing by androidx.compose.runtime.saveable.rememberSaveable(attachment.id.value) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (playing) {
        Picture(attachment)
        return
    }
    val context = LocalContext.current
    val still =
        coil3.request.ImageRequest
            .Builder(context)
            .data(attachment.localPath?.let(::File))
            .decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory())
            .build()
    Box(Modifier.mediaFrame().clickable { playing = true }, Alignment.Center) {
        AsyncImage(still, attachment.fileName, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Surface(shape = CircleShape, color = Color.Black.copy(alpha = SCRIM), contentColor = Color.White) {
            Text(
                stringResource(R.string.gif),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun VideoFrame(
    attachment: Attachment,
    onOpen: () -> Unit,
) {
    val frame by produceState<ImageBitmap?>(null, attachment.localPath) {
        value = attachment.localPath?.let { firstFrame(it) }
    }
    Box(Modifier.mediaFrame().clickable(onClick = onOpen), Alignment.Center) {
        frame?.let {
            androidx.compose.foundation.Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
        Surface(shape = CircleShape, color = Color.Black.copy(alpha = SCRIM), contentColor = Color.White) {
            Icon(
                painterResource(UiR.drawable.ic_play_arrow),
                stringResource(R.string.media_play_video),
                Modifier.padding(10.dp).size(28.dp),
            )
        }
    }
}

@Composable
private fun PlaceCard(attachment: Attachment) {
    val context = LocalContext.current
    val point by produceState<Pair<Double, Double>?>(null, attachment.localPath) {
        value = attachment.localPath?.let { readPoint(File(it)) }
    }
    val (lat, lng) = point ?: (null to null)
    val where = if (lat != null && lng != null) "%.5f, %.5f".format(lat, lng) else ""
    CardLine(UiR.drawable.ic_location_on, stringResource(R.string.media_location), where) {
        if (lat != null && lng != null) launch(context, Intent(Intent.ACTION_VIEW, "geo:$lat,$lng?q=$lat,$lng".toUri()))
    }
}

@Composable
private fun FileLine(
    attachment: Attachment,
    title: String,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val size = if (attachment.sizeBytes > 0) Formatter.formatShortFileSize(context, attachment.sizeBytes) else ""
    CardLine(iconFor(attachment.kind), title, size, onOpen)
}

@Composable
private fun CardLine(
    icon: Int,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = LocalContentColor.current.copy(alpha = TINT),
        contentColor = LocalContentColor.current,
        modifier = Modifier.widthIn(min = CARD_MIN, max = MEDIA_WIDTH),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(28.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Modifier.mediaFrame() =
    this
        .widthIn(max = MEDIA_WIDTH)
        .heightIn(min = MEDIA_MIN, max = MEDIA_MAX)
        .clip(RoundedCornerShape(12.dp))
        .background(LocalContentColor.current.copy(alpha = TINT))

@Composable
private fun contactName(attachment: Attachment): String {
    val name by produceState<String?>(null, attachment.localPath) {
        value = attachment.localPath?.let { vCardName(File(it)) }
    }
    return name ?: attachment.fileName?.removeSuffix(".vcf") ?: stringResource(R.string.media_contact)
}

private val MEDIA_WIDTH = 260.dp
private val MEDIA_MIN = 120.dp
private val MEDIA_MAX = 320.dp
private val CARD_MIN = 180.dp
private val PROGRESS = 44.dp
private const val TINT = 0.12f
private const val SCRIM = 0.5f
