// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import java.time.LocalDate
import org.pingme.core.ui.R as UiR

/**
 * Settings > Backup (DESIGN.md milestone 4): save the chats to a file the user picks, or
 * bring them back from one, after which PingMe starts again on the restored chats.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackupPage(
    status: BackupStatus,
    actions: BackupActions,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<android.net.Uri?>(null) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
            uri?.let { actions.exportTo(it, passphrase) }
        }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { confirming = it }
    val restart by rememberUpdatedState(onRestart)
    LaunchedEffect(status) { if (status == BackupStatus.RESTORED) restart() }
    val working = status == BackupStatus.WORKING
    val ready = !working && passphrase.isNotBlank()
    Column(modifier) {
        Text(
            stringResource(R.string.backup_note),
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        PassphraseField(passphrase, shown, { passphrase = it }, { shown = !shown })
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_save)) },
            supportingContent = {
                Text(stringResource(if (ready) R.string.backup_save_note else R.string.backup_needs_passphrase))
            },
            leadingContent = { Icon(painterResource(UiR.drawable.ic_upload), null) },
            modifier =
                Modifier.clickable(
                    enabled = ready,
                ) { save.launch("pingme-backup-${LocalDate.now()}$EXTENSION") },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_restore)) },
            supportingContent = { Text(stringResource(R.string.backup_restore_note)) },
            leadingContent = { Icon(painterResource(UiR.drawable.ic_download), null) },
            modifier = Modifier.clickable(enabled = !working) { open.launch(arrayOf("*/*")) },
        )
        if (working) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        statusText(status)?.let { Text(stringResource(it), Modifier.padding(16.dp)) }
    }
    confirming?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm)) },
            confirmButton = {
                TextButton({
                    confirming = null
                    actions.restoreFrom(uri, passphrase)
                }) { Text(stringResource(R.string.backup_restore_go)) }
            },
            dismissButton = {
                TextButton({ confirming = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** The passphrase that locks a saved backup and opens one being restored (Phase 8, P8.2). */
@Composable
private fun PassphraseField(
    passphrase: String,
    shown: Boolean,
    onChange: (String) -> Unit,
    onToggle: () -> Unit,
) {
    OutlinedTextField(
        value = passphrase,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.backup_passphrase)) },
        supportingText = { Text(stringResource(R.string.backup_passphrase_note)) },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onToggle) {
                Icon(
                    painterResource(if (shown) UiR.drawable.ic_visibility_off else UiR.drawable.ic_visibility),
                    stringResource(if (shown) R.string.backup_hide_passphrase else R.string.backup_show_passphrase),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun statusText(status: BackupStatus) =
    when (status) {
        BackupStatus.WORKING -> R.string.backup_working
        BackupStatus.SAVED -> R.string.backup_saved
        BackupStatus.SAVE_FAILED -> R.string.backup_save_failed
        BackupStatus.NOT_A_BACKUP -> R.string.backup_not_a_backup
        BackupStatus.NEWER_VERSION -> R.string.backup_newer
        BackupStatus.WRONG_PASSPHRASE -> R.string.backup_wrong_passphrase
        BackupStatus.IDLE, BackupStatus.RESTORED -> null
    }

/** Starts PingMe again from its first screen, so the restored database is opened fresh. */
fun restartApp(context: Context) {
    val start = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

private const val MIME = "application/octet-stream"
private const val EXTENSION = ".pingme"
