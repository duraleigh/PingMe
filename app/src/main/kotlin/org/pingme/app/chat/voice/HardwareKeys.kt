// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.pingme.core.connector.Diag
import org.pingme.core.ui.components.rememberHaptic
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's physical keys inside a chat (owner, 2026-10-06; UI_DESIGN.md 5.6). Only a chat
 * that is on screen takes a key; anywhere else every key keeps its normal job. The activity
 * hands every key-down and key-up here; the chat's composer listens.
 *
 * - **Volume up, held**: records while held and sends on release, like holding the mic. A
 *   short press is still a volume press: the sliver recorded is dropped and the volume goes up.
 * - **Volume down, held**, when a Groq key is set: dictates into the message box; a short
 *   press is still a volume press. Without a key the chat never takes volume down.
 * - **The side key** (the razr's dedicated key): one press starts a hands-free recording, a
 *   second press sends. On the owner's razr the phone's own key service takes that key before
 *   any app sees it, so it is set, in the phone's settings, to open PingMe; PingMe opened
 *   again while already in front and in a chat counts as the press. Button Mapper, or
 *   anything else, can fire [ACTION_VOICE_NOTE] for the same effect.
 */
object HardwareKeys {
    private val presses = MutableSharedFlow<Int>(extraBufferCapacity = BUFFER)
    private val volumeUps = MutableSharedFlow<Boolean>(extraBufferCapacity = BUFFER)
    private val volumeDowns = MutableSharedFlow<Boolean>(extraBufferCapacity = BUFFER)
    private val listeners = AtomicInteger()
    private val dictating = AtomicInteger()

    /** The key codes of the side key, one per press, while a chat listens. */
    val pressed: SharedFlow<Int> = presses.asSharedFlow()

    /** Volume up going down (true) and coming back up (false), while a chat listens. */
    val volumeUp: SharedFlow<Boolean> = volumeUps.asSharedFlow()

    /** Volume down going down (true) and coming back up (false), while a chat with dictation listens. */
    val volumeDown: SharedFlow<Boolean> = volumeDowns.asSharedFlow()

    /** The activity's key-down. True when a chat on screen takes the key, so nothing else sees it. */
    fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
    ): Boolean {
        if (listeners.get() == 0) return false
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (repeatCount == 0) volumeUps.tryEmit(true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && dictating.get() > 0) {
            if (repeatCount == 0) volumeDowns.tryEmit(true)
            return true
        }
        if (keyCode !in VOICE_KEYS) {
            // Which code the side key arrives as is only known from the phone; the diagnostic
            // file says what reached the chat, so an unexpected mapping can be added.
            if (keyCode !in EVERYDAY_KEYS) Diag.note(TAG, "Key ${KeyEvent.keyCodeToString(keyCode)} reached the chat")
            return false
        }
        if (repeatCount == 0) {
            Diag.note(TAG, "Voice key ${KeyEvent.keyCodeToString(keyCode)} pressed in a chat")
            presses.tryEmit(keyCode)
        }
        return true
    }

    /** The activity's key-up: the release of a held volume up ends the recording. */
    fun onKeyUp(keyCode: Int): Boolean {
        if (listeners.get() == 0) return false
        return when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP -> volumeUps.tryEmit(false)
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && dictating.get() > 0 -> volumeDowns.tryEmit(false)
            else -> false
        }
    }

    /**
     * The side key by way of the phone's own setting: PingMe opened again while it is already
     * in front and resumed can only be that, so it counts as a press; so does Button Mapper
     * firing [ACTION_VOICE_NOTE]. True when the intent was taken as a press.
     */
    fun fromIntent(
        intent: Intent?,
        resumed: Boolean,
    ): Boolean {
        intent ?: return false
        val shortcut = intent.action == ACTION_VOICE_NOTE
        val relaunch = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && resumed
        if (!shortcut && !relaunch) return false
        if (listeners.get() == 0) {
            Diag.note(TAG, "Voice-note launch (${intent.action}) with no chat on screen")
            return false
        }
        Diag.note(TAG, "Voice-note launch (${intent.action}) in a chat")
        presses.tryEmit(KeyEvent.KEYCODE_SEARCH)
        return true
    }

    /**
     * A chat is on screen and wants the keys, volume down too when [dictation] is true; the
     * returned function says it has gone.
     */
    fun listen(dictation: Boolean = false): () -> Unit {
        listeners.incrementAndGet()
        if (dictation) dictating.incrementAndGet()
        return {
            listeners.decrementAndGet()
            if (dictation) dictating.decrementAndGet()
        }
    }

    /**
     * The side key reports KEY_SEARCH at the kernel and the key layout maps it to the assistant
     * key (read off the owner's razr ultra 2025).
     */
    val VOICE_KEYS = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST)

    private val EVERYDAY_KEYS =
        setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
        )

    /** What Button Mapper (or anything else) fires to start or send a voice note in the open chat. */
    const val ACTION_VOICE_NOTE = "org.pingme.action.VOICE_NOTE"

    /** Volume up let go sooner than this was a volume press, not a recording. */
    const val HOLD_MS = 400L
    private const val BUFFER = 8
    private const val TAG = "PingMeKeys"
}

/**
 * One press of the side key: idle, it asks the composer for a hands-free recording (which
 * checks the microphone permission first); recording, it sends. False when the note was too
 * short to keep.
 */
fun voiceKeyPressed(voice: VoiceNotes): Boolean {
    if (voice.state.value is MicState.Recording) return voice.send()
    voice.requestLocked()
    return true
}

/** How a held volume up ended: let go quickly, so it was a volume press, or held, so it sends. */
enum class VolumeHold { VOLUME, SEND }

fun volumeHoldEnded(heldMs: Long): VolumeHold =
    if (heldMs <
        HardwareKeys.HOLD_MS
    ) {
        VolumeHold.VOLUME
    } else {
        VolumeHold.SEND
    }

/** Listens for the keys while this composer is on screen; [dictation] null leaves volume down alone. */
@Composable
fun VoiceKeyListener(
    voice: VoiceNotes?,
    dictation: Dictation?,
    onTooShort: () -> Unit,
) {
    val tooShort by rememberUpdatedState(onTooShort)
    DisposableEffect(dictation) {
        val gone = HardwareKeys.listen(dictation = dictation != null)
        onDispose { gone() }
    }
    LaunchedEffect(voice) {
        voice ?: return@LaunchedEffect
        HardwareKeys.pressed.collect { if (!voiceKeyPressed(voice)) tooShort() }
    }
    if (voice != null) {
        HeldKey(
            HardwareKeys.volumeUp,
            AudioManager.ADJUST_RAISE,
            start = { voice.state.value !is MicState.Recording && voice.start() },
            cancel = voice::cancel,
            finish = { if (!voice.send()) tooShort() },
        )
    }
    if (dictation != null) {
        HeldKey(
            HardwareKeys.volumeDown,
            AudioManager.ADJUST_LOWER,
            start = dictation::start,
            cancel = dictation::cancel,
            finish = { if (!dictation.stop()) tooShort() },
        )
    }
}

/**
 * One volume key held: [start] on the way down (after the microphone is allowed), then on
 * release either [cancel] and a volume nudge in [direction] for a short press, or [finish].
 */
@Composable
private fun HeldKey(
    moves: SharedFlow<Boolean>,
    direction: Int,
    start: () -> Boolean,
    cancel: () -> Unit,
    finish: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val ask = rememberMicPermission { }
    val begin by rememberUpdatedState(start)
    val drop by rememberUpdatedState(cancel)
    val end by rememberUpdatedState(finish)
    LaunchedEffect(moves) {
        var heldSince = 0L
        moves.collect { down ->
            if (down) {
                if (!micAllowed(context)) {
                    ask()
                } else if (begin()) {
                    heldSince = System.currentTimeMillis()
                    haptic.bump()
                }
            } else if (heldSince != 0L) {
                when (volumeHoldEnded(System.currentTimeMillis() - heldSince)) {
                    VolumeHold.VOLUME -> {
                        drop()
                        nudgeVolume(context, direction)
                    }

                    VolumeHold.SEND -> {
                        end()
                    }
                }
                heldSince = 0L
            }
        }
    }
}

private fun micAllowed(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** The volume press the chat took, done by hand: the same nudge, with the system's slider. */
private fun nudgeVolume(
    context: Context,
    direction: Int,
) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    audio.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
}
