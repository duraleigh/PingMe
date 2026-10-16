// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
        PinnedMessageEntity::class,
        ChatOverridesEntity::class,
        ChatTombstoneEntity::class,
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

    abstract fun chatOverridesDao(): ChatOverridesDao

    companion object {
        const val NAME = "pingme.db"

        /** The schema version. Bump it with a migration in [MIGRATIONS] and an exported schema. */
        const val VERSION = 8

        /** Schema migrations, oldest first (BUILD_PLAN.md P1.6). MigrationTest checks every one. */
        val MIGRATIONS: Array<Migration> =
            arrayOf(
                PinnedMessages,
                ChatOverridesTable,
                SpaceIconAndAll,
                ReadUpToAndTombstones,
                ReadBackfill,
                ContactLinks,
                ReadReachesNewest,
            )

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

private const val VERSION_3 = 3

/** 1 to 2 (P2.4): pinned messages, local to the phone (UI_DESIGN.md 5.1). */
private object PinnedMessages : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `pinned_messages` (`messageId` TEXT NOT NULL, `chatId` TEXT NOT NULL, " +
                "`pinnedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`), FOREIGN KEY(`messageId`) REFERENCES " +
                "`messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_pinned_messages_chatId` ON `pinned_messages` (`chatId`)")
    }
}

/** 2 to 3 (P2.5): each chat's own settings from Chat details (UI_DESIGN.md 3.4). */
private object ChatOverridesTable : Migration(2, VERSION_3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_overrides` (`chatId` TEXT NOT NULL, `soundUri` TEXT, " +
                "`vibration` TEXT, `channelVersion` INTEGER NOT NULL, `lookJson` TEXT, `quickReactions` TEXT, " +
                "PRIMARY KEY(`chatId`), FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION " +
                "ON DELETE CASCADE )",
        )
    }
}

/** 3 to 4 (Gate G1): a space's icon, and whether its chats also show in All (UI_DESIGN.md 10.4). */
private object SpaceIconAndAll : Migration(VERSION_3, VERSION_4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `spaces` ADD COLUMN `icon` TEXT NOT NULL DEFAULT 'SPACE'")
        connection.execSQL("ALTER TABLE `spaces` ADD COLUMN `showInAll` INTEGER NOT NULL DEFAULT 1")
    }
}

/** 4 to 5 (Gate G7): when a chat was last read here, and chats deleted here (not undone by a sync). */
private const val VERSION_4 = 4

private const val VERSION_5 = 5

private object ReadUpToAndTombstones : Migration(VERSION_4, VERSION_5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `chats` ADD COLUMN `readUpTo` INTEGER")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_tombstones` (`chatId` TEXT NOT NULL, `hiddenAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chatId`))",
        )
    }
}

/**
 * 5 to 6 (Gate G7, round 2): a chat that showed as read before "read here" existed is
 * stamped as read, so the first sync after the upgrade cannot bring it back unread
 * (owner: Google Messages chats read under 0.7.0 came back unread in 0.7.1).
 */
private const val VERSION_6 = 6

private object ReadBackfill : Migration(VERSION_5, VERSION_6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "UPDATE `chats` SET `readUpTo` = `lastActivityAt` WHERE `unreadCount` = 0 AND `readUpTo` IS NULL",
        )
    }
}

/** 6 to 7 (P7.1): the matched contact's name and photo on a person (UI_DESIGN.md 10.18). */
private const val VERSION_7 = 7

private object ContactLinks : Migration(VERSION_6, VERSION_7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `persons` ADD COLUMN `contactName` TEXT")
        connection.execSQL("ALTER TABLE `persons` ADD COLUMN `contactPhoto` TEXT")
    }
}

/**
 * 7 to 8 (Gate G7, round 3): a chat showing as read is stamped read at its newest stored
 * message, not the network's chat time, which could be older (Google Messages).
 */
private object ReadReachesNewest : Migration(VERSION_7, PingMeDatabase.VERSION) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "UPDATE `chats` SET `readUpTo` = MAX(`readUpTo`, COALESCE((SELECT MAX(m.`sentAt`) FROM `messages` m " +
                "WHERE m.`chatId` = `chats`.`id`), `readUpTo`)) WHERE `unreadCount` = 0 AND `readUpTo` IS NOT NULL",
        )
    }
}
