// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/**
 * The microphone (UI_DESIGN.md 5.6): hold to record, slide left to cancel, slide up to lock,
 * let go to send. Asks for the microphone the first time instead of recording.
 */
@Composable
fun MicButton(
    voice: VoiceNotes,
    modifier: Modifier = Modifier,
    onTooShort: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cancelAt = with(density) { CANCEL_DISTANCE.toPx() }
    val lockAt = with(density) { LOCK_DISTANCE.toPx() }
    val tooShort by rememberUpdatedState(onTooShort)
    val ask = rememberMicPermission { }
    val allowed = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val label = stringResource(R.string.voice_hold_to_record)
    Box(modifier.size(MIC_SIZE), Alignment.Center) {
        FilledIconButton(
            onClick = {},
            modifier =
                Modifier
                    .size(MIC_SIZE)
                    .testTag(MIC)
                    .semantics { contentDescription = label }
                    .pointerInput(voice) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (!allowed()) {
                                ask()
                                return@awaitEachGesture
                            }
                            if (!voice.start()) return@awaitEachGesture
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            var moved = Offset.Zero
                            var outcome: Slide? = null
                            while (outcome == null) {
                                val change = awaitPointerEvent().changes.first()
                                moved += change.positionChange()
                                change.consume()
                                outcome = slide(change.pressed, moved, cancelAt, lockAt)
                            }
                            when (outcome) {
                                Slide.RELEASED -> {
                                    if (!voice.send()) tooShort()
                                }

                                Slide.CANCELLED -> {
                                    voice.cancel()
                                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                }

                                Slide.LOCKED -> {
                                    voice.lock()
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                }
                            }
                        }
                    },
        ) { Icon(painterResource(UiR.drawable.ic_mic), null) }
    }
}

/** How a hold on the mic ended: let go, slid left, or slid up. */
private enum class Slide { RELEASED, CANCELLED, LOCKED }

// Null while the finger is still down and has not slid far enough either way.
private fun slide(
    pressed: Boolean,
    moved: Offset,
    cancelAt: Float,
    lockAt: Float,
) = when {
    !pressed -> Slide.RELEASED
    moved.x < -cancelAt -> Slide.CANCELLED
    moved.y < -lockAt -> Slide.LOCKED
    else -> null
}

/** Asks for the microphone; [onGrant] runs once it is allowed. */
@Composable
fun rememberMicPermission(onGrant: () -> Unit): () -> Unit {
    val granted by rememberUpdatedState(onGrant)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) granted() }
    return { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}

/**
 * "Voice reply" from the action sheet: once the microphone is allowed, start recording
 * locked so it is one tap from the message to talking (UI_DESIGN.md 5.6).
 */
@Composable
fun VoiceReplyStarter(voice: VoiceNotes) {
    val context = LocalContext.current
    val asked by voice.lockedRequest.collectAsStateWithLifecycle()
    val ask = rememberMicPermission { voice.start(locked = true) }
    LaunchedEffect(asked) {
        if (!asked) return@LaunchedEffect
        voice.clearRequest()
        val allowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (allowed) voice.start(locked = true) else ask()
    }
}

/**
 * What the composer shows while recording. Held: the timer, the live waveform, and "slide
 * to cancel". Locked: delete, the timer and waveform, and send.
 */
@Composable
fun RecordingBar(
    state: MicState.Recording,
    voice: VoiceNotes,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.height(BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.locked) {
            IconButton(voice::cancel) {
                Icon(painterResource(UiR.drawable.ic_delete), stringResource(R.string.voice_discard))
            }
        } else {
            Surface(Modifier.size(10.dp), shape = CircleShape, color = MaterialTheme.colorScheme.error) {}
        }
        Text(clock(state.elapsedMs), style = MaterialTheme.typography.titleMedium)
        LiveWave(state.levels, Modifier.weight(1f).height(32.dp))
        if (state.locked) {
            FilledIconButton({ voice.send() }, Modifier.size(MIC_SIZE)) {
                Icon(painterResource(UiR.drawable.ic_send), stringResource(R.string.chat_send))
            }
        } else {
            Text(stringResource(R.string.voice_slide_to_cancel), style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(UiR.drawable.ic_lock), stringResource(R.string.voice_slide_up_to_lock))
        }
    }
}

@Composable
private fun LiveWave(
    levels: List<Float>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val bar = size.width / VoiceNotes.LIVE_BARS
        levels.forEachIndexed { i, level ->
            val h = (size.height * level.coerceAtLeast(MIN_BAR)).coerceAtLeast(2f)
            drawRoundRect(
                color,
                Offset(i * bar + bar * GAP, (size.height - h) / 2),
                Size(bar * (1 - 2 * GAP), h),
                CornerRadius(bar / 2),
            )
        }
    }
}

/** "0:07", "1:23": minutes and seconds. */
fun clock(ms: Long): String {
    val seconds = ms / MS_PER_SECOND
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

const val MIC = "mic"
private const val MS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private val MIC_SIZE = 48.dp
private val BAR_HEIGHT = 64.dp
private val CANCEL_DISTANCE = 120.dp
private val LOCK_DISTANCE = 90.dp
private const val MIN_BAR = 0.08f
private const val GAP = 0.18f
