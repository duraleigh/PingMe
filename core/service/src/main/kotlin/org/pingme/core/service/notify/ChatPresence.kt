// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import org.pingme.core.model.ChatId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which chat is on screen right now, if any. A message for the chat you are looking at
 * never raises a notification (UI_DESIGN.md 6.2). Set while the chat is resumed, cleared
 * when it pauses, so a chat left open behind the lock screen still notifies.
 */
@Singleton
class ChatPresence
    @Inject
    constructor() {
        @Volatile var visible: ChatId? = null
    }
