// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.ui.R as UiR

/**
 * Stands in for the chat screen, which is BUILD_PLAN.md P2.4, so opening a chat from the
 * inbox already lands somewhere real: the chat's name and a back arrow. Replaced in P2.4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPane(
    chatId: ChatId,
    chat: (ChatId) -> kotlinx.coroutines.flow.Flow<Chat?>,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val current by remember(chatId) { chat(chatId) }.collectAsStateWithLifecycle(null)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(current?.let { it.nameOverride ?: it.title }.orEmpty()) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                        ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_coming), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The wide-screen detail pane before any chat is picked. */
@Composable
fun NoChatPicked(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.chat_pick_one), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
