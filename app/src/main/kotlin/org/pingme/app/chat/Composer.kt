// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import org.pingme.app.R
import org.pingme.app.chat.attach.AttachSheet
import org.pingme.app.chat.attach.Outbox
import org.pingme.app.chat.attach.SendButton
import org.pingme.app.chat.attach.StagedStrip
import org.pingme.app.chat.gif.GifPickerSheet
import org.pingme.app.chat.gif.GifPicks
import org.pingme.app.chat.later.SendLaterSheet
import org.pingme.app.chat.voice.MicButton
import org.pingme.app.chat.voice.MicState
import org.pingme.app.chat.voice.RecordingBar
import org.pingme.app.chat.voice.VoiceNotes
import org.pingme.app.chat.voice.VoiceReplyStarter
import org.pingme.core.model.Message
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * The composer (UI_DESIGN.md 3.2): attach, the text field, and a button that is the
 * microphone while there is nothing to send and the send button once there is (5.6).
 */
@Composable
internal fun Composer(
    onSend: (String) -> Unit,
    onTyping: (String) -> Unit,
    editing: Message? = null,
    hooks: ComposerHooks = ComposerHooks(),
) {
    // Editing starts from the message's text; a new edit (or none) starts afresh.
    val field =
        rememberSaveable(editing?.id?.value, saver = TextFieldState.Saver) { TextFieldState(editing?.body.orEmpty()) }
    val typing by rememberUpdatedState(onTyping)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.drop(1).collect { typing(it) } }
    var sheet by remember { mutableStateOf(ComposerSheet.NONE) }
    val outbox = hooks.outbox?.takeIf { editing == null }
    val voice = hooks.voice?.takeIf { editing == null }
    val staged by outbox?.staged.collectOr(emptyList())
    val copying by outbox?.busy.collectOr(0)
    val mic by voice?.state.collectOr(MicState.Idle)
    val recording = mic as? MicState.Recording
    voice?.let { VoiceReplyStarter(it) }
    if (recording?.locked == true && voice != null) {
        RecordingBar(recording, voice, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        return
    }
    StagedStrip(staged, copying > 0, { outbox?.remove(it) })
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (recording != null && voice != null) {
            RecordingBar(recording, voice, Modifier.weight(1f))
        } else {
            outbox?.let { ComposerIcon(UiR.drawable.ic_add, R.string.attach) { sheet = ComposerSheet.ATTACH } }
            hooks.gifs?.takeIf { editing == null }?.let {
                ComposerIcon(UiR.drawable.ic_gif_box, R.string.gif) { sheet = ComposerSheet.GIF }
            }
            MessageField(field, outbox)
        }
        val sendWith = { send: (String) -> Unit ->
            send(field.text.toString())
            field.clearText()
        }
        val sms = hooks.onSendSms?.takeIf { editing == null }
        MicOrSend(
            voice,
            hooks.onVoiceTooShort,
            something = field.text.isNotBlank() || staged.isNotEmpty(),
            ready = copying == 0,
            onSend = { sendWith(onSend) },
            onSendSms = sms?.let { { sendWith(it) } },
            onSendLater = hooks.onSchedule?.takeIf { editing == null }?.let { { sheet = ComposerSheet.LATER } },
        )
    }
    if (sheet == ComposerSheet.LATER) {
        SendLaterSheet(
            onPick = { at ->
                hooks.onSchedule?.invoke(field.text.toString(), at)
                field.clearText()
            },
            onDismiss = { sheet = ComposerSheet.NONE },
        )
    }
    ComposerSheets(sheet, hooks, outbox) { sheet = ComposerSheet.NONE }
}

/** Which sheet the composer has open. */
private enum class ComposerSheet { NONE, ATTACH, GIF, LATER }

@Composable
private fun ComposerSheets(
    sheet: ComposerSheet,
    hooks: ComposerHooks,
    outbox: Outbox?,
    onClose: () -> Unit,
) {
    when (sheet) {
        ComposerSheet.ATTACH -> {
            outbox?.let { AttachSheet(it, hooks.onProblem, onClose) }
        }

        ComposerSheet.GIF -> {
            val gifs = hooks.gifs ?: return
            val picks = hooks.gifPicks ?: return
            GifPickerSheet(
                gifs,
                GifPicks(
                    onGif = {
                        onClose()
                        picks.onGif(it)
                    },
                    onFavourite = {
                        onClose()
                        picks.onFavourite(it)
                    },
                ),
                onClose,
            )
        }

        ComposerSheet.NONE, ComposerSheet.LATER -> {
            Unit
        }
    }
}

// The microphone while there is nothing to send, the send button once there is (UI_DESIGN.md 5.6).
@Composable
private fun MicOrSend(
    voice: VoiceNotes?,
    onTooShort: () -> Unit,
    something: Boolean,
    ready: Boolean,
    onSend: () -> Unit,
    onSendSms: (() -> Unit)?,
    onSendLater: (() -> Unit)?,
) {
    if (voice != null && !something && ready) {
        MicButton(voice, onTooShort = onTooShort)
    } else {
        SendButton(something && ready, onSend, onSendSms, onSendLater = onSendLater)
    }
}

@Composable
private fun <T> StateFlow<T>?.collectOr(default: T): State<T> =
    (this ?: remember { MutableStateFlow(default) }).collectAsStateWithLifecycle()

// Keyboards that insert pictures and GIFs (Gboard and others) hand them over here (UI_DESIGN.md 5.5).
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.MessageField(
    field: TextFieldState,
    outbox: Outbox?,
) {
    val receiver =
        remember(outbox) {
            ReceiveContentListener { content ->
                if (outbox == null || !content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
                val uris = mutableListOf<android.net.Uri>()
                val rest = content.consume { item -> item.uri?.let { uris += it } != null }
                if (uris.isNotEmpty()) outbox.addPicked(uris)
                rest
            }
        }
    TextField(
        state = field,
        modifier = Modifier.weight(1f).testTag(COMPOSER).contentReceiver(receiver),
        placeholder = { Text(stringResource(R.string.chat_message_hint)) },
        shape = RoundedCornerShape(26.dp),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = COMPOSER_LINES),
        textStyle = PingMeTheme.messageText,
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
private fun ComposerIcon(
    icon: Int,
    label: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick, Modifier.size(BUTTON)) { Icon(painterResource(icon), stringResource(label)) }
}

private const val COMPOSER_LINES = 6
private val BUTTON = 44.dp
