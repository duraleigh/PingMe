// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.pingme.app.R
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.MediaRule
import java.io.File
import org.pingme.core.ui.R as UiR

/** What waits to go with the next message, each with a way to take it back (UI_DESIGN.md 5.8). */
@Composable
fun StagedStrip(
    staged: List<OutgoingAttachment>,
    copying: Boolean,
    onRemove: (OutgoingAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (staged.isEmpty() && !copying) return
    LazyRow(
        modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp).testTag(STAGED),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(staged, key = { it.localPath }) { StagedTile(it) { onRemove(it) } }
        if (copying) {
            item { Box(Modifier.size(TILE), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        }
    }
}

@Composable
private fun StagedTile(
    file: OutgoingAttachment,
    onRemove: () -> Unit,
) {
    Box(
        Modifier.size(TILE).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (file.kind == AttachmentKind.IMAGE || file.kind == AttachmentKind.GIF) {
            AsyncImage(File(file.localPath), file.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.fillMaxSize().padding(6.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(painterResource(iconFor(file.kind)), null, Modifier.size(24.dp))
                Text(
                    file.fileName.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SmallFloatingActionButton(
            onRemove,
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(REMOVE),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) { Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.attach_remove), Modifier.size(14.dp)) }
    }
}

/** The icon a kind of attachment goes by in strips and bubbles. */
fun iconFor(kind: AttachmentKind) =
    when (kind) {
        AttachmentKind.IMAGE, AttachmentKind.STICKER -> UiR.drawable.ic_image
        AttachmentKind.GIF -> UiR.drawable.ic_gif_box
        AttachmentKind.VIDEO -> UiR.drawable.ic_videocam
        AttachmentKind.AUDIO, AttachmentKind.VOICE -> UiR.drawable.ic_mic
        AttachmentKind.CONTACT -> UiR.drawable.ic_contacts
        AttachmentKind.LOCATION -> UiR.drawable.ic_location_on
        AttachmentKind.FILE -> UiR.drawable.ic_description
    }

/** Attachments and the other ways to send for this chat's composer; [onNotice] shows a snackbar. */
internal fun composerHooks(
    viewModel: org.pingme.app.chat.ChatViewModel,
    state: org.pingme.app.chat.ChatUiState,
    onNotice: (Int) -> Unit,
) = org.pingme.app.chat.ComposerHooks(
    outbox = viewModel.outbox,
    voice = viewModel.voice.takeIf { state.capabilities?.voiceNote?.let { it != MediaRule.UNSUPPORTED } == true },
    onVoiceTooShort = { onNotice(R.string.voice_too_short) },
    gifs = viewModel.gifs.takeIf { state.capabilities?.gif?.let { it != MediaRule.UNSUPPORTED } == true },
    gifPicks =
        org.pingme.app.chat.gif
            .GifPicks(viewModel::sendGif, viewModel::sendFavourite),
    onSchedule = viewModel::schedule,
    network = state.sendNetwork,
    members = state.members,
    sendVia = state.sendVia,
    onSendVia = viewModel::sendVia,
    onProblem = { problem ->
        onNotice(
            when (problem) {
                AttachProblem.NO_LOCATION -> R.string.attach_no_location
                AttachProblem.NO_CAMERA -> R.string.attach_no_camera
            },
        )
    },
)

const val STAGED = "staged"
private val TILE = 72.dp
private val REMOVE = 22.dp

/** The MMS size warning for a GIF or voice note (UI_DESIGN.md 5.5, 5.6): send anyway, or not. */
@Composable
internal fun HeldBackDialog(
    held: org.pingme.app.chat.HeldBack?,
    onAnswer: (send: Boolean) -> Unit,
    onShrink: () -> Unit,
) {
    val waiting = held ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val size =
        android.text.format.Formatter
            .formatShortFileSize(context, waiting.bytes)
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.mms_too_big_title)) },
        text = { Text(stringResource(R.string.mms_too_big_body, size)) },
        confirmButton = { TextButton({ onAnswer(true) }) { Text(stringResource(R.string.mms_send_anyway)) } },
        dismissButton = {
            Row {
                TextButton({ onAnswer(false) }) { Text(stringResource(android.R.string.cancel)) }
                // An online GIF can go in its smaller size instead (UI_DESIGN.md 5.5).
                if (waiting.shrink != null) TextButton(onShrink) { Text(stringResource(R.string.mms_shrink)) }
            }
        },
    )
}
