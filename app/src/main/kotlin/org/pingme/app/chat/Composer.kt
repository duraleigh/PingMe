// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import org.pingme.app.R
import org.pingme.app.chat.attach.AttachSheet
import org.pingme.app.chat.attach.SendButton
import org.pingme.app.chat.attach.StagedStrip
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
    var text by rememberSaveable(editing?.id?.value) { mutableStateOf(editing?.body.orEmpty()) }
    var attaching by remember { mutableStateOf(false) }
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recording != null && voice != null) {
            RecordingBar(recording, voice, Modifier.weight(1f))
        } else {
            outbox?.let { AttachButton { attaching = true } }
            MessageField(text, { text = it.also(onTyping) })
        }
        val sendWith = { send: (String) -> Unit ->
            send(text)
            text = ""
        }
        val sms = hooks.onSendSms?.takeIf { editing == null }
        MicOrSend(
            voice,
            hooks.onVoiceTooShort,
            something = text.isNotBlank() || staged.isNotEmpty(),
            ready = copying == 0,
            onSend = { sendWith(onSend) },
            onSendSms = sms?.let { { sendWith(it) } },
        )
    }
    if (attaching && outbox != null) AttachSheet(outbox, hooks.onProblem) { attaching = false }
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
) {
    if (voice != null && !something && ready) {
        MicButton(voice, onTooShort = onTooShort)
    } else {
        SendButton(something && ready, onSend, onSendSms)
    }
}

@Composable
private fun <T> StateFlow<T>?.collectOr(default: T): State<T> =
    (this ?: remember { MutableStateFlow(default) }).collectAsStateWithLifecycle()

@Composable
private fun AttachButton(onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(BUTTON)) {
        Icon(painterResource(UiR.drawable.ic_add), stringResource(R.string.attach))
    }
}

@Composable
private fun RowScope.MessageField(
    text: String,
    onChange: (String) -> Unit,
) {
    TextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.weight(1f).testTag(COMPOSER),
        placeholder = { Text(stringResource(R.string.chat_message_hint)) },
        shape = RoundedCornerShape(26.dp),
        maxLines = COMPOSER_LINES,
        textStyle = PingMeTheme.messageText,
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
    )
}

private const val COMPOSER_LINES = 6
private val BUTTON = 48.dp
