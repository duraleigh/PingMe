// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import org.pingme.core.model.ChatId

/** What a suggestion card can do. */
class SuggestionActions(
    val onRemove: (String, ChatId) -> Unit,
    val onAdd: (String, List<ChatId>) -> Unit,
    val onDismiss: (String) -> Unit,
    val onMerge: (SuggestionCard) -> Unit,
    /** The member the merged chat sends from (owner, 2026-10-03). */
    val onDefault: (String, ChatId) -> Unit = { _, _ -> },
    /** The member whose picture stands for the merged chat; null for the contact's photo. */
    val onPhoto: (String, ChatId?) -> Unit = { _, _ -> },
)
