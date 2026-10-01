// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.pingme.core.model.Capabilities
import org.pingme.core.model.Message
import org.pingme.core.model.NetworkId
import org.pingme.core.model.TimeLimit
import kotlin.time.Instant

/** Whether delete for everyone is possible now, and the one line that says so (UI_DESIGN.md 5.3). */
sealed interface EveryoneDelete {
    data class Allowed(
        val limit: TimeLimit,
    ) : EveryoneDelete

    data class Unsupported(
        val network: NetworkId,
    ) : EveryoneDelete

    data object NotYours : EveryoneDelete

    data class Expired(
        val limit: TimeLimit.Within,
    ) : EveryoneDelete
}

/** Delete for everyone needs the network's support, your own messages, and the time limit not passed. */
fun everyoneDelete(
    targets: List<Message>,
    capabilities: Capabilities?,
    network: NetworkId,
    now: Instant,
): EveryoneDelete {
    val limit = capabilities?.deleteForEveryone ?: return EveryoneDelete.Unsupported(network)
    if (targets.any { !it.isOutgoing }) return EveryoneDelete.NotYours
    if (limit is TimeLimit.Within &&
        targets.any { now - it.sentAt > limit.duration }
    ) {
        return EveryoneDelete.Expired(limit)
    }
    return EveryoneDelete.Allowed(limit)
}
