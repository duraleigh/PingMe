// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import org.pingme.core.model.ChatId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is on screen right now. A message for the chat you are looking at never raises a
 * notification (UI_DESIGN.md 6.2); with PingMe open on any other screen the sound plays
 * and nothing lands in the shade (owner, Gate G3). Set while the chat is resumed, cleared
 * when it pauses, so a chat left open behind the lock screen still notifies.
 */
@Singleton
class ChatPresence
    @Inject
    constructor() {
        @Volatile var visible: ChatId? = null

        /** Whether PingMe is in the foreground at all. */
        @Volatile var appVisible: Boolean = false
    }
