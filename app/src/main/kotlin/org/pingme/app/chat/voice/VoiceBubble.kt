// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.model.Attachment
import org.pingme.core.ui.R as UiR

/** The chat's one voice-note player, stopped when the chat closes. */
@Composable
fun rememberVoicePlayer(): VoicePlayer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { VoicePlayer(context.applicationContext, scope) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * A voice note in its bubble (UI_DESIGN.md 5.6): play and pause, a waveform to tap or drag
 * through, the time, and the speed (1x, 1.5x, 2x).
 */
@Composable
fun VoiceBubble(
    attachment: Attachment,
    player: VoicePlayer?,
    modifier: Modifier = Modifier,
    transcripts: Transcripts? = null,
) {
    Column(modifier.testTag(VOICE_NOTE)) {
        VoiceControls(attachment, player)
        val path = attachment.localPath
        if (transcripts != null && path != null) TranscriptLine(transcripts, attachment.id.value, path)
    }
}

// Play, the waveform to scrub, the time, and the speed.
@Composable
private fun VoiceControls(
    attachment: Attachment,
    player: VoicePlayer?,
) {
    val path = attachment.localPath
    val key = attachment.id.value
    val playing by (player?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlayState()) })
        .collectAsStateWithLifecycle()
    val mine = playing.key == key
    val bars by produceState(FloatArray(0), path) { value = path?.let { Waveform.of(it) } ?: FloatArray(0) }
    val total = attachment.durationMs ?: playing.durationMs.takeIf { mine } ?: 0L
    val done = if (mine && playing.durationMs > 0) playing.positionMs.toFloat() / playing.durationMs else 0f
    val play = { if (path != null) player?.toggle(key, path) }
    val seek = { fraction: Float -> if (path != null) player?.seek(key, path, fraction) }
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton({ play() }, enabled = path != null) {
            if (mine && playing.playing) {
                Icon(painterResource(UiR.drawable.ic_pause), stringResource(R.string.voice_pause))
            } else {
                Icon(painterResource(UiR.drawable.ic_play_arrow), stringResource(R.string.voice_play))
            }
        }
        Bars(
            bars,
            done,
            Modifier
                .width(WAVE_WIDTH)
                .height(WAVE_HEIGHT)
                .padding(horizontal = 6.dp)
                .pointerInput(path) {
                    detectTapGestures { seek(it.x / size.width) }
                }.pointerInput(path) {
                    detectHorizontalDragGestures { change, _ -> seek(change.position.x / size.width) }
                },
        )
        Text(
            clock(if (mine && playing.positionMs > 0) playing.positionMs else total),
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(
            { player?.nextSpeed() },
            Modifier.padding(start = 2.dp).size(width = SPEED_WIDTH, height = SPEED_HEIGHT),
        ) { Text(speedLabel(playing.speed), style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun Bars(
    bars: FloatArray,
    done: Float,
    modifier: Modifier = Modifier,
) {
    val played = LocalContentColor.current
    val ahead = played.copy(alpha = AHEAD)
    Canvas(modifier) {
        val count = bars.size.takeIf { it > 0 } ?: Waveform.BARS
        val bar = size.width / count
        for (i in 0 until count) {
            val level = bars.getOrElse(i) { MIN_BAR }
            val h = (size.height * level).coerceAtLeast(3f)
            drawRoundRect(
                if ((i + 0.5f) / count <= done) played else ahead,
                Offset(i * bar + bar * GAP, (size.height - h) / 2),
                Size(bar * (1 - 2 * GAP), h),
                CornerRadius(bar / 2),
            )
        }
    }
}

// The note's words under it, written out on the phone (UI_DESIGN.md 5.6).
@Composable
private fun TranscriptLine(
    transcripts: Transcripts,
    key: String,
    path: String,
) {
    val transcript by remember(key) { transcripts.of(key, path) }.collectAsStateWithLifecycle()
    val (text, italic) =
        when (val t = transcript) {
            is Transcript.Text -> t.text to false
            Transcript.Working -> stringResource(R.string.voice_transcribing) to true
            Transcript.Unavailable -> stringResource(R.string.voice_transcript_unavailable) to true
            Transcript.Failed -> stringResource(R.string.voice_transcript_failed) to true
        }
    Text(
        text,
        Modifier.padding(bottom = 6.dp).widthIn(max = TRANSCRIPT_WIDTH),
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = if (italic) FontStyle.Italic else null,
    )
}

/** "1x", "1.5x", "2x". */
fun speedLabel(speed: Float) = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

const val VOICE_NOTE = "voice-note"
private val WAVE_WIDTH = 140.dp
private val TRANSCRIPT_WIDTH = 260.dp
private val WAVE_HEIGHT = 32.dp
private val SPEED_WIDTH = 52.dp
private val SPEED_HEIGHT = 36.dp
private const val AHEAD = 0.4f
private const val MIN_BAR = 0.08f
private const val GAP = 0.2f
