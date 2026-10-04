// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.core.model.Chat
import org.pingme.core.model.Person
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.R as UiR

/**
 * The top of Chat details (UI_DESIGN.md 3.4, 10.17): the photo, the name with a pencil to
 * change it, the network, the contact card, and Search, Mute, and Pin.
 */
@Composable
fun DetailsHeader(
    state: ChatDetailsState,
    buttons: HeaderButtons,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    val name = chat.nameOverride ?: chat.title
    var renaming by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(
            name,
            size = AVATAR,
            photo =
                org.pingme.app.inbox
                    .photoFor(chat, state.people.associateBy { it.id }),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
            IconButton(
                { renaming = true },
            ) { Icon(painterResource(UiR.drawable.ic_edit), stringResource(R.string.details_rename)) }
        }
        // A merged chat names every network it spans (UI_DESIGN.md 10.15).
        val networks =
            state.members
                .map { it.network }
                .distinct()
                .ifEmpty { listOfNotNull(state.account?.network) }
        if (networks.isNotEmpty()) {
            Text(networks.joinToString(" · ") { it.displayName }, style = MaterialTheme.typography.labelLarge)
        }
        ContactButton(state.person, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(buttons.onSearch) { Text(stringResource(R.string.details_search)) }
            val muted = chat.isMuted
            FilledTonalButton(if (muted) buttons.onUnmute else buttons.onMute) {
                Text(stringResource(if (muted) R.string.details_unmute else R.string.details_mute))
            }
            FilledTonalButton({ buttons.onPin(!chat.isPinned) }) {
                Text(stringResource(if (chat.isPinned) R.string.details_unpin else R.string.details_pin))
            }
        }
    }
    if (renaming) RenameDialog(chat, buttons.onRename) { renaming = false }
}

// The person's card in the phone's contacts app, or "Add to contacts" for someone not there (10.17).
@Composable
private fun ContactButton(
    person: Person?,
    modifier: Modifier = Modifier,
) {
    person ?: return
    val context = LocalContext.current
    val contact = person.contactId
    if (contact != null) {
        OutlinedButton({ openCard(context, contact.value) }, modifier) {
            Icon(painterResource(UiR.drawable.ic_contacts), null, Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.details_contact_card))
        }
    } else {
        OutlinedButton({ addContact(context, person) }, modifier) {
            Icon(painterResource(UiR.drawable.ic_person), null, Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.details_add_contact))
        }
    }
}

@Composable
private fun RenameDialog(
    chat: Chat,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(chat.nameOverride ?: chat.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(stringResource(R.string.details_rename_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text(
                    stringResource(R.string.details_rename_note),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton({
                // The network's own name means no override.
                onRename(if (text.trim() == chat.title) "" else text)
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

// The contacts provider's lookup address for the matched contact (ContactsContract.Contacts.CONTENT_LOOKUP_URI).
private fun openCard(
    context: Context,
    lookupKey: String,
) = start(context, Intent(Intent.ACTION_VIEW, "${ContactsContract.Contacts.CONTENT_LOOKUP_URI}/$lookupKey".toUri()))

private fun addContact(
    context: Context,
    person: Person,
) = start(
    context,
    Intent(Intent.ACTION_INSERT_OR_EDIT)
        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        .putExtra(ContactsContract.Intents.Insert.NAME, person.displayName)
        .apply { person.phoneNumber?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) } },
)

private fun start(
    context: Context,
    intent: Intent,
) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No contacts app on this phone; nothing to open.
    }
}

private val AVATAR = 96.dp
