// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PingMe's own small diagnostic file on the phone (owner, 2026-10-05: a chat misfiled
 * into General cannot be caught live, and the phone's log is gone by the time wireless
 * debugging is on). The lines that matter for chasing a fault are written here as well as
 * to the system log: chat removals, folder moves, dropped messages, read markers. Nothing
 * leaves the phone; the file is read over wireless debugging when asked (DESIGN.md 6.5).
 * Kept small: one file of up to half a megabyte, and the one before it.
 */
object Diag {
    /** Set by the app at start; null (in tests) writes nothing. */
    @Volatile
    var dir: File? = null

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_BYTES = 512L * 1024
    private const val CURRENT = "pingme.log"
    private const val PREVIOUS = "pingme.1.log"

    @Synchronized
    fun note(
        tag: String,
        line: String,
    ) {
        val folder = dir ?: return
        try {
            folder.mkdirs()
            val file = File(folder, CURRENT)
            if (file.length() > MAX_BYTES) {
                File(folder, PREVIOUS).delete()
                file.renameTo(File(folder, PREVIOUS))
            }
            File(folder, CURRENT).appendText("${stamp.format(Date())} $tag: $line\n")
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // A full disk or a missing folder must never trouble the app.
        }
    }
}
