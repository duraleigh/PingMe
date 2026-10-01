// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.VibrationPattern
import org.pingme.core.store.db.ChatOverridesEntity
import org.pingme.core.store.db.PingMeDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Each chat's own settings from Chat details (UI_DESIGN.md 3.4). A chat with none follows the app. */
@Singleton
class ChatOverridesRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.chatOverridesDao()

        fun overrides(chatId: ChatId): Flow<ChatOverrides> =
            dao.observe(chatId.value).map {
                it?.toModel()
                    ?: ChatOverrides(chatId)
            }

        suspend fun get(chatId: ChatId): ChatOverrides = dao.get(chatId.value)?.toModel() ?: ChatOverrides(chatId)

        suspend fun update(
            chatId: ChatId,
            change: (ChatOverrides) -> ChatOverrides,
        ) = dao.upsert(change(get(chatId)).toEntity())

        /** A new sound, or vibration, means a new notification channel for this chat (UI_DESIGN.md 6.1). */
        suspend fun setNotification(
            chatId: ChatId,
            soundUri: String?,
            vibration: VibrationPattern?,
        ) = update(
            chatId,
        ) { it.copy(soundUri = soundUri, vibration = vibration, channelVersion = it.channelVersion + 1) }

        private fun ChatOverridesEntity.toModel() =
            ChatOverrides(
                chatId = ChatId(chatId),
                soundUri = soundUri,
                vibration = vibration?.let { name -> VibrationPattern.entries.firstOrNull { it.name == name } },
                channelVersion = channelVersion,
                lookJson = lookJson,
                quickReactions = quickReactions?.split(SEPARATOR)?.filter { it.isNotEmpty() },
            )

        private fun ChatOverrides.toEntity() =
            ChatOverridesEntity(
                chatId = chatId.value,
                soundUri = soundUri,
                vibration = vibration?.name,
                channelVersion = channelVersion,
                lookJson = lookJson,
                quickReactions = quickReactions?.joinToString(SEPARATOR),
            )

        private companion object {
            const val SEPARATOR = "\n"
        }
    }
