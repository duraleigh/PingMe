// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.Message
import org.pingme.core.model.TimeLimit
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.PingMeTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import org.pingme.core.ui.R as UiR

/** What the chat is showing on top of itself: the held message, open sheets, reaction bursts. */
@Stable
class ChatUi {
    var holding by mutableStateOf<Message?>(null)
    var pickerFor by mutableStateOf<Message?>(null)
    var deleting by mutableStateOf<List<Message>?>(null)
    var infoFor by mutableStateOf<Message?>(null)
    var forwarding by mutableStateOf<List<Message>?>(null)

    /** A scheduled message whose time is being changed (UI_DESIGN.md 10.13). */
    var rescheduling by mutableStateOf<Message?>(null)

    /** A voice note, sound, or file on its way to Downloads (owner, Gate G3). */
    var saving by mutableStateOf<Attachment?>(null)

    /**
     * The messages in the chat right now. A bubble that is fading out of the list (the
     * "sending" one, as the sent one replaces it) ignores touches, which would otherwise act
     * on a message that is gone.
     */
    var shown: Set<String> = emptySet()

    /** Where each bubble is on screen, for the overlay and the bursts. */
    val bounds = mutableStateMapOf<String, Rect>()

    /** Bumped when a reaction lands on a bubble, which wobbles. */
    val wobble = mutableStateMapOf<String, Int>()

    /** Times shown by a tap, when Appearance > Timestamps is "on tap". */
    val revealed = mutableStateMapOf<String, Boolean>()

    /** Bubbles in an obscured chat that a tap is showing for a few seconds. */
    val unblurred = mutableStateMapOf<String, Boolean>()
    val burst = BurstState()

    /** When the chat opened: only newer messages play their entrance (UI_DESIGN.md 4.5). */
    val openedAt = Clock.System.now()

    // Bubbles that have played their entrance, so scrolling back to one does not replay it,
    // and when each of your own messages last did.
    private val entered = mutableSetOf<String>()
    private val sent = mutableMapOf<String, kotlin.time.Instant>()

    /**
     * True the first time [message] is shown. Your own message is also matched by its content,
     * since the network's copy soon replaces the "sending" one under a new ID and must not pop
     * in twice.
     */
    fun enters(message: Message): Boolean {
        if (!entered.add(message.id.value)) return false
        if (!message.isOutgoing) return true
        val now = Clock.System.now()
        val content = "${message.kind}:${message.body}:${message.attachments.size}"
        val before = sent.put(content, now)
        return before == null || now - before >= SAME_SEND
    }

    /** Where a reaction chip lands: the bubble's bottom corner on the sender's side. */
    fun chipSpot(message: Message): Offset? =
        bounds[message.id.value]?.let {
            if (message.isOutgoing) {
                it.bottomRight - Offset(CHIP_INSET, 0f)
            } else {
                it.bottomLeft +
                    Offset(CHIP_INSET, 0f)
            }
        }

    private companion object {
        const val CHIP_INSET = 48f

        /** A copy of your message this soon after the "sending" one is the same message. */
        val SAME_SEND = 5.seconds
    }
}

/**
 * Reacts to [message] and plays the burst from [from] (the bar, or the bubble for a double
 * tap), with a strong haptic on the pick (UI_DESIGN.md 5.4).
 */
fun ChatUi.react(
    message: Message,
    emoji: String,
    from: Offset?,
    state: ChatUiState,
    actions: ChatScreenActions,
    haptic: HapticFeedback,
    haptics: Haptics,
) {
    val menu = actions.menu ?: return
    val added = menu.react(message, emoji, state.me)
    val to = chipSpot(message) ?: return
    if (added && haptics != Haptics.OFF) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    burst.play(
        emoji,
        if (added) BurstKind.PICKED else BurstKind.REMOVED,
        to,
        from ?: bounds[message.id.value]?.center,
        message.id.value,
    )
}

/** Other people's reactions play Land and Celebrate on the bubble they reacted to. */
@Composable
fun IncomingBursts(
    ui: ChatUi,
    actions: ChatScreenActions,
    state: ChatUiState,
) {
    LaunchedEffect(actions.incoming) {
        actions.incoming.collect { reaction ->
            val message =
                state.items
                    .asSequence()
                    .filterIsInstance<ChatItem.Bubble>()
                    .map { it.message }
                    .firstOrNull { it.id == reaction.messageId }
            val to = message?.let(ui::chipSpot) ?: return@collect
            ui.burst.play(reaction.emoji, BurstKind.INCOMING, to, key = message.id.value)
        }
    }
}

/** The action card's lines for [message] (UI_DESIGN.md 3.3). Edit shows only where the network allows it now. */
fun ChatUi.actionsFor(
    message: Message,
    state: ChatUiState,
    actions: ChatScreenActions,
    context: Context,
): List<MessageAction> {
    val menu = actions.menu
    if (message.status is org.pingme.core.model.MessageStatus.Scheduled) return scheduledActions(message, menu, context)
    val pinned = state.pinned.any { it.id == message.id }
    return buildList {
        add(MessageAction(R.string.action_reply, UiR.drawable.ic_reply, { actions.onReply(message) }))
        // One tap from the message to talking: the reply strip, then a locked recording (UI_DESIGN.md 5.6).
        actions.composer.voice?.let { voice ->
            add(
                MessageAction(R.string.action_voice_reply, UiR.drawable.ic_keyboard_voice, {
                    actions.onReply(message)
                    voice.requestLocked()
                }),
            )
        }
        if (!message.body.isNullOrBlank()) {
            add(
                MessageAction(R.string.action_copy, UiR.drawable.ic_content_copy, {
                    copy(context, listOf(message))
                }),
            )
        }
        add(MessageAction(R.string.action_forward, UiR.drawable.ic_forward, { forwarding = listOf(message) }))
        message.attachments.firstOrNull { it.kind in SAVEABLE }?.let { file ->
            add(MessageAction(R.string.action_save, UiR.drawable.ic_download, { saving = file }))
        }
        add(
            MessageAction(
                if (pinned) R.string.action_unpin_message else R.string.action_pin_message,
                UiR.drawable.ic_keep,
                { menu?.togglePin(message, pinned) },
            ),
        )
        add(MessageAction(R.string.action_select, UiR.drawable.ic_check_box, { menu?.toggle(message) }))
        add(MessageAction(R.string.action_info, UiR.drawable.ic_info, { infoFor = message }))
        if (canEdit(message, state)) {
            add(
                MessageAction(R.string.action_edit, UiR.drawable.ic_edit, {
                    menu?.startEdit(message)
                }),
            )
        }
        add(
            MessageAction(R.string.action_delete_message, UiR.drawable.ic_delete, {
                deleting = listOf(message)
            }, destructive = true),
        )
    }
}

/**
 * A message waiting for its time (UI_DESIGN.md 3.3, 10.13): change it, move it, send it now,
 * copy it, or unschedule it, which deletes it.
 */
private fun ChatUi.scheduledActions(
    message: Message,
    menu: MessageMenu?,
    context: Context,
) = listOf(
    MessageAction(R.string.action_edit, UiR.drawable.ic_edit, { menu?.startEdit(message) }),
    MessageAction(R.string.later_reschedule, UiR.drawable.ic_schedule, { rescheduling = message }),
    MessageAction(R.string.later_send_now, UiR.drawable.ic_send, { menu?.sendNow(message) }),
    MessageAction(R.string.action_copy, UiR.drawable.ic_content_copy, { copy(context, listOf(message)) }),
    MessageAction(
        R.string.later_unschedule,
        UiR.drawable.ic_delete,
        { menu?.cancelScheduled(message) },
        destructive = true,
    ),
)

/** Your own text message, on a network that edits, within its time limit. */
private fun canEdit(
    message: Message,
    state: ChatUiState,
): Boolean {
    val limit = state.capabilities?.edit ?: return false
    if (!message.isOutgoing || message.body.isNullOrBlank() || message.deletedForEveryone) return false
    return limit !is TimeLimit.Within || Clock.System.now() - message.sentAt <= limit.duration
}

/** Copies the messages' text, oldest first, one per line. */
fun copy(
    context: Context,
    messages: List<Message>,
) {
    val text = messages.sortedBy { it.sentAt }.mapNotNull { it.body }.joinToString("\n")
    context
        .getSystemService(
            ClipboardManager::class.java,
        ).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.action_copy), text))
    // Android 13 and newer confirm a copy on their own; older versions say nothing, so we do.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

/** Shares the messages' text with another app. */
fun share(
    context: Context,
    messages: List<Message>,
) {
    val text = messages.sortedBy { it.sentAt }.mapNotNull { it.body }.joinToString("\n")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The overlays and sheets the chat can show, and the burst layer over everything. */
@Composable
fun ChatOverlays(
    ui: ChatUi,
    state: ChatUiState,
    actions: ChatScreenActions,
    context: Context,
    haptic: HapticFeedback,
    lifted: @Composable (Message) -> Unit,
) {
    val network = state.account?.network
    val haptics = PingMeTheme.appearance.haptics
    val held = ui.holding
    val heldAt = held?.let { ui.bounds[it.id.value] }
    if (held != null && heldAt != null) {
        MessageOverlay(
            message = held,
            bubble = heldAt,
            quick = state.reactions.quick,
            rule = state.capabilities?.reactions,
            networkName = network?.displayName.orEmpty(),
            actions = ui.actionsFor(held, state, actions, context),
            onReact = { emoji, from ->
                ui.holding = null
                ui.react(held, emoji, from, state, actions, haptic, haptics)
            },
            onMore = {
                ui.holding = null
                ui.pickerFor = held
            },
            onDismiss = { ui.holding = null },
        ) { lifted(held) }
    }
    ui.saving?.let { wanted -> SaveToDownloads(ui, wanted, state, actions, context) }
    ui.pickerFor?.let { message ->
        val rule = state.capabilities?.reactions
        org.pingme.app.chat.emoji.EmojiPickerSheet(
            recent = state.reactions.recent,
            onPick = { emoji ->
                ui.pickerFor = null
                actions.onRememberEmoji(emoji)
                ui.react(message, emoji, null, state, actions, haptic, haptics)
            },
            onDismiss = { ui.pickerFor = null },
            allowed = (rule as? org.pingme.core.model.ReactionRule.Set)?.allowed?.toSet(),
            notAllowedReason = context.getString(R.string.not_on_network, network?.displayName.orEmpty()),
        )
    }
    Sheets(ui, state, actions)
    IncomingBursts(ui, actions, state)
    ReactionBurstLayer(
        ui.burst,
        PingMeTheme.motion,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        actions.settings.specialEmoji,
        onLand = { burst -> burst.key?.let { id -> ui.wobble[id] = (ui.wobble[id] ?: 0) + 1 } },
    )
}

@Composable
private fun Sheets(
    ui: ChatUi,
    state: ChatUiState,
    actions: ChatScreenActions,
) {
    val network = state.account?.network ?: return
    ui.deleting?.let { targets ->
        DeleteSheet(
            count = targets.size,
            everyone = everyoneDelete(targets, state.capabilities, network, Clock.System.now()),
            onForMe = { actions.menu?.deleteForMe(targets) },
            onForEveryone = { actions.menu?.deleteForEveryone(targets) },
            onDismiss = { ui.deleting = null },
        )
    }
    ui.infoFor?.let { InfoSheet(it, network) { ui.infoFor = null } }
    ui.forwarding?.let { targets ->
        ForwardSheet(actions.forwardTargets, onSend = { chats -> actions.menu?.forward(targets, chats) }, onDismiss = {
            ui.forwarding =
                null
        })
    }
    ui.rescheduling?.let { waiting ->
        org.pingme.app.chat.later.SendLaterSheet(
            onPick = { at -> actions.menu?.reschedule(waiting, at) },
            onDismiss = { ui.rescheduling = null },
        )
    }
}

/**
 * Copies [wanted] to Downloads once it is on the phone, fetching it first when it is not
 * (the chat's newest copy of the attachment carries the path), and says how it went.
 */
@Composable
private fun SaveToDownloads(
    ui: ChatUi,
    wanted: Attachment,
    state: ChatUiState,
    actions: ChatScreenActions,
    context: Context,
) {
    val latest =
        state.items
            .asSequence()
            .filterIsInstance<ChatItem.Bubble>()
            .flatMap { it.message.attachments }
            .firstOrNull { it.id == wanted.id } ?: wanted
    val saved = stringResource(R.string.media_saved_downloads)
    val failed = stringResource(R.string.media_save_failed)
    LaunchedEffect(latest.id, latest.localPath) {
        if (latest.localPath == null) {
            actions.onNeed(latest)
            delay(DOWNLOAD_WAIT_MS)
            android.widget.Toast
                .makeText(context, failed, android.widget.Toast.LENGTH_SHORT)
                .show()
            ui.saving = null
            return@LaunchedEffect
        }
        val ok = saveToDownloads(context, latest)
        android.widget.Toast
            .makeText(context, if (ok) saved else failed, android.widget.Toast.LENGTH_SHORT)
            .show()
        ui.saving = null
    }
}

/** What the hold menu offers to save to Downloads; pictures and videos save from the viewer instead. */
private val SAVEABLE = setOf(AttachmentKind.VOICE, AttachmentKind.AUDIO, AttachmentKind.FILE)

/** How long a fetch may take before "Save" gives up. */
private const val DOWNLOAD_WAIT_MS = 60_000L
