// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.pingme.app.R
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.Person
import org.pingme.core.service.ChatActions
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.ui.components.Avatar
import javax.inject.Inject
import org.pingme.core.ui.R as UiR

/** Navigation route: a new chat, or a new group when [group] is true (the + menu, UI_DESIGN.md 3.1). */
@Serializable
data class NewChatRoute(
    val group: Boolean = false,
)

data class NewChatUiState(
    /** Accounts whose network can start chats (or make groups): the others cannot be picked. */
    val accounts: List<Account> = emptyList(),
    val account: AccountId? = null,
    val text: String = "",
    val title: String = "",
    val members: List<String> = emptyList(),
    val suggestions: List<Person> = emptyList(),
    val working: Boolean = false,
)

/** New chat and New group (UI_DESIGN.md 3.1, DESIGN.md 6.2). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewChatViewModel
    @Inject
    constructor(
        accounts: AccountRepository,
        contacts: ContactRepository,
        registry: ConnectorRegistry,
        private val actions: ChatActions,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val group = saved.toRoute<NewChatRoute>().group
        private val form = MutableStateFlow(NewChatUiState())
        private val done = Channel<ChatId>(Channel.BUFFERED)
        private val failures = Channel<String?>(Channel.BUFFERED)

        /** The chat to open once it exists. */
        val opened = done.receiveAsFlow()

        /** A reason to show when the network said no. */
        val failed = failures.receiveAsFlow()

        private val usable =
            combine(accounts.accounts(), flowOf(registry)) { all, reg ->
                all.filter { account ->
                    val caps = reg[account.network]?.capabilities
                    caps != null && if (group) caps.createGroup else caps.startConversation
                }
            }

        val state: StateFlow<NewChatUiState> =
            combine(form, usable) { f, list ->
                f.copy(
                    accounts = list,
                    account =
                        f.account?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id,
                )
            }.flatMapLatest { s ->
                val id = s.account ?: return@flatMapLatest flowOf(s)
                contacts.people(id).combineWith(s)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), NewChatUiState())

        private fun kotlinx.coroutines.flow.Flow<List<Person>>.combineWith(s: NewChatUiState) =
            combine(this, flowOf(s)) { people, st ->
                val q = st.text.trim()
                st.copy(
                    suggestions =
                        people
                            .filter { p ->
                                q.isEmpty() || p.displayName.contains(q, true) ||
                                    p.networkHandle.contains(q, true)
                            }.filter { p -> p.networkHandle !in st.members }
                            .take(MAX_SUGGESTIONS),
                )
            }

        fun pickAccount(id: AccountId) = form.update { it.copy(account = id) }

        /**
         * The two text boxes. Held in Compose state, which the text box reads at once:
         * a box fed from a flow can be redrawn with an older value between keystrokes and
         * lose what was typed.
         */
        var typed by mutableStateOf("")
            private set
        var typedTitle by mutableStateOf("")
            private set

        fun type(text: String) {
            typed = text
            form.update { it.copy(text = text) }
        }

        fun setTitle(text: String) {
            typedTitle = text
            form.update { it.copy(title = text) }
        }

        /** New chat: starts at once. New group: adds a member. */
        fun choose(handle: String) {
            val h = handle.trim()
            if (h.isEmpty()) return
            if (group) {
                typed = ""
                form.update { it.copy(members = (it.members + h).distinct(), text = "") }
            } else {
                run { actions.startChat(requireNotNull(state.value.account), h) }
            }
        }

        fun remove(handle: String) = form.update { it.copy(members = it.members - handle) }

        fun createGroup() {
            // The name and members come from the form itself, which always has the last keystroke;
            // the combined screen state can be a step behind.
            val typed = form.value
            val account = requireNotNull(state.value.account)
            run { actions.createGroup(account, typed.title.trim(), typed.members) }
        }

        private fun run(block: suspend () -> ChatId) {
            viewModelScope.launch {
                form.update { it.copy(working = true) }
                try {
                    done.send(block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedCapabilityException) {
                    failures.send(e.message)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Offline, a bad number, the network refused: say so and let the user try again.
                    failures.send(e.message)
                } finally {
                    form.update { it.copy(working = false) }
                }
            }
        }

        private companion object {
            const val STOP_AFTER = 5_000L
            const val MAX_SUGGESTIONS = 30
        }
    }

@Composable
fun NewChatRoute(
    onBack: () -> Unit,
    onOpenChat: (ChatId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) { viewModel.opened.collect(onOpenChat) }
    LaunchedEffect(viewModel) {
        viewModel.failed.collect { snackbar.showSnackbar(it ?: resources.getString(R.string.new_chat_failed)) }
    }
    NewChatScreen(
        group = viewModel.group,
        // The boxes show what was typed at once; the rest of the state follows from the flow.
        state = state.copy(text = viewModel.typed, title = viewModel.typedTitle),
        actions =
            NewChatActions(
                onBack,
                viewModel::pickAccount,
                viewModel::type,
                viewModel::setTitle,
                viewModel::choose,
                viewModel::remove,
                viewModel::createGroup,
            ),
        modifier = modifier,
        snackbar = snackbar,
    )
}

class NewChatActions(
    val onBack: () -> Unit,
    val onAccount: (AccountId) -> Unit,
    val onText: (String) -> Unit,
    val onTitle: (String) -> Unit,
    val onChoose: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onCreate: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    group: Boolean,
    state: NewChatUiState,
    actions: NewChatActions,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (group) R.string.fab_new_group else R.string.fab_new_chat)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.accounts.isEmpty()) {
            Text(
                stringResource(if (group) R.string.new_group_no_accounts else R.string.new_chat_no_accounts),
                Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { NewChatForm(group, state, actions) }
            val typed = state.text.trim()
            if (typed.isNotEmpty()) {
                item {
                    ListItem(
                        onClick = { actions.onChoose(typed) },
                        enabled = !state.working,
                        leadingContent = {
                            Icon(
                                painterResource(if (group) UiR.drawable.ic_add else UiR.drawable.ic_chat),
                                null,
                            )
                        },
                    ) { Text(stringResource(if (group) R.string.new_group_add else R.string.new_chat_start, typed)) }
                }
            }
            items(state.suggestions, key = { it.id.value }) { person ->
                ListItem(
                    onClick = { actions.onChoose(person.networkHandle) },
                    enabled = !state.working,
                    leadingContent = { Avatar(person.displayName, size = 40.dp) },
                    supportingContent = { Text(person.networkHandle) },
                ) { Text(person.displayName) }
            }
        }
    }
}

@Composable
private fun NewChatForm(
    group: Boolean,
    state: NewChatUiState,
    actions: NewChatActions,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.accounts.size > 1) {
            Text(stringResource(R.string.new_chat_on), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.accounts.forEach { account ->
                    FilterChip(
                        selected = account.id == state.account,
                        onClick = { actions.onAccount(account.id) },
                        label = { Text(account.displayName) },
                        leadingIcon = { NetworkDot(account.network) },
                    )
                }
            }
        }
        if (group) {
            OutlinedTextField(
                state.title,
                actions.onTitle,
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.new_group_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.members.forEach { handle ->
                    InputChip(
                        selected = true,
                        onClick = { actions.onRemove(handle) },
                        label = { Text(handle) },
                        trailingIcon = {
                            Icon(
                                painterResource(UiR.drawable.ic_close),
                                stringResource(R.string.new_group_remove, handle),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            state.text,
            actions.onText,
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.new_chat_who)) },
            singleLine = true,
        )
        if (group) {
            Button(
                onClick = actions.onCreate,
                enabled = !state.working && state.title.isNotBlank() && state.members.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.new_group_create)) }
        }
    }
}
