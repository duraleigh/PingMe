// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.MessageStatus
import kotlin.time.Instant

/** Instants are stored as epoch milliseconds; sealed types as their JSON form. */
object Converters {
    private val json = Json

    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun connectionStateToJson(value: ConnectionState): String = json.encodeToString(ConnectionState.serializer(), value)

    @TypeConverter
    fun jsonToConnectionState(value: String): ConnectionState =
        json.decodeFromString(ConnectionState.serializer(), value)

    @TypeConverter
    fun messageStatusToJson(value: MessageStatus): String = json.encodeToString(MessageStatus.serializer(), value)

    @TypeConverter
    fun jsonToMessageStatus(value: String): MessageStatus = json.decodeFromString(MessageStatus.serializer(), value)

    @TypeConverter
    fun avatarSourceToJson(value: AvatarSource): String = json.encodeToString(AvatarSource.serializer(), value)

    @TypeConverter
    fun jsonToAvatarSource(value: String): AvatarSource = json.decodeFromString(AvatarSource.serializer(), value)

    @TypeConverter
    fun keywordScopeToJson(value: KeywordScope): String = json.encodeToString(KeywordScope.serializer(), value)

    @TypeConverter
    fun jsonToKeywordScope(value: String): KeywordScope = json.decodeFromString(KeywordScope.serializer(), value)
}
