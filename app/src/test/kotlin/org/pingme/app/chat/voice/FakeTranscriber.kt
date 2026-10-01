// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Context
import java.io.File

/** Hears [words] in every note instead of asking Android's recogniser, which tests do not have. */
class FakeTranscriber(
    context: Context,
) : VoiceTranscriber(context) {
    var words: String? = "See you at seven"
    var heard = 0

    override val available: Boolean get() = true

    override suspend fun recognise(file: File): String? {
        heard++
        return words
    }
}
