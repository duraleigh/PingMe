// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId

/** One chat the picker can offer: its name, network, and photo. */
data class PickableChat(
    val id: ChatId,
    val title: String,
    val network: NetworkId,
    val photo: String? = null,
)
