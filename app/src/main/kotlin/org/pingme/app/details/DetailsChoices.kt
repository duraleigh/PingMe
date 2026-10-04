// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.VibrationPattern
import org.pingme.core.ui.theme.ChatLook
import kotlin.time.Duration

// What each part of Chat details can change (UI_DESIGN.md 3.4).

/** What the header's buttons do. */
class HeaderButtons(
    val onSearch: () -> Unit,
    val onMute: () -> Unit,
    val onUnmute: () -> Unit,
    val onPin: (Boolean) -> Unit,
    val onRename: (String) -> Unit,
)

/** What the Look section changes. */
class LookChoices(
    val onLook: (ChatLook) -> Unit,
    val onPicture: (android.net.Uri, Boolean) -> Unit,
)

/** What the privacy and chat sections change. */
class ChatChoices(
    val onObscured: (Boolean) -> Unit,
    val onLowPriority: (Boolean) -> Unit,
    val onFolder: (ChatFolder) -> Unit,
    val onArchived: (Boolean) -> Unit,
    val onBlock: () -> Unit,
    val onDelete: () -> Unit,
)

/** What the notification rows change. */
class NotificationChoices(
    val onMute: (Duration?) -> Unit,
    val onNotification: (sound: String?, vibration: VibrationPattern?) -> Unit,
)

/** What the merge section can do (UI_DESIGN.md 10.15; owner, Phase 7). */
class MergeChoices(
    val onMergeWith: (List<ChatId>) -> Unit,
    val onAddMembers: (List<ChatId>) -> Unit,
    val onSplit: (ChatId) -> Unit,
    val onDefault: (AccountId) -> Unit,
)
