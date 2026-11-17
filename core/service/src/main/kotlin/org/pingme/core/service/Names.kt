// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

/** Whether a title is a person's name or just the address PingMe fell back to. */
object Names {
    private const val MIN_NUMBER_DIGITS = 5
    private val numberChars = setOf('+', ' ', '(', ')', '-', '.')

    /** A bare phone number, a raw network id, or nothing at all. */
    fun isBare(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || '@' in trimmed) return true
        val digits = trimmed.count { it.isDigit() }
        return digits >= MIN_NUMBER_DIGITS && trimmed.all { it.isDigit() || it in numberChars }
    }

    /** A chat's address as a title of last resort: "+digits" for a phone address, else the raw id. */
    fun address(remoteId: String): String {
        val user = remoteId.substringBefore('@')
        return if (remoteId.endsWith(PHONE_SUFFIX) && user.all(Char::isDigit)) "+$user" else remoteId
    }

    private const val PHONE_SUFFIX = "@s.whatsapp.net"

    /** A name worth titling a chat with: not an address, and not "You". */
    fun isReal(name: String): Boolean = !isBare(name) && name.trim() != "You"
}
