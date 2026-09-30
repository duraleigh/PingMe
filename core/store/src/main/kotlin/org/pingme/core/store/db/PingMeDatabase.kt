// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** `pingme.db` (BUILD_PLAN.md P1.2). Schemas are exported to core/store/schemas. */
@Database(
    entities = [
        AccountEntity::class,
        ChatEntity::class,
        ChatParticipantEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ReactionEntity::class,
        PersonEntity::class,
        SpaceEntity::class,
        SpaceChatEntity::class,
        ScheduledSendEntity::class,
        KeywordRuleEntity::class,
        MergeLinkEntity::class,
        MediaSaveJobEntity::class,
    ],
    version = PingMeDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PingMeDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun chatDao(): ChatDao

    abstract fun messageDao(): MessageDao

    abstract fun personDao(): PersonDao

    abstract fun spaceDao(): SpaceDao

    abstract fun scheduledSendDao(): ScheduledSendDao

    abstract fun keywordRuleDao(): KeywordRuleDao

    abstract fun mergeLinkDao(): MergeLinkDao

    abstract fun mediaSaveJobDao(): MediaSaveJobDao

    companion object {
        const val NAME = "pingme.db"

        /** The schema version. Bump it with a migration in [MIGRATIONS] and an exported schema. */
        const val VERSION = 1

        /** Schema migrations, oldest first. Empty until the schema first changes (BUILD_PLAN.md P1.6). */
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()

        /** Applies the settings every PingMe database needs, on-disk or in-memory. */
        fun configure(builder: Builder<PingMeDatabase>): PingMeDatabase =
            builder
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*MIGRATIONS)
                .addCallback(
                    object : Callback() {
                        override fun onOpen(connection: SQLiteConnection) {
                            MessageFts.ensure(connection)
                        }
                    },
                ).build()
    }
}
