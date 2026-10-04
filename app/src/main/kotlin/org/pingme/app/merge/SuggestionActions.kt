// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import org.pingme.core.model.ChatId

/** What a suggestion card can do. */
class SuggestionActions(
    val onRemove: (String, ChatId) -> Unit,
    val onAdd: (String, List<ChatId>) -> Unit,
    val onDismiss: (String) -> Unit,
    val onMerge: (SuggestionCard) -> Unit,
)
