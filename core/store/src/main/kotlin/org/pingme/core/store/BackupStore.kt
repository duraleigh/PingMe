// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Why a restore was refused. */
enum class RestoreProblem { NOT_A_BACKUP, NEWER_VERSION, WRONG_PASSPHRASE }

/**
 * Backup and restore (BUILD_PLAN.md Phase 8, P8.2; DESIGN.md milestone 4). A backup is a
 * file locked with the owner's passphrase ([BackupCrypto]) holding the database, the
 * settings file, and the view-once pictures PingMe kept, which no network can hand back
 * (UI_DESIGN.md 10.16). Logins stay in the keystore and are not in it; other media
 * downloads again when shown. The plain database file an older PingMe saved still
 * restores.
 */
@Singleton
class BackupStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val db: PingMeDatabase,
    ) {
        private val file: File get() = context.getDatabasePath(PingMeDatabase.NAME)
        private val settingsFile: File get() = context.preferencesDataStoreFile(SETTINGS)

        /** Writes the backup to [out], sealed with [passphrase]. */
        suspend fun export(
            out: OutputStream,
            passphrase: CharArray,
        ) {
            val copy = File(context.cacheDir, "backup.db").apply { delete() }
            try {
                db.useWriterConnection { connection ->
                    connection.usePrepared("VACUUM INTO ?") { statement ->
                        statement.bindText(1, copy.path)
                        statement.step()
                    }
                }
                withContext(Dispatchers.IO) {
                    ZipOutputStream(BackupCrypto.seal(out, passphrase)).use { zip ->
                        zip.put(DATABASE, copy)
                        settingsFile.takeIf { it.exists() }?.let { zip.put(SETTINGS_ENTRY, it) }
                        val kept = keptMedia(copy)
                        kept.forEachIndexed { i, (_, path) -> zip.put("$MEDIA_DIR$i", File(path)) }
                        zip.putNextEntry(ZipEntry(MANIFEST))
                        val manifest = kept.mapIndexed { i, (id, _) -> "$id\t$MEDIA_DIR$i" }
                        zip.write(manifest.joinToString("\n").toByteArray())
                        zip.closeEntry()
                    }
                }
            } finally {
                copy.delete()
            }
        }

        /**
         * Replaces the database, the settings, and the kept pictures with the backup in
         * [input], or says why not. On success the database is closed, so the app must
         * restart before using the store again.
         */
        suspend fun restore(
            input: InputStream,
            passphrase: CharArray,
        ): RestoreProblem? =
            withContext(Dispatchers.IO) {
                val head = PushbackInputStream(input, BackupCrypto.MAGIC.size)
                val magic = ByteArray(BackupCrypto.MAGIC.size)
                val read = head.read(magic)
                if (read > 0) head.unread(magic, 0, read)
                if (magic.contentEquals(BackupCrypto.MAGIC)) {
                    head.skip(magic.size.toLong())
                    restoreSealed(head, passphrase)
                } else {
                    restorePlain(head)
                }
            }

        private fun restoreSealed(
            input: InputStream,
            passphrase: CharArray,
        ): RestoreProblem? {
            val zipFile = File(context.cacheDir, "restore.zip")
            val incoming = File(context.cacheDir, "restore.db")
            try {
                return unseal(input, passphrase, zipFile) ?: unpack(zipFile, incoming)
            } finally {
                zipFile.delete()
                incoming.delete()
            }
        }

        /** Unlocks the file into [zipFile]; the problem when the passphrase or the file is wrong. */
        private fun unseal(
            input: InputStream,
            passphrase: CharArray,
            zipFile: File,
        ): RestoreProblem? =
            try {
                zipFile.outputStream().use { BackupCrypto.open(input, passphrase).copyTo(it) }
                null
            } catch (_: WrongPassphraseException) {
                RestoreProblem.WRONG_PASSPHRASE
            } catch (_: IOException) {
                RestoreProblem.NOT_A_BACKUP
            }

        /** Takes the database, the kept pictures, and the settings out of the unlocked [zipFile]. */
        private fun unpack(
            zipFile: File,
            incoming: File,
        ): RestoreProblem? {
            val zip =
                try {
                    ZipFile(zipFile)
                } catch (_: ZipException) {
                    return RestoreProblem.NOT_A_BACKUP
                }
            val problem =
                zip.use {
                    val database = zip.getEntry(DATABASE) ?: return@use RestoreProblem.NOT_A_BACKUP
                    incoming.outputStream().use { zip.getInputStream(database).copyTo(it) }
                    val restoredMedia = unpackMedia(zip)
                    check(incoming, restoredMedia) ?: zip.getEntry(SETTINGS_ENTRY)?.let { swapSettings(zip, it) }
                }
            if (problem == null) replaceDatabase(incoming)
            return problem
        }

        /** The settings file is written beside the live one and renamed over it; null means done. */
        private fun swapSettings(
            zip: ZipFile,
            entry: ZipEntry,
        ): RestoreProblem? {
            val fresh = File(settingsFile.path + ".restored")
            fresh.outputStream().use { zip.getInputStream(entry).copyTo(it) }
            settingsFile.parentFile?.mkdirs()
            fresh.renameTo(settingsFile)
            return null
        }

        private fun restorePlain(input: InputStream): RestoreProblem? {
            val incoming = File(context.cacheDir, "restore.db")
            try {
                incoming.outputStream().use { input.copyTo(it) }
                check(incoming, emptyMap())?.let { return it }
                replaceDatabase(incoming)
                return null
            } finally {
                incoming.delete()
            }
        }

        private fun replaceDatabase(incoming: File) {
            db.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
            incoming.copyTo(file, overwrite = true)
        }

        /** The kept pictures land in app storage; the manifest says which attachment each one is. */
        private fun unpackMedia(zip: ZipFile): Map<String, String> {
            val manifest = zip.getEntry(MANIFEST) ?: return emptyMap()
            val dir = File(context.filesDir, RESTORED_MEDIA).apply { mkdirs() }
            val lines =
                zip
                    .getInputStream(manifest)
                    .bufferedReader()
                    .readLines()
                    .filter { it.isNotBlank() }
            return lines
                .mapNotNull { line ->
                    val (id, entryName) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                    val entry = zip.getEntry(entryName) ?: return@mapNotNull null
                    val target = File(dir, entryName.substringAfterLast('/'))
                    target.outputStream().use { zip.getInputStream(entry).copyTo(it) }
                    id to target.path
                }.toMap()
        }

        // A PingMe database no newer than this app; restored pictures are pointed at where they
        // landed, and media paths that do not exist here are cleared.
        private fun check(
            candidate: File,
            restoredMedia: Map<String, String>,
        ): RestoreProblem? {
            val header = ByteArray(SQLITE_HEADER.size)
            if (candidate.length() < header.size) return RestoreProblem.NOT_A_BACKUP
            DataInputStream(candidate.inputStream()).use { it.readFully(header) }
            if (!header.contentEquals(SQLITE_HEADER)) return RestoreProblem.NOT_A_BACKUP
            return try {
                BundledSQLiteDriver().open(candidate.path).use { backup ->
                    when {
                        backup.version() > PingMeDatabase.VERSION -> {
                            RestoreProblem.NEWER_VERSION
                        }

                        !backup.hasTable("messages") -> {
                            RestoreProblem.NOT_A_BACKUP
                        }

                        else -> {
                            restoredMedia.forEach { (id, path) -> backup.setLocalPath(id, path) }
                            backup.forgetMissingMedia()
                            null
                        }
                    }
                }
            } catch (_: SQLiteException) {
                RestoreProblem.NOT_A_BACKUP
            }
        }

        /** The view-once pictures and videos PingMe kept: id and file, from the copied database. */
        private fun keptMedia(copy: File): List<Pair<String, String>> =
            BundledSQLiteDriver().open(copy.path).use { backup ->
                backup
                    .prepare(
                        KEPT_MEDIA,
                    ).use {
                        buildList {
                            while (it.step()) {
                                val path = it.getText(1)
                                if (File(path).exists()) add(it.getText(0) to path)
                            }
                        }
                    }
            }

        private fun ZipOutputStream.put(
            name: String,
            source: File,
        ) {
            putNextEntry(ZipEntry(name))
            source.inputStream().use { it.copyTo(this) }
            closeEntry()
        }

        private fun SQLiteConnection.version() =
            prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }

        private fun SQLiteConnection.hasTable(name: String) =
            prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
                it.bindText(1, name)
                it.step()
            }

        private fun SQLiteConnection.setLocalPath(
            id: String,
            path: String,
        ) {
            prepare("UPDATE attachments SET localPath = ? WHERE id = ?").use {
                it.bindText(1, path)
                it.bindText(2, id)
                it.step()
            }
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
            const val SETTINGS = "settings"
            const val DATABASE = "database.db"
            const val SETTINGS_ENTRY = "settings.preferences_pb"
            const val MANIFEST = "media.tsv"
            const val MEDIA_DIR = "media/"
            const val RESTORED_MEDIA = "media/restored"
            const val KEPT_MEDIA =
                "SELECT id, localPath FROM attachments WHERE isEphemeral = 1 AND localPath IS NOT NULL"
        }
    }
