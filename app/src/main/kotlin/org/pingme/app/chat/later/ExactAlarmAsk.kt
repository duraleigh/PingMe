// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import org.pingme.app.R

/** Whether Android lets PingMe fire an exact alarm (Android 12 and newer ask the user). */
fun canScheduleExactly(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/**
 * After the first scheduled send without the permission: why "Alarms and reminders" matters
 * and a way to Android's page for it (BUILD_PLAN.md P4.2). Declined, the message still goes
 * through the background job, which may run late (owner, 2026-10-02: keep the fallback).
 */
@Composable
fun ExactAlarmAsk(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.later_exact_title)) },
        text = { Text(stringResource(R.string.later_exact_body)) },
        confirmButton = {
            TextButton({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val self = "package:${context.packageName}".toUri()
                    val page = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, self)
                    runCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                onDismiss()
            }) { Text(stringResource(R.string.later_exact_allow)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.later_exact_not_now)) } },
    )
}

/**
 * The Send later sheet, and after the first pick without the permission, the ask above.
 * Asked once per chat screen; declined, scheduling still works through the fallback job.
 */
@Composable
fun LaterSheetWithAsk(
    open: Boolean,
    onSchedule: (kotlin.time.Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    var asking by androidx.compose.runtime.saveable
        .rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var asked by androidx.compose.runtime.saveable
        .rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val context = LocalContext.current
    if (open) {
        SendLaterSheet(
            onPick = { at ->
                onSchedule(at)
                if (!asked && !canScheduleExactly(context)) {
                    asked = true
                    asking = true
                }
            },
            onDismiss = onDismiss,
        )
    }
    if (asking) ExactAlarmAsk { asking = false }
}
