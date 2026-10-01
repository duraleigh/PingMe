// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.notify.SoundRows
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordRuleId
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.NotificationProfile
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.SwitchSetting
import java.util.UUID

/** Where a keyword applies, as the dialog offers it. */
private enum class Where(
    val label: Int,
) {
    ALL(R.string.notify_keyword_everywhere),
    ACCOUNTS(R.string.notify_keyword_accounts),
    CHATS(R.string.notify_keyword_chats),
}

/**
 * Adds or changes one keyword (UI_DESIGN.md 10.9): the word, how it matches, which
 * accounts or chats it watches, whether it overrides mute, and its own sound and vibration.
 */
@Composable
internal fun KeywordDialog(
    rule: KeywordRule?,
    sound: NotificationProfile,
    state: SettingsState,
    onSave: (KeywordRule, NotificationProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val id = remember(rule) { rule?.id ?: KeywordRuleId(UUID.randomUUID().toString()) }
    var draft by remember(rule) { mutableStateOf(rule ?: blank(id)) }
    var tone by remember(rule) { mutableStateOf(sound) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notify_keyword_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    draft.pattern,
                    { draft = draft.copy(pattern = it) },
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    label = { Text(stringResource(R.string.notify_keyword_word)) },
                    singleLine = true,
                )
                SwitchSetting(stringResource(R.string.notify_keyword_whole), draft.wholeWord, {
                    draft = draft.copy(wholeWord = it)
                })
                SwitchSetting(stringResource(R.string.notify_keyword_case), draft.caseSensitive, {
                    draft = draft.copy(caseSensitive = it)
                })
                SwitchSetting(stringResource(R.string.notify_keyword_override), draft.overridesSilence, {
                    draft = draft.copy(overridesSilence = it)
                })
                ScopeChoice(draft.scope, state) { draft = draft.copy(scope = it) }
                SoundRows(tone.soundUri, tone.vibration, { s, v -> tone = tone.withSound(s, v) })
            }
        },
        confirmButton = {
            TextButton({
                onSave(draft.copy(pattern = draft.pattern.trim()), tone)
                onDismiss()
            }, enabled = draft.pattern.isNotBlank()) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

// All chats, some accounts, or some chats, with a tick list for the last two.
@Composable
private fun ScopeChoice(
    scope: KeywordScope,
    state: SettingsState,
    onChange: (KeywordScope) -> Unit,
) {
    val where =
        when (scope) {
            KeywordScope.All -> Where.ALL
            is KeywordScope.Accounts -> Where.ACCOUNTS
            is KeywordScope.Chats -> Where.CHATS
        }
    Column {
        ChoiceSetting(
            stringResource(
                R.string.notify_keyword_where,
            ),
            Where.entries,
            where,
            { stringResource(it.label) },
            {
                onChange(
                    when (it) {
                        Where.ALL -> KeywordScope.All
                        Where.ACCOUNTS -> KeywordScope.Accounts(emptyList())
                        Where.CHATS -> KeywordScope.Chats(emptyList())
                    },
                )
            },
        )
        ScopeTicks(scope, state, onChange)
    }
}

// The accounts or chats a keyword watches, each with a tick.
@Composable
private fun ScopeTicks(
    scope: KeywordScope,
    state: SettingsState,
    onChange: (KeywordScope) -> Unit,
) {
    when (scope) {
        KeywordScope.All -> {
            Unit
        }

        is KeywordScope.Accounts -> {
            state.accounts.forEach { account ->
                Tick(account.displayName, account.id in scope.ids) { on ->
                    onChange(KeywordScope.Accounts(if (on) scope.ids + account.id else scope.ids - account.id))
                }
            }
        }

        is KeywordScope.Chats -> {
            state.chats.forEach { chat ->
                Tick(chat.nameOverride ?: chat.title, chat.id in scope.ids) { on ->
                    onChange(KeywordScope.Chats(if (on) scope.ids + chat.id else scope.ids - chat.id))
                }
            }
        }
    }
}

@Composable
private fun Tick(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Checkbox(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
    )
}

/** One line under a keyword: where it applies and how it matches. */
@Composable
internal fun keywordSummary(
    rule: KeywordRule,
    state: SettingsState,
): String {
    val where =
        when (val scope = rule.scope) {
            KeywordScope.All -> {
                stringResource(R.string.notify_keyword_everywhere)
            }

            is KeywordScope.Accounts -> {
                state.accounts.filter { it.id in scope.ids }.joinToString { it.displayName }
            }

            is KeywordScope.Chats -> {
                state.chats.filter { it.id in scope.ids }.joinToString { it.nameOverride ?: it.title }
            }
        }
    val how =
        listOfNotNull(
            stringResource(R.string.notify_keyword_whole).takeIf { rule.wholeWord },
            stringResource(R.string.notify_keyword_override).takeIf { rule.overridesSilence },
        )
    return (listOf(where) + how).joinToString(" · ")
}

private fun blank(id: KeywordRuleId) =
    KeywordRule(
        id = id,
        pattern = "",
        wholeWord = true,
        caseSensitive = false,
        scope = KeywordScope.All,
        channelId = "keyword-${id.value}",
        overridesSilence = true,
    )
