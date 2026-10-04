// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import org.pingme.app.R
import java.io.File
import org.pingme.core.ui.R as UiR

/** Why an attach option could not finish, for the chat's snackbar. */
enum class AttachProblem { NO_LOCATION, NO_CAMERA }

/**
 * The "+" sheet: Camera, Gallery, File, Location, Contact (UI_DESIGN.md 5.8). [pick] comes
 * from the composer, which stays on screen: the sheet closes as soon as a picker opens, and
 * a picker's answer must land somewhere that still exists (Gate G2: photos went nowhere).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    pick: AttachLaunchers,
    onDismiss: () -> Unit,
    /** Opens the GIF picker; null where the network cannot take GIFs (owner, 2026-10-03: GIFs live here). */
    onGif: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val choose = { action: () -> Unit ->
                action()
                onDismiss()
            }
            AttachOption(R.string.attach_camera, UiR.drawable.ic_photo_camera) { choose(pick.camera) }
            AttachOption(R.string.attach_gallery, UiR.drawable.ic_photo_library) { choose(pick.gallery) }
            AttachOption(R.string.attach_file, UiR.drawable.ic_description) { choose(pick.file) }
            AttachOption(R.string.attach_location, UiR.drawable.ic_location_on) { choose(pick.location) }
            AttachOption(R.string.attach_contact, UiR.drawable.ic_contacts) { choose(pick.contact) }
            // The GIF picker replaces this sheet: close first, then open it.
            onGif?.let { open ->
                AttachOption(R.string.gif, UiR.drawable.ic_gif_box) {
                    onDismiss()
                    open()
                }
            }
        }
    }
}

@Composable
private fun AttachOption(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    // The label is part of the target: the whole option takes the tap.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(OPTION_WIDTH).clickable(role = Role.Button, onClick = onClick),
    ) {
        FilledTonalIconButton(onClick, Modifier.size(OPTION_SIZE)) { Icon(painterResource(icon), null) }
        Text(
            stringResource(label),
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** The five ways in, each starting the system screen that picks. */
class AttachLaunchers(
    val camera: () -> Unit,
    val gallery: () -> Unit,
    val file: () -> Unit,
    val location: () -> Unit,
    val contact: () -> Unit,
)

@Composable
fun rememberAttachLaunchers(
    outbox: Outbox,
    onProblem: (AttachProblem) -> Unit,
): AttachLaunchers {
    val context = LocalContext.current
    val problem by rememberUpdatedState(onProblem)
    var photo by remember { mutableStateOf<File?>(null) }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            photo?.takeIf { taken }?.let { file -> outbox.files.photo(file)?.let(outbox::add) }
            photo = null
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKS)) {
            if (it.isNotEmpty()) outbox.addPicked(it)
        }
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { outbox.addPicked(listOf(it)) }
        }
    val contact =
        rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let(outbox::addContact)
        }
    // The phone's own contact card for the picked person needs the contacts permission.
    val allowContacts =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) contact.launch(null)
        }
    val here = { currentLocation(context, outbox) { problem(AttachProblem.NO_LOCATION) } }
    val allowLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) here() else problem(AttachProblem.NO_LOCATION)
        }
    return AttachLaunchers(
        camera = {
            val (target, uri) = outbox.files.photoTarget()
            photo = target
            runCatching { camera.launch(uri) }.onFailure { problem(AttachProblem.NO_CAMERA) }
        },
        gallery = {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        file = { file.launch(arrayOf("*/*")) },
        location = {
            if (hasLocation(context)) here() else allowLocation.launch(LOCATION_PERMISSIONS)
        },
        contact = {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) contact.launch(null) else allowContacts.launch(Manifest.permission.READ_CONTACTS)
        },
    )
}

private fun hasLocation(context: Context) =
    LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

// Asks the phone once for where it is now, using its own location service; nothing goes online.
@android.annotation.SuppressLint("MissingPermission")
private fun currentLocation(
    context: Context,
    outbox: Outbox,
    onNone: () -> Unit,
) {
    val manager = context.getSystemService(LocationManager::class.java)
    val fused =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            emptyList()
        }
    val provider =
        (fused + listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) {
        onNone()
        return
    }
    val found =
        Consumer<Location?> { place ->
            if (place == null) onNone() else outbox.addLocation(place.latitude, place.longitude)
        }
    LocationManagerCompat.getCurrentLocation(
        manager,
        provider,
        null as CancellationSignal?,
        ContextCompat.getMainExecutor(context),
        found,
    )
}

private val LOCATION_PERMISSIONS =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private const val MAX_PICKS = 20
private val OPTION_SIZE = 64.dp
private val OPTION_WIDTH = 88.dp
