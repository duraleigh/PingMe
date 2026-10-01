// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Context
import java.io.File

/** A microphone for tests: "records" [bytes] of silence, so no audio hardware is needed. */
class FakeRecorder(
    context: Context,
    private val folder: File,
    var bytes: Int = 2048,
) : VoiceRecorder(context) {
    var started = 0
    var cancelled = 0
    var paused = 0
    var compact: Boolean? = null
    private var since: Long? = null
    private var recorded = 0L

    override fun start(
        now: Long,
        compact: Boolean,
    ): Boolean {
        started++
        since = now
        recorded = 0
        this.compact = compact
        return true
    }

    override fun level() = if (since == null) 0f else 0.5f

    override fun pause(now: Long): File? {
        paused++
        since?.let { recorded += now - it }
        since = null
        return File.createTempFile("so-far", ".m4a", folder).apply { writeBytes(ByteArray(bytes)) }
    }

    override fun resume(now: Long): Boolean {
        since = now
        return true
    }

    override fun finish(now: Long): Recording? {
        val length = recorded + (since?.let { now - it } ?: 0)
        since = null
        if (length < SHORTEST_MS) return null
        val file = File.createTempFile("voice", ".m4a", folder).apply { writeBytes(ByteArray(bytes)) }
        return Recording(file, length)
    }

    override fun cancel() {
        cancelled++
        since = null
    }
}
