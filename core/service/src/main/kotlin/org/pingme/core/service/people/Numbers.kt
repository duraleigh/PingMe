// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.people

/** Phone numbers as keys: the digits, and the last ten for a number written without its country. */
object Numbers {
    private const val NATIONAL_DIGITS = 10
    private const val MIN_DIGITS = 7

    /** The digits of [raw], or null when it is not a phone number. */
    fun digits(raw: String?): String? = raw?.filter(Char::isDigit)?.takeIf { it.length >= MIN_DIGITS }

    /** The last ten digits, when there are at least that many. */
    fun national(digits: String): String? = digits.takeIf { it.length >= NATIONAL_DIGITS }?.takeLast(NATIONAL_DIGITS)
}
