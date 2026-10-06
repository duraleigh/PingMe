// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.pingme.core.connector.Diag
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's physical keys inside a chat (owner, 2026-10-06): the razr's dedicated side key
 * starts a hands-free voice note in the open chat and, pressed again, sends it (UI_DESIGN.md
 * 5.6). Only a chat that is on screen takes the key; anywhere else it keeps its normal job.
 * The activity hands every key-down here; the chat's composer listens.
 */
object HardwareKeys {
    private val presses = MutableSharedFlow<Int>(extraBufferCapacity = BUFFER)
    private val listeners = AtomicInteger()

    /** The key codes of the voice key, one per press, while a chat listens. */
    val pressed: SharedFlow<Int> = presses.asSharedFlow()

    /** The activity's key-down. True when a chat on screen takes the key, so nothing else sees it. */
    fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
    ): Boolean {
        if (listeners.get() == 0) return false
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

    /**
     * The side key by way of the phone's own setting: Motorola's key service takes the key
     * before any app sees it (found on the phone, 2026-10-06), but it will open an app of the
     * owner's choosing. PingMe opened again while it is already in front and resumed can only
     * be that, so it counts as a press; so does Button Mapper firing [ACTION_VOICE_NOTE].
     * True when the intent was taken as a press.
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

    /** A chat is on screen and wants the key; the returned function says it has gone. */
    fun listen(): () -> Unit {
        listeners.incrementAndGet()
        return { listeners.decrementAndGet() }
    }

    /**
     * The side key reports KEY_SEARCH at the kernel (read off the owner's razr ultra 2025); Android
     * or Motorola may hand it on as the search or an assistant key.
     */
    val VOICE_KEYS = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST)

    private val EVERYDAY_KEYS =
        setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
        )

    /** What Button Mapper (or anything else) fires to start or send a voice note in the open chat. */
    const val ACTION_VOICE_NOTE = "org.pingme.action.VOICE_NOTE"
    private const val BUFFER = 8
    private const val TAG = "PingMeKeys"
}

/**
 * One press of the voice key: idle, it asks the composer for a hands-free recording (which
 * checks the microphone permission first); recording, it sends. False when the note was too
 * short to keep.
 */
fun voiceKeyPressed(voice: VoiceNotes): Boolean {
    if (voice.state.value is MicState.Recording) return voice.send()
    voice.requestLocked()
    return true
}

/** Listens for the voice key while this composer is on screen. */
@Composable
fun VoiceKeyListener(
    voice: VoiceNotes,
    onTooShort: () -> Unit,
) {
    val tooShort by rememberUpdatedState(onTooShort)
    DisposableEffect(Unit) {
        val gone = HardwareKeys.listen()
        onDispose { gone() }
    }
    LaunchedEffect(voice) {
        HardwareKeys.pressed.collect { if (!voiceKeyPressed(voice)) tooShort() }
    }
}
