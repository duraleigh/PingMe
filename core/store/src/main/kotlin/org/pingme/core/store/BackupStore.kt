// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import android.content.Context
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.core.store.db.PingMeDatabase
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Why a restore was refused. */
enum class RestoreProblem { NOT_A_BACKUP, NEWER_VERSION }

/**
 * Backup and restore of the local database (DESIGN.md milestone 4, BUILD_PLAN.md P2.6).
 * A backup is the database file itself. Logins stay in the keystore and are not in it,
 * and media files are not either; restored media downloads again when shown.
 */
@Singleton
class BackupStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val db: PingMeDatabase,
    ) {
        private val file: File get() = context.getDatabasePath(PingMeDatabase.NAME)

        /** Writes a consistent copy of the whole database to [out]. */
        suspend fun export(out: OutputStream) {
            val copy = File(context.cacheDir, "backup.db").apply { delete() }
            try {
                db.useWriterConnection { connection ->
                    connection.usePrepared("VACUUM INTO ?") { statement ->
                        statement.bindText(1, copy.path)
                        statement.step()
                    }
                }
                withContext(Dispatchers.IO) { copy.inputStream().use { it.copyTo(out) } }
            } finally {
                copy.delete()
            }
        }

        /**
         * Replaces the database with the backup in [input], or says why not. On success the
         * database is closed, so the app must restart before using the store again.
         */
        suspend fun restore(input: InputStream): RestoreProblem? =
            withContext(Dispatchers.IO) {
                val incoming = File(context.cacheDir, "restore.db")
                try {
                    incoming.outputStream().use { input.copyTo(it) }
                    check(incoming)?.let { return@withContext it }
                    db.close()
                    listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
                    incoming.copyTo(file, overwrite = true)
                    null
                } finally {
                    incoming.delete()
                }
            }

        // A PingMe database no newer than this app; media paths that do not exist here are cleared.
        private fun check(candidate: File): RestoreProblem? {
            val header = ByteArray(SQLITE_HEADER.size)
            if (candidate.length() < header.size) return RestoreProblem.NOT_A_BACKUP
            DataInputStream(candidate.inputStream()).use { it.readFully(header) }
            if (!header.contentEquals(SQLITE_HEADER)) return RestoreProblem.NOT_A_BACKUP
            return try {
                BundledSQLiteDriver().open(candidate.path).use { backup ->
                    when {
                        backup.version() > PingMeDatabase.VERSION -> RestoreProblem.NEWER_VERSION
                        !backup.hasTable("messages") -> RestoreProblem.NOT_A_BACKUP
                        else -> null.also { backup.forgetMissingMedia() }
                    }
                }
            } catch (_: SQLiteException) {
                RestoreProblem.NOT_A_BACKUP
            }
        }

        private fun SQLiteConnection.version() =
            prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }

        private fun SQLiteConnection.hasTable(name: String) =
            prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
                it.bindText(1, name)
                it.step()
            }

        private fun SQLiteConnection.forgetMissingMedia() {
            val missing =
                prepare("SELECT id, localPath FROM attachments WHERE localPath IS NOT NULL").use { rows ->
                    buildList {
                        while (rows.step()) if (!File(rows.getText(1)).exists()) add(rows.getText(0))
                    }
                }
            missing.forEach { id ->
                prepare("UPDATE attachments SET localPath = NULL WHERE id = ?").use {
                    it.bindText(1, id)
                    it.step()
                }
            }
        }

        private companion object {
            val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        }
    }
