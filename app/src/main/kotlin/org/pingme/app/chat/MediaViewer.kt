// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import org.pingme.app.R
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import org.pingme.core.ui.R as UiR

/**
 * A picture or video full-screen, inside PingMe (owner, Gate G2: never hand a video to
 * another app). Pictures and GIFs fit the screen; videos play with the usual controls.
 * Back or the close button returns to the chat.
 */
@Composable
internal fun MediaViewer(
    attachment: Attachment,
    onClose: () -> Unit,
) {
    val path = attachment.localPath ?: return
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (attachment.kind == AttachmentKind.VIDEO) {
                Video(path, Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = File(path),
                    contentDescription = attachment.fileName ?: stringResource(R.string.kind_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(onClose, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)) {
                Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.back), tint = Color.White)
            }
            attachment.fileName?.let {
                Text(
                    it,
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                    color = Color.White.copy(alpha = NAME_ALPHA),
                )
            }
        }
    }
}

// The platform's own player: local files only, with play, pause, and seeking.
@Composable
private fun Video(
    path: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setVideoURI(File(path).toUri())
                setOnPreparedListener { it.isLooping = false }
                start()
            }
        },
        modifier = modifier,
        onRelease = { it.stopPlayback() },
    )
}

private const val NAME_ALPHA = 0.7f
