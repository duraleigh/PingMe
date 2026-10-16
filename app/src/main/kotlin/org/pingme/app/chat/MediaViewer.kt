// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
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
                ZoomablePicture(path, attachment.fileName ?: stringResource(R.string.kind_image))
            }
            IconButton(onClose, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)) {
                Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.back), tint = Color.White)
            }
            // Top right: save to the PingMe album in Photos (owner, Gate G2).
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val saved = stringResource(R.string.media_saved)
            val failed = stringResource(R.string.media_save_failed)
            IconButton(
                onClick = {
                    scope.launch {
                        val ok = saveToGallery(context, attachment)
                        val said = if (ok) saved else failed
                        android.widget.Toast
                            .makeText(context, said, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp),
            ) {
                Icon(painterResource(UiR.drawable.ic_download), stringResource(R.string.media_save), tint = Color.White)
            }
            attachment.fileName?.let {
                Text(
                    it,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                    color = Color.White.copy(alpha = NAME_ALPHA),
                )
            }
        }
    }
}

/**
 * The picture, pinched to zoom and dragged to pan (owner, Gate G2 and 2026-10-04): two
 * fingers scale it between fit-to-screen and five times that, one finger moves it while
 * zoomed, and a double tap toggles between fit and twice the size.
 */
@Composable
private fun ZoomablePicture(
    path: String,
    description: String,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform =
        rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
            offset = if (scale > 1f) offset + pan else Offset.Zero
        }
    AsyncImage(
        model = File(path),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                            offset = Offset.Zero
                        },
                    )
                }.transformable(transform)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
    )
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2f

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
