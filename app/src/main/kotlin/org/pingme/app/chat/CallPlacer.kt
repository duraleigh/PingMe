// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.core.model.CallMethod
import org.pingme.core.model.NetworkId

/**
 * Places calls from a screen (UI_DESIGN.md 10.17): asks for the phone-call permission once
 * when a call needs it, then says in a notice what happened when the call could not simply
 * start. The returned function is what a call icon's tap runs.
 */
@Composable
fun rememberCallPlacer(onNotice: (String) -> Unit): (CallRequest) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val calls = remember(context) { Calls(context) }
    var waiting by remember { mutableStateOf<CallRequest?>(null) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            waiting?.let { request ->
                val outcome = calls.start(request.network, request.method, request.video, request.target, asked = true)
                callNotice(resources, outcome, request.network)?.let(onNotice)
            }
            waiting = null
        }
    return { request ->
        when (val outcome = calls.start(request.network, request.method, request.video, request.target)) {
            CallOutcome.NEEDS_PERMISSION -> {
                waiting = request
                ask.launch(android.Manifest.permission.CALL_PHONE)
            }

            else -> {
                callNotice(resources, outcome, request.network)?.let(onNotice)
            }
        }
    }
}

/** The notice for an outcome, or null when the call simply started. */
internal fun callNotice(
    resources: Resources,
    outcome: CallOutcome,
    network: NetworkId,
): String? {
    val name = network.displayName
    return when (outcome) {
        CallOutcome.CALLING, CallOutcome.NEEDS_PERMISSION -> null
        CallOutcome.OPENED_DIALER -> resources.getString(R.string.chat_call_opened_dialer)
        CallOutcome.OPENED_CHAT_NO_CALLS -> resources.getString(R.string.chat_call_no_calls, name)
        CallOutcome.OPENED_CHAT_NEEDS_SYNC -> resources.getString(R.string.chat_call_needs_sync, name)
        CallOutcome.OPENED_APP -> resources.getString(R.string.chat_call_opened_app, name)
        CallOutcome.UNAVAILABLE -> resources.getString(R.string.chat_calls_unavailable)
    }
}

/** What a call icon will do, for its long-press explanation (UI_DESIGN.md 10.17). */
internal fun callExplanation(
    resources: Resources,
    network: NetworkId,
    method: CallMethod,
    video: Boolean,
): String {
    val name = network.displayName
    return when (method) {
        CallMethod.DIALER -> {
            resources.getString(R.string.chat_call_how_dialer)
        }

        CallMethod.APP_DIALER -> {
            resources.getString(R.string.chat_call_how_app_dialer, name)
        }

        CallMethod.MEET -> {
            resources.getString(R.string.chat_call_how_meet)
        }

        CallMethod.CONTACT_APP_CALL -> {
            resources.getString(if (video) R.string.chat_call_how_app_video else R.string.chat_call_how_app_audio, name)
        }

        CallMethod.OPEN_THREAD -> {
            resources.getString(R.string.chat_call_how_thread, name)
        }

        CallMethod.OPEN_APP, CallMethod.NONE -> {
            resources.getString(R.string.chat_call_how_open, name)
        }
    }
}
