// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import org.pingme.core.model.Message

/**
 * The box's text. Editing starts from the message's text; a new edit (or none) starts afresh,
 * with the words left unsent last time put back (owner, 2026-11-04: leaving the chat cleared the
 * box). Each change tells the other side someone is typing, and is kept as the chat's draft.
 */
@Composable
internal fun rememberComposerField(
    editing: Message?,
    hooks: ComposerHooks,
    onTyping: (String) -> Unit,
): TextFieldState {
    val field =
        rememberSaveable(editing?.id?.value, saver = TextFieldState.Saver) { TextFieldState(editing?.body.orEmpty()) }
    val typing by rememberUpdatedState(onTyping)
    val keep by rememberUpdatedState(hooks.onDraft.takeIf { editing == null })
    // The words left unsent last time come back into an empty box, once, without telling the
    // other side that anyone is typing (owner, 2026-11-04).
    var restored by rememberSaveable(editing?.id?.value) { mutableStateOf<String?>(null) }
    val saved by (hooks.draft ?: remember { MutableStateFlow<String?>(null) }).collectAsStateWithLifecycle()
    LaunchedEffect(saved, editing) {
        val words = saved
        if (editing == null && restored == null && words != null) {
            restored = words
            if (words.isNotEmpty() && field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(words)
        }
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.drop(1).collect {
            keep?.invoke(it)
            if (it != restored) typing(it)
            restored = ""
        }
    }
    return field
}
