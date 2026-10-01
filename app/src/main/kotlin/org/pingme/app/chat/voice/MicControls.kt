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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/**
 * The microphone (UI_DESIGN.md 5.6). Recording starts the moment it is touched. Let go quickly
 * (a tap) and it carries on hands-free in the recording bar; keep holding and it is hold to
 * talk: let go to send, slide left to cancel, slide up to lock. Asks for the microphone the
 * first time instead of recording.
 *
 * It is a plain touch area, not a button: a button's own click handling takes the touch first,
 * which once left the hold never seen (Gate G1).
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
    val label = stringResource(R.string.voice_record)
    Surface(
        modifier
            .size(MIC_SIZE)
            .testTag(MIC)
            .semantics {
                role = Role.Button
                contentDescription = label
                // A screen reader's double tap is the tap: hands-free recording.
                onClick(label) {
                    if (allowed()) voice.start(locked = true) else ask()
                    true
                }
            }.pointerInput(voice) {
                val holdMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!allowed()) {
                        ask()
                        return@awaitEachGesture
                    }
                    if (!voice.start()) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    var moved = Offset.Zero
                    var outcome: Slide? = null
                    var liftedAt = down.uptimeMillis
                    while (outcome == null) {
                        val change = awaitPointerEvent().changes.first()
                        moved += change.positionChange()
                        change.consume()
                        liftedAt = change.uptimeMillis
                        outcome = slide(change.pressed, moved, cancelAt, lockAt)
                    }
                    val tap = outcome == Slide.RELEASED && liftedAt - down.uptimeMillis < holdMs
                    ended(if (tap) Slide.TAPPED else outcome, voice, haptic, tooShort)
                }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(UiR.drawable.ic_mic), null) }
    }
}

/** How a touch on the mic ended: a quick tap, let go after holding, slid left, or slid up. */
private enum class Slide { TAPPED, RELEASED, CANCELLED, LOCKED }

private fun ended(
    how: Slide,
    voice: VoiceNotes,
    haptic: HapticFeedback,
    tooShort: () -> Unit,
) {
    when (how) {
        // A tap: keep recording, hands-free.
        Slide.TAPPED -> {
            voice.lock()
        }

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
 * What the composer shows while recording (UI_DESIGN.md 5.6). Held: the timer, the live
 * waveform, and "slide to cancel". Hands-free: delete, the timer and waveform, Pause or Resume,
 * and Send; while paused, the recording so far can be played back.
 */
@Composable
fun RecordingBar(
    state: MicState.Recording,
    voice: VoiceNotes,
    modifier: Modifier = Modifier,
) {
    val player = rememberVoicePlayer()
    val playing by player.state.collectAsStateWithLifecycle()
    // Playback belongs to a pause: going on, sending, or deleting stops it.
    LaunchedEffect(state.paused) { if (!state.paused) player.release() }
    Row(
        modifier.height(BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.locked) {
            IconButton(voice::cancel) {
                Icon(painterResource(UiR.drawable.ic_delete), stringResource(R.string.voice_discard))
            }
        }
        SoFar(state, player, playing)
        val shown = if (playing.key == SO_FAR && playing.positionMs > 0) playing.positionMs else state.elapsedMs
        Text(clock(shown), style = MaterialTheme.typography.titleMedium)
        LiveWave(state.levels, Modifier.weight(1f).height(32.dp))
        if (state.locked) {
            HandsFreeEnd(state, voice)
        } else {
            Text(stringResource(R.string.voice_slide_to_cancel), style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(UiR.drawable.ic_lock), stringResource(R.string.voice_slide_up_to_lock))
        }
    }
}

/** Recording: a red dot. Paused: play or stop what is recorded so far. */
@Composable
private fun SoFar(
    state: MicState.Recording,
    player: VoicePlayer,
    playing: PlayState,
) {
    val soFar = state.soFar
    if (!state.paused) {
        Surface(Modifier.size(10.dp), shape = CircleShape, color = MaterialTheme.colorScheme.error) {}
    } else if (soFar != null) {
        val hearing = playing.key == SO_FAR && playing.playing
        IconButton({ player.toggle(SO_FAR, soFar) }) {
            Icon(
                painterResource(if (hearing) UiR.drawable.ic_stop else UiR.drawable.ic_play_arrow),
                stringResource(if (hearing) R.string.voice_stop_so_far else R.string.voice_play_so_far),
            )
        }
    }
}

/** Hands-free: pause or go on, and send. */
@Composable
private fun HandsFreeEnd(
    state: MicState.Recording,
    voice: VoiceNotes,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(if (state.paused) voice::resume else voice::pause) {
            Icon(
                painterResource(if (state.paused) UiR.drawable.ic_mic else UiR.drawable.ic_pause),
                stringResource(if (state.paused) R.string.voice_resume else R.string.voice_pause_recording),
            )
        }
        FilledIconButton({ voice.send() }, Modifier.size(MIC_SIZE)) {
            Icon(painterResource(UiR.drawable.ic_send), stringResource(R.string.chat_send))
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
private const val SO_FAR = "recording-so-far"
private const val MS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private val MIC_SIZE = 48.dp
private val BAR_HEIGHT = 64.dp
private val CANCEL_DISTANCE = 120.dp
private val LOCK_DISTANCE = 90.dp
private const val MIN_BAR = 0.08f
private const val GAP = 0.18f
