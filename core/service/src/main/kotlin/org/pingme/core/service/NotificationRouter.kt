// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.model.Account
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.Message
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.NotificationSettings
import org.pingme.core.service.notify.ChatPresence
import org.pingme.core.service.notify.MessageNotifications
import org.pingme.core.service.notify.NotificationChannels
import org.pingme.core.service.notify.OneTimeCodes
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatOverridesRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/** What to do with one incoming message. */
sealed interface NotificationDecision {
    data object Drop : NotificationDecision

    data class Notify(
        val channelId: String,
        /** The keyword that matched, named on the notification (UI_DESIGN.md 10.9). */
        val keyword: String? = null,
        /** Shown without a sound: the account, folder, or chat is set to Silent. */
        val silent: Boolean = false,
    ) : NotificationDecision
}

/**
 * Decides whether and how a new message notifies (BUILD_PLAN.md P4.1). Precedence: a
 * matching keyword rule > the chat's own channel > the Instagram folder's > the account's >
 * default. Dropped: your own messages, a message already in the store (a reaction or a
 * status change re-sends the message; owner, Gate G2), the chat on screen, muted and low
 * priority chats unless a keyword overrides, and accounts or folders set to Off. Obscured
 * chats show "New message" only (UI_DESIGN.md 10.10).
 */
@Singleton
class NotificationRouter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val chats: ChatRepository,
        private val messages: MessageRepository,
        private val contacts: ContactRepository,
        private val overrides: ChatOverridesRepository,
        private val accounts: AccountRepository,
        private val settings: SettingsRepository,
        private val presence: ChatPresence,
        private val clock: Clock,
    ) {
        private val channels = NotificationChannels(context)
        private val shown = MessageNotifications(context)
        private val sounds =
            org.pingme.core.service.notify
                .NotificationSounds(context)

        /**
         * Whether [event] brings a message the store has not seen. Ask before the event is
         * applied: afterwards every message is in the store.
         */
        suspend fun isFresh(event: ConnectorEvent): Boolean {
            if (event !is ConnectorEvent.NewMessage) return false
            val message = event.message.message
            return !message.isOutgoing && messages.get(message.id) == null
        }

        /**
         * Posts for a fresh incoming message, after it has been stored. A chat the network
         * now reports as read (read on the phone, in Google Messages) takes its notification
         * down (owner, Gate G3).
         */
        suspend fun onEvent(
            event: ConnectorEvent,
            fresh: Boolean = true,
        ) {
            if (event is ConnectorEvent.ChatUpdated && event.chat.unreadCount == 0) {
                clear(event.chat.id)
                return
            }
            if (event !is ConnectorEvent.NewMessage || !fresh) return
            val message = event.message.message
            val chat = chats.get(message.chatId) ?: return
            val account = accounts.get(chat.accountId)
            val app = settings.app.first().notifications
            val decision =
                decide(
                    chat,
                    message,
                    clock.now(),
                    settings.keywordRules().first(),
                    app,
                    overrides.get(chat.id),
                    account,
                    presence.visible,
                )
            if (decision !is NotificationDecision.Notify) return
            // A one-time code gets a Copy code button; with auto-copy on it is copied at once (UI_DESIGN.md 10.6).
            val code = if (chat.isObscured) null else OneTimeCodes.find(message.body)
            if (code != null && app.autoCopyCodes) OneTimeCodes.copy(context, code)
            val channel = channelFor(decision, chat, account, app)
            // PingMe open on another screen: the sound, and nothing in the shade (owner, Gate G3).
            if (presence.appVisible) {
                if (!decision.silent) sounds.play(channel)
                return
            }
            if (!allowed()) return
            val sender = event.message.sender ?: contacts.person(message.senderId)
            shown.post(decision.copy(channelId = channel), chat, message, sender, code)
        }

        /** Takes this chat's notifications down: it was opened, read, or replied to. */
        fun clear(chatId: ChatId) = shown.clear(chatId)

        /** A picture has finished downloading: the notification for its message shows it (owner, Gate G3). */
        suspend fun pictureArrived(
            attachment: org.pingme.core.model.Attachment,
            file: java.io.File,
        ) {
            if (attachment.kind !in PICTURES) return
            val messageId = messages.messageOf(attachment.id) ?: return
            val message = messages.get(messageId) ?: return
            shown.showPicture(message.chatId, messageId, file, attachment.mimeType)
        }

        /** A scheduled message went out well after its time (UI_DESIGN.md 10.13): say so. */
        suspend fun sentLate(message: Message) {
            if (!allowed()) return
            val chat = chats.get(message.chatId) ?: return
            val channel = channels.ensure(NotificationChannels.DEFAULT, context.getString(R.string.channel_messages))
            shown.postLate(channel, chat, message)
        }

        private fun allowed() =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        // The channel exists by the time the notification goes out, with the sound its owner chose.
        private suspend fun channelFor(
            decision: NotificationDecision.Notify,
            chat: Chat,
            account: Account?,
            app: NotificationSettings,
        ): String {
            val id = decision.channelId
            val chatName = chat.nameOverride ?: chat.title
            return when {
                id == NotificationChannels.DEFAULT -> {
                    channels.ensure(id, context.getString(R.string.channel_messages))
                }

                id.startsWith(CHAT) -> {
                    val own = overrides.get(chat.id)
                    channels.ensure(id, chatName, NotificationMode.NORMAL, own.soundUri, own.vibration)
                }

                id.startsWith(KEYWORD) -> {
                    val profile = app.keywords[decision.keyword.orEmpty()]
                    val name = context.getString(R.string.channel_keyword, decision.keyword.orEmpty())
                    channels.ensure(
                        id,
                        name,
                        profile?.mode ?: NotificationMode.NORMAL,
                        profile?.soundUri,
                        profile?.vibration,
                    )
                }

                id.startsWith(FOLDER) -> {
                    val folder = chat.folder
                    val profile = folder?.let { app.folder(it) }
                    channels.ensure(
                        id,
                        folder?.name.orEmpty(),
                        profile?.mode ?: NotificationMode.NORMAL,
                        profile?.soundUri,
                        profile?.vibration,
                    )
                }

                else -> {
                    val profile = account?.let { app.network(it.network) }
                    channels.ensure(
                        id,
                        account?.displayName ?: context.getString(R.string.channel_messages),
                        account?.notificationMode ?: NotificationMode.NORMAL,
                        profile?.soundUri,
                        profile?.vibration,
                    )
                }
            }
        }

        companion object {
            const val DEFAULT_CHANNEL = NotificationChannels.DEFAULT
            private val PICTURES =
                setOf(
                    org.pingme.core.model.AttachmentKind.IMAGE,
                    org.pingme.core.model.AttachmentKind.GIF,
                    org.pingme.core.model.AttachmentKind.STICKER,
                )
            private const val CHAT = "chat_"
            private const val KEYWORD = "keyword_"
            private const val FOLDER = "folder_"
            private const val ACCOUNT = "account_"

            /** The pure decision, so it can be tested without Android. */
            @Suppress("LongParameterList")
            fun decide(
                chat: Chat,
                message: Message,
                now: Instant,
                keywords: List<KeywordRule> = emptyList(),
                settings: NotificationSettings = NotificationSettings(),
                overrides: ChatOverrides? = null,
                account: Account? = null,
                visible: ChatId? = null,
            ): NotificationDecision {
                if (message.isOutgoing || visible == chat.id) return NotificationDecision.Drop
                val keyword = keywords.firstOrNull { it.matches(message.body, chat) }
                val folder = chat.folder
                return when {
                    keyword != null && keyword.overridesSilence -> {
                        keywordChannel(keyword, settings)
                    }

                    isQuiet(chat, now) -> {
                        NotificationDecision.Drop
                    }

                    keyword != null -> {
                        keywordChannel(keyword, settings)
                    }

                    overrides?.isCustomNotification == true -> {
                        NotificationDecision.Notify("$CHAT${chat.id.value}_${overrides.channelVersion}")
                    }

                    folder != null -> {
                        profileChannel("$FOLDER${folder.name}", settings.folder(folder), NotificationMode.NORMAL)
                    }

                    account == null -> {
                        NotificationDecision.Notify(DEFAULT_CHANNEL)
                    }

                    else -> {
                        accountChannel(account, settings)
                    }
                }
            }

            private fun isQuiet(
                chat: Chat,
                now: Instant,
            ): Boolean {
                val muted = chat.isMuted && chat.muteUntil.let { it == null || it > now }
                return muted || chat.isLowPriority
            }

            // Off drops; Silent shows without a sound; the channel id carries the profile's version.
            private fun profileChannel(
                stem: String,
                profile: org.pingme.core.model.NotificationProfile,
                accountMode: NotificationMode,
            ): NotificationDecision {
                if (profile.mode == NotificationMode.OFF ||
                    accountMode == NotificationMode.OFF
                ) {
                    return NotificationDecision.Drop
                }
                return NotificationDecision.Notify(
                    "${stem}_${profile.channelVersion}",
                    silent = profile.mode == NotificationMode.SILENT || accountMode == NotificationMode.SILENT,
                )
            }

            private fun accountChannel(
                account: Account,
                settings: NotificationSettings,
            ): NotificationDecision {
                val profile = settings.network(account.network)
                val plain =
                    profile ==
                        org.pingme.core.model
                            .NotificationProfile() &&
                        account.notificationMode == NotificationMode.NORMAL
                if (plain) return NotificationDecision.Notify(DEFAULT_CHANNEL)
                return profileChannel("$ACCOUNT${account.id.value}", profile, account.notificationMode)
            }

            private fun keywordChannel(
                rule: KeywordRule,
                settings: NotificationSettings,
            ): NotificationDecision.Notify {
                val profile = settings.keywords[rule.id.value]
                return NotificationDecision.Notify(
                    "$KEYWORD${rule.id.value}_${profile?.channelVersion ?: 0}",
                    keyword = rule.pattern,
                    silent = profile?.mode == NotificationMode.SILENT,
                )
            }
        }
    }

/** Whether [text] in [chat] trips this rule (UI_DESIGN.md 10.9). */
fun KeywordRule.matches(
    text: String?,
    chat: Chat,
): Boolean {
    if (text.isNullOrBlank() || pattern.isBlank()) return false
    val inScope =
        when (val where = scope) {
            KeywordScope.All -> true
            is KeywordScope.Accounts -> chat.accountId in where.ids
            is KeywordScope.Chats -> chat.id in where.ids
        }
    if (!inScope) return false
    val quoted = Regex.escape(pattern)
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return Regex(if (wholeWord) "\\b$quoted\\b" else quoted, options).containsMatchIn(text)
}
