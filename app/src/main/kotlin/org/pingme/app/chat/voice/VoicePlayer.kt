// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What is playing: which note, how far in, how fast, and whether it is paused. */
data class PlayState(
    val key: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val earpiece: Boolean = false,
)

/**
 * Plays one voice note at a time (UI_DESIGN.md 5.6): play and pause, scrub, 1x / 1.5x / 2x,
 * and the earpiece when the phone is raised to the ear (proximity sensor and AudioManager).
 */
class VoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) : SensorEventListener {
    private val now = MutableStateFlow(PlayState())
    private var player: MediaPlayer? = null
    private var path: String? = null
    private var ticker: Job? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val screenOff =
        context
            .getSystemService(PowerManager::class.java)
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "PingMe:voice")

    val state: StateFlow<PlayState> = now.asStateFlow()

    /** Plays [file] (named by [key]), or pauses it if it is the one playing. */
    fun toggle(
        key: String,
        file: String,
    ) {
        val current = now.value
        when {
            current.key == key && current.playing -> pause()
            current.key == key && player != null -> resume()
            else -> open(key, file, from = 0, earpiece = false)
        }
    }

    /** Jumps to [fraction] of the way through the note named [key]. */
    fun seek(
        key: String,
        file: String,
        fraction: Float,
    ) {
        if (now.value.key != key) open(key, file, from = 0, earpiece = false, play = false)
        val target = (now.value.durationMs * fraction.coerceIn(0f, 1f)).toLong()
        player?.seekTo(target.toInt())
        now.update { it.copy(positionMs = target) }
    }

    /** 1x, then 1.5x, then 2x, then back to 1x. */
    fun nextSpeed() {
        val speed = SPEEDS[(SPEEDS.indexOf(now.value.speed) + 1) % SPEEDS.size]
        now.update { it.copy(speed = speed) }
        player?.takeIf { it.isPlaying }?.let { applySpeed(it, speed) }
    }

    fun release() {
        stop()
        now.value = PlayState(speed = now.value.speed)
    }

    // Raised to the ear: replay through the earpiece from a moment back; lowered: the speaker.
    override fun onSensorChanged(event: SensorEvent) {
        val sensor = proximity ?: return
        val near = event.values.firstOrNull()?.let { it < sensor.maximumRange } ?: return
        val current = now.value
        val file = path ?: return
        if (near == current.earpiece || !current.playing) return
        open(current.key!!, file, from = (current.positionMs - REWIND_MS).coerceAtLeast(0), earpiece = near)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private fun open(
        key: String,
        file: String,
        from: Long,
        earpiece: Boolean,
        play: Boolean = true,
    ) {
        stop()
        val made =
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attributes(earpiece))
                    setDataSource(file)
                    prepare()
                    seekTo(from.toInt())
                }
            }.getOrNull() ?: return
        player = made
        path = file
        route(earpiece)
        made.setOnCompletionListener { finished() }
        now.update { it.copy(key = key, positionMs = from, durationMs = made.duration.toLong(), earpiece = earpiece) }
        if (play) resume()
    }

    private fun resume() {
        val running = player ?: return
        applySpeed(running, now.value.speed)
        running.start()
        now.update { it.copy(playing = true) }
        proximity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (screenOff?.isHeld == false) screenOff.acquire(HOLD_MS)
        ticker?.cancel()
        ticker =
            scope.launch {
                while (isActive) {
                    player?.let { p -> now.update { it.copy(positionMs = p.currentPosition.toLong()) } }
                    delay(TICK_MS)
                }
            }
    }

    private fun pause() {
        player?.pause()
        quiet()
        now.update { it.copy(playing = false) }
    }

    private fun finished() {
        stop()
        now.update { it.copy(playing = false, positionMs = 0, earpiece = false) }
    }

    private fun stop() {
        quiet()
        player?.release()
        player = null
        route(earpiece = false)
    }

    private fun quiet() {
        ticker?.cancel()
        ticker = null
        sensors?.unregisterListener(this)
        if (screenOff?.isHeld == true) screenOff.release()
    }

    private fun applySpeed(
        running: MediaPlayer,
        speed: Float,
    ) {
        runCatching { running.playbackParams = running.playbackParams.setSpeed(speed) }
    }

    private fun attributes(earpiece: Boolean) =
        AudioAttributes
            .Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .build()

    private fun route(earpiece: Boolean) {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (earpiece) {
                manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let(manager::setCommunicationDevice)
            } else {
                manager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = !earpiece
        }
        manager.mode = if (earpiece) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    }

    companion object {
        val SPEEDS = listOf(1f, 1.5f, 2f)
        private const val TICK_MS = 50L
        private const val REWIND_MS = 500L
        private const val HOLD_MS = 10 * 60 * 1000L
    }
}
