// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.content.Context
import android.text.format.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Whether the phone is set to the 24-hour clock, which the language alone does not say: times read
 * 8:14 PM here while Google Messages read 20:14 on the same phone (owner, 2026-11-02).
 */
object PhoneClock {
    @Volatile
    private var uses24Hours = false

    /** Read when the app comes to the front, so a change in the phone's settings follows. */
    fun refresh(context: Context) {
        uses24Hours = DateFormat.is24HourFormat(context)
    }

    fun formatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        if (uses24Hours) {
            DateTimeFormatter.ofPattern("HH:mm", locale)
        } else {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        }
}
