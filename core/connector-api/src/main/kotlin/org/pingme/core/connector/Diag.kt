// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PingMe's own small diagnostic files on the phone (owner, 2026-10-05: a chat misfiled
 * into General cannot be caught live, and the phone's log is gone by the time wireless
 * debugging is on). The lines that matter for chasing a fault are written here as well as
 * to the system log: chat removals, folder moves, dropped messages, read markers. Nothing
 * leaves the phone; the files are read over wireless debugging when asked (DESIGN.md 6.5).
 * Kept small: each file holds up to half a megabyte, and the one before it.
 *
 * Connections have a diary of their own ([connection]), so a burst of anything else can
 * never push them out (owner, 2026-11-01: one repeating line erased every day but the last
 * two hours, the very days the question was about). The same line twice in a row is written
 * once, with a count when something else finally follows it.
 */
object Diag {
    /** Set by the app at start; null (in tests) writes nothing. */
    @Volatile
    var dir: File? = null

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_BYTES = 512L * 1024
    private const val CURRENT = "pingme.log"
    private const val PREVIOUS = "pingme.1.log"
    private const val CONNECTION = "connection.log"
    private const val CONNECTION_PREVIOUS = "connection.1.log"

    private val general = Diary(CURRENT, PREVIOUS)
    private val connections = Diary(CONNECTION, CONNECTION_PREVIOUS)

    @Synchronized
    fun note(
        tag: String,
        line: String,
    ) = write(general, "$tag: $line")

    /** One event in the life of a connection: connecting, connected, why it ended, and how long each took. */
    @Synchronized
    fun connection(
        tag: String,
        line: String,
    ) = write(connections, "$tag: $line")

    private fun write(
        diary: Diary,
        text: String,
    ) {
        val folder = dir ?: return
        try {
            folder.mkdirs()
            if (text == diary.last) {
                diary.repeats++
                return
            }
            val file = File(folder, diary.current)
            if (file.length() > MAX_BYTES) {
                File(folder, diary.previous).delete()
                file.renameTo(File(folder, diary.previous))
            }
            val now = stamp.format(Date())
            val repeats = if (diary.repeats > 0) "$now (the line above came ${diary.repeats} more time(s))\n" else ""
            File(folder, diary.current).appendText("$repeats$now $text\n")
            diary.last = text
            diary.repeats = 0
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // A full disk or a missing folder must never trouble the app.
        }
    }

    private class Diary(
        val current: String,
        val previous: String,
    ) {
        var last: String? = null
        var repeats = 0
    }
}
