// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.pingme.core.model.CallMethod
import org.pingme.core.model.NetworkId

/** What pressing a call icon did. */
enum class CallOutcome {
    /** The call is being placed. */
    CALLING,

    /** The app opened to that person, because it has no way to start the call directly. */
    OPENED_APP,

    /** Nothing on this phone can place it. */
    UNAVAILABLE,
}

/**
 * The phone and video icons in the chat header (UI_DESIGN.md 10.17): each does the most
 * direct thing the service allows. Which intents each app answers is confirmed on a real
 * phone at the gates; everything here falls back to opening the app.
 */
class Calls(
    private val context: Context,
) {
    fun start(
        network: NetworkId,
        method: CallMethod,
        video: Boolean,
        phone: String?,
    ): CallOutcome =
        when (method) {
            CallMethod.DIALER -> phone?.let(::dial) ?: CallOutcome.UNAVAILABLE
            CallMethod.MEET -> phone?.let(::meet) ?: openApp(MEET)
            CallMethod.CONTACT_APP_CALL -> phone?.let { contactCall(network, video, it) } ?: openApp(network.appPackage)
            CallMethod.OPEN_APP, CallMethod.OPEN_THREAD -> openApp(network.appPackage)
            CallMethod.NONE -> CallOutcome.UNAVAILABLE
        }

    /** Dials at once with the phone-call permission, or opens the dialer with the number filled in. */
    private fun dial(phone: String): CallOutcome {
        val allowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        val action = if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (launch(Intent(action, "tel:$phone".toUri()))) CallOutcome.CALLING else CallOutcome.UNAVAILABLE
    }

    private fun meet(phone: String): CallOutcome =
        if (launch(
                Intent(Intent.ACTION_VIEW, "tel:$phone".toUri()).setPackage(MEET),
            )
        ) {
            CallOutcome.CALLING
        } else {
            openApp(MEET)
        }

    /**
     * WhatsApp, Signal, and Telegram put a call row on the person's contact; opening it starts
     * the call. Without contacts access or that row, the app opens instead.
     */
    private fun contactCall(
        network: NetworkId,
        video: Boolean,
        phone: String,
    ): CallOutcome {
        val mime = callMimeType(network, video) ?: return openApp(network.appPackage)
        val row = findDataRow(mime, phone) ?: return openApp(network.appPackage)
        val intent =
            Intent(
                Intent.ACTION_VIEW,
            ).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row), mime)
        return if (launch(intent)) CallOutcome.CALLING else openApp(network.appPackage)
    }

    private fun findDataRow(
        mime: String,
        phone: String,
    ): Long? {
        val allowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        if (!allowed) return null
        val digits = phone.filter(Char::isDigit).takeLast(SIGNIFICANT_DIGITS)
        context.contentResolver
            .query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(mime),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val data = cursor.getString(1).orEmpty().filter(Char::isDigit)
                    if (digits.isNotEmpty() && data.endsWith(digits)) return cursor.getLong(0)
                }
            }
        return null
    }

    private fun openApp(appPackage: String?): CallOutcome {
        val intent =
            appPackage?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return CallOutcome.UNAVAILABLE
        return if (launch(intent)) CallOutcome.OPENED_APP else CallOutcome.UNAVAILABLE
    }

    private fun launch(intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private companion object {
        const val MEET = "com.google.android.apps.tachyon"
        const val SIGNIFICANT_DIGITS = 9

        /** The call rows each app adds to contacts. Checked on a real phone at the gates (10.17). */
        fun callMimeType(
            network: NetworkId,
            video: Boolean,
        ): String? =
            when (network) {
                NetworkId.WHATSAPP -> {
                    if (video) {
                        "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
                    } else {
                        "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
                    }
                }

                NetworkId.SIGNAL -> {
                    "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call"
                }

                NetworkId.TELEGRAM -> {
                    if (video) {
                        "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call.video"
                    } else {
                        "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"
                    }
                }

                else -> {
                    null
                }
            }
    }
}

/** The official app for each network, for "open the app to that person". */
val NetworkId.appPackage: String?
    get() =
        when (this) {
            NetworkId.WHATSAPP -> "com.whatsapp"
            NetworkId.SIGNAL -> "org.thoughtcrime.securesms"
            NetworkId.TELEGRAM -> "org.telegram.messenger"
            NetworkId.GVOICE -> "com.google.android.apps.googlevoice"
            NetworkId.MESSENGER, NetworkId.FBPAGE -> "com.facebook.orca"
            NetworkId.INSTAGRAM -> "com.instagram.android"
            NetworkId.GMESSAGES -> "com.google.android.apps.messaging"
            NetworkId.SMS, NetworkId.DEMO -> null
        }
