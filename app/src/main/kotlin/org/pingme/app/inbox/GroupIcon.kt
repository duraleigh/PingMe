// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pingme.app.R
import org.pingme.core.ui.components.Avatar
import java.io.File
import java.util.UUID
import org.pingme.core.ui.R as UiR

/**
 * A group's picture with the camera button on it, as in Google Messages (owner, 2026-11-01). The
 * parts PingMe talks to Google through can read a group's icon but offer no way to set one, so the
 * picture is kept here: it shows in PingMe, not to the group's other members.
 */
@Composable
internal fun GroupIconPicker(
    name: String,
    photo: String?,
    size: Dp,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    picture: @Composable () -> Unit = { Avatar(name, size = size, photo = photo) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { scope.launch { keepGroupIcon(context, it)?.let(onPick) } }
        }
    Box(modifier) {
        picture()
        Surface(
            Modifier.align(Alignment.BottomEnd).size(BUTTON),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            IconButton({
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(painterResource(UiR.drawable.ic_photo_camera), stringResource(R.string.group_icon_pick))
            }
        }
    }
}

/** Copies the picked picture in, since a picked item's permission ends with the screen. */
private suspend fun keepGroupIcon(
    context: Context,
    uri: Uri,
): String? =
    withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, "group-icons").apply { mkdirs() }
        val target = File(folder, UUID.randomUUID().toString())
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.getOrNull() ?: return@withContext null
        target.absolutePath
    }

private val BUTTON = 48.dp
