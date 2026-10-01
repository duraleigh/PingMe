// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Obscured chats (UI_DESIGN.md 10.10): every bubble blurred until tapped, and the screen kept
// out of screenshots and the recent-apps view while the chat is open.

/**
 * Hides a bubble's content while [hidden]. Android 12 and newer blur it; older versions
 * cannot blur, so the bubble is covered in [cover] instead. Screen readers hear [label]
 * rather than the message.
 */
fun Modifier.obscured(
    hidden: Boolean,
    cover: Color,
    label: String,
): Modifier =
    when {
        !hidden -> {
            this
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            clearAndSetSemantics { contentDescription = label }.blur(BLUR)
        }

        else -> {
            clearAndSetSemantics { contentDescription = label }.drawWithContent {
                drawContent()
                drawRect(cover)
            }
        }
    }

/** Blurs [key]'s bubble again [REVEAL_MS] after a tap showed it. */
@Composable
fun HideAgain(
    key: String,
    shown: SnapshotStateMap<String, Boolean>,
) {
    if (shown[key] != true) return
    LaunchedEffect(key) {
        delay(REVEAL_MS)
        shown.remove(key)
    }
}

/** Keeps the window out of screenshots and the recent-apps view while [secure] (FLAG_SECURE). */
@Composable
fun SecureWindow(secure: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(secure, activity) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** How long a tapped bubble stays readable. */
const val REVEAL_MS = 5_000L
private val BLUR = 14.dp
