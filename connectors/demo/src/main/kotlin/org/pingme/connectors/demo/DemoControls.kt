// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.pingme.core.model.CallMethod
import org.pingme.core.model.CallRule
import org.pingme.core.model.Capabilities
import org.pingme.core.model.MediaRule
import org.pingme.core.model.ReactionRule
import org.pingme.core.model.ReplyRule
import org.pingme.core.model.TimeLimit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime switches for the demo network (BUILD_PLAN.md P1.5): every capability flag, and
 * how lively the scripted people are. Changes apply immediately.
 */
class DemoControls {
    private val state = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = state.asStateFlow()

    fun update(change: (Settings) -> Settings) = state.update(change)

    data class Settings(
        val capabilities: Capabilities = FULL,
        /** Scripted people message, type, and react on their own. */
        val liveActivity: Boolean = true,
        /** Roughly how often someone messages when [liveActivity] is on. */
        val activityInterval: Duration = 25.seconds,
        /** How long the other side "takes" to confirm a login. */
        val confirmationDelay: Duration = 2.seconds,
        /** How long the other side types before a reply lands. */
        val typingTime: Duration = 2.seconds,
    )

    companion object {
        /** Everything on: the richest network the UI has to handle. */
        val FULL =
            Capabilities(
                reply = ReplyRule.NATIVE,
                deleteForMe = true,
                deleteForEveryone = TimeLimit.Within(1.hours),
                reactions = ReactionRule.AnyEmoji,
                gif = MediaRule.NATIVE,
                voiceNote = MediaRule.NATIVE,
                typing = true,
                readReceipts = true,
                edit = TimeLimit.Within(15.minutes),
                nativePins = true,
                folders = true,
                startConversation = true,
                createGroup = true,
                block = true,
                multiAccount = true,
                calls = CallRule(CallMethod.CONTACT_APP_CALL, CallMethod.CONTACT_APP_CALL),
            )

        /** SMS-like: the most limited network the UI has to handle. */
        val MINIMAL =
            Capabilities(
                reply = ReplyRule.QUOTED_TEXT,
                deleteForMe = true,
                deleteForEveryone = null,
                reactions = ReactionRule.TextFallback,
                gif = MediaRule.MMS_SIZE_LIMITED,
                voiceNote = MediaRule.MMS_SIZE_LIMITED,
                typing = false,
                readReceipts = false,
                edit = null,
                nativePins = false,
                folders = false,
                startConversation = false,
                createGroup = false,
                block = false,
                multiAccount = false,
                calls = CallRule(CallMethod.DIALER, CallMethod.MEET),
            )
    }
}
