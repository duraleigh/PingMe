// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.model.ChatId
import org.pingme.core.store.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The faces in Android's own share pop-up: whoever you last sent to (owner, 2026-11-01). Each send
 * publishes that chat as a share target; a tap opens PingMe's picker with that chat already picked.
 */
@Singleton
open class ShareShortcuts
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val chats: ChatRepository,
    ) {
        open suspend fun sentTo(chatId: ChatId) {
            val chat = chats.get(chatId) ?: return
            val target = chat.mergedInto?.let { chats.get(it) } ?: chat
            val title = target.nameOverride ?: target.title
            if (title.isBlank()) return
            val launch =
                (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(NotificationTaps.EXTRA_CHAT, target.id.value)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val shortcut =
                ShortcutInfoCompat
                    .Builder(context, target.id.value)
                    .setShortLabel(title)
                    .setLongLived(true)
                    .setIsConversation()
                    .setCategories(setOf(CATEGORY))
                    .setPerson(
                        Person
                            .Builder()
                            .setName(title)
                            .setKey(target.id.value)
                            .build(),
                    ).setIntent(launch)
                    .build()
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        }

        companion object {
            /** Named in res/xml/shortcuts.xml. */
            const val CATEGORY = "org.pingme.category.SHARE"
        }
    }
