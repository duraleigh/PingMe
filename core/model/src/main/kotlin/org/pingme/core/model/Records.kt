// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@JvmInline
@Serializable
value class KeywordRuleId(
    val value: String,
)

/** A notification keyword (UI_DESIGN.md 10.9). Each rule has its own notification channel. */
@Serializable
data class KeywordRule(
    val id: KeywordRuleId,
    val pattern: String,
    val wholeWord: Boolean,
    val caseSensitive: Boolean,
    val scope: KeywordScope,
    val channelId: String,
    /** "Override Low priority and mute". */
    val overridesSilence: Boolean,
)

/** Which chats a keyword rule applies to. */
@Serializable
sealed interface KeywordScope {
    @Serializable
    data object All : KeywordScope

    @Serializable
    data class Accounts(
        val ids: List<AccountId>,
    ) : KeywordScope

    @Serializable
    data class Chats(
        val ids: List<ChatId>,
    ) : KeywordScope
}

/** A send-later message waiting for its alarm (UI_DESIGN.md 10.13). */
@Serializable
data class ScheduledSend(
    val messageId: MessageId,
    val sendAt: Instant,
    val accountId: AccountId,
    val chatId: ChatId,
    /** The outgoing message, serialized, so it can be sent after a restart. */
    val payloadJson: String,
    val attempts: Int,
)

/** A confirmed link between a network person and a phone contact (UI_DESIGN.md 10.15). */
@Serializable
data class MergeLink(
    val personId: PersonId,
    val contactId: ContactId,
    val confirmedAt: Instant,
)

/** A "Save all incoming media" job for one attachment (UI_DESIGN.md 10.16). */
@Serializable
data class MediaSaveJob(
    val attachmentId: AttachmentId,
    val state: MediaSaveState,
)

@Serializable
enum class MediaSaveState {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}
