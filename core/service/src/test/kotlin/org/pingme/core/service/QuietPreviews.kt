// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import org.pingme.core.model.Message
import org.pingme.core.service.links.PreviewRequests

/** Records which messages would get a preview fetched, instead of asking Android's job system. */
class QuietPreviews(
    context: Context,
) : PreviewRequests(context) {
    val requested = mutableListOf<Message>()

    override fun request(message: Message) {
        requested += message
    }
}
