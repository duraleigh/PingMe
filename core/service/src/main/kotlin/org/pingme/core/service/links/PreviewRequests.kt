// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.links

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.model.Message
import org.pingme.core.service.work.Work
import javax.inject.Inject
import javax.inject.Singleton

/** Asks for a link preview to be fetched for a message that has a link and no preview yet. */
@Singleton
open class PreviewRequests
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        open fun request(message: Message) {
            if (message.linkPreview != null || message.deletedForEveryone) return
            val body = message.body ?: return
            if (!CleanLinks.LINK.containsMatchIn(body)) return
            Work.linkPreview(context, message.id)
        }
    }
