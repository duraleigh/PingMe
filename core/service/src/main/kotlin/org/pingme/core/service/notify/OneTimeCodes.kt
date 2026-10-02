// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import org.pingme.core.service.R

/**
 * Finds a one-time code in a message (UI_DESIGN.md 10.6, BUILD_PLAN.md P4.1): four to eight
 * digits near a word like "code", "OTP", or "verification", or Google's "G-123456". Scanned
 * on the phone only.
 */
object OneTimeCodes {
    private val GOOGLE = Regex("""\bG-(\d{6})\b""")
    private val CONTEXT =
        Regex(
            """(?i)\b(code|codes|otp|verification|verify|verifying|passcode|pin|one[- ]time|2fa|two[- ]factor|""" +
                """login|log in|sign[- ]in|confirm|confirmation|authenticat\w*|token)\b""",
        )

    // Not part of a longer number, a decimal, a price, or a phone number; a full stop after it is fine.
    private val CODE = Regex("""(?<![\d$€£-])(?<!\d\.)(\d{4,8})(?![\d%-])(?!\.\d)""")

    fun find(text: String?): String? =
        if (text.isNullOrBlank()) null else GOOGLE.find(text)?.groupValues?.get(1) ?: nearATellingWord(text)

    private fun nearATellingWord(text: String): String? =
        if (!CONTEXT.containsMatchIn(text)) {
            null
        } else {
            CODE.findAll(text).map { it.groupValues[1] }.firstOrNull { !it.looksLikeAYear() }
        }

    // "2026" alone in "expires in 2026" is a year, not a code; a code with a context word nearby still wins.
    private fun String.looksLikeAYear() = length == YEAR_DIGITS && (startsWith("19") || startsWith("20"))

    private const val YEAR_DIGITS = 4

    /** Puts [code] on the clipboard, marked sensitive so previews hide it, and says so. */
    fun copy(
        context: Context,
        code: String,
    ) {
        val clip =
            ClipData.newPlainText(context.getString(R.string.notification_code_label), code).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras =
                        PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
            }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        val said = context.getString(R.string.notification_code_copied, code)
        Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
    }
}
