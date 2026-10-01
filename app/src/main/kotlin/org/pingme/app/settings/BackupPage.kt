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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
            uri?.let(actions::exportTo)
        }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { confirming = it }
    val restart by rememberUpdatedState(onRestart)
    LaunchedEffect(status) { if (status == BackupStatus.RESTORED) restart() }
    val working = status == BackupStatus.WORKING
    Column(modifier) {
        Text(
            stringResource(R.string.backup_note),
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_save)) },
            supportingContent = { Text(stringResource(R.string.backup_save_note)) },
            leadingContent = { Icon(painterResource(UiR.drawable.ic_upload), null) },
            modifier = Modifier.clickable(enabled = !working) { save.launch("pingme-backup-${LocalDate.now()}.db") },
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
                    actions.restoreFrom(uri)
                }) { Text(stringResource(R.string.backup_restore_go)) }
            },
            dismissButton = {
                TextButton({ confirming = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun statusText(status: BackupStatus) =
    when (status) {
        BackupStatus.WORKING -> R.string.backup_working
        BackupStatus.SAVED -> R.string.backup_saved
        BackupStatus.SAVE_FAILED -> R.string.backup_save_failed
        BackupStatus.NOT_A_BACKUP -> R.string.backup_not_a_backup
        BackupStatus.NEWER_VERSION -> R.string.backup_newer
        BackupStatus.IDLE, BackupStatus.RESTORED -> null
    }

/** Starts PingMe again from its first screen, so the restored database is opened fresh. */
fun restartApp(context: Context) {
    val start = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

private const val MIME = "application/octet-stream"
