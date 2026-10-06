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

/** Who a call icon calls: the person's number, and the chat's id on its network. */
data class CallTarget(
    val phone: String?,
    /** The chat's own id on the network, for opening it in Instagram or Messenger. */
    val thread: String? = null,
)

/** One press of a call icon: which network, how that network calls, and whom. */
data class CallRequest(
    val network: NetworkId,
    val method: CallMethod,
    val video: Boolean,
    val target: CallTarget,
)

/** What pressing a call icon did. */
enum class CallOutcome {
    /** The call is being placed. */
    CALLING,

    /** PingMe has no leave to place phone calls yet: ask once, then try again. */
    NEEDS_PERMISSION,

    /** The dialer opened with the number filled in, because phone calls were refused. */
    OPENED_DIALER,

    /** The chat opened in its app, which lets no other app start its calls (Instagram, Messenger). */
    OPENED_CHAT_NO_CALLS,

    /** The chat opened in its app, which has not put its call rows on the contact (WhatsApp, Signal, Telegram). */
    OPENED_CHAT_NEEDS_SYNC,

    /** The app opened, because nothing more direct worked. */
    OPENED_APP,

    /** Nothing on this phone can place it. */
    UNAVAILABLE,
}

/**
 * The phone and video icons in the chat header (UI_DESIGN.md 10.17): each does the most
 * direct thing the service allows. What each app answers was read off the owner's phone on
 * 2026-10-06 (the apps' intent filters and the call rows they add to contacts); everything
 * here falls back to opening the app to the person, then to opening the app.
 */
class Calls(
    private val context: Context,
) {
    /**
     * Places the call. [asked] is true after the phone-call permission was requested, so a
     * refusal opens the dialer with the number instead of asking again.
     */
    fun start(
        network: NetworkId,
        method: CallMethod,
        video: Boolean,
        target: CallTarget,
        asked: Boolean = false,
    ): CallOutcome =
        when (method) {
            CallMethod.DIALER -> dial(target.phone, null, asked)
            CallMethod.APP_DIALER -> dial(target.phone, network.appPackage, asked)
            CallMethod.MEET -> meet(target.phone)
            CallMethod.CONTACT_APP_CALL -> contactCall(network, video, target.phone)
            CallMethod.OPEN_THREAD -> openThread(network, target.thread)
            CallMethod.OPEN_APP -> openApp(network.appPackage)
            CallMethod.NONE -> CallOutcome.UNAVAILABLE
        }

    /**
     * Dials at once with the phone-call permission; in [appPackage] when given (Google Voice
     * answers the call action itself and places the call on its own number). Without the
     * permission it asks once, then settles for the dialer with the number filled in.
     */
    private fun dial(
        phone: String?,
        appPackage: String?,
        asked: Boolean,
    ): CallOutcome =
        when {
            phone == null -> {
                CallOutcome.UNAVAILABLE
            }

            granted(Manifest.permission.CALL_PHONE) -> {
                outcome(Intent(Intent.ACTION_CALL, tel(phone)).setPackage(appPackage), CallOutcome.CALLING)
            }

            !asked -> {
                CallOutcome.NEEDS_PERMISSION
            }

            else -> {
                outcome(Intent(Intent.ACTION_DIAL, tel(phone)).setPackage(appPackage), CallOutcome.OPENED_DIALER)
            }
        }

    /** Google Meet starts a call from its own action with a tel: number; it ignores a plain view. */
    private fun meet(phone: String?): CallOutcome =
        if (phone != null && launch(Intent(MEET_CALL, tel(phone)).setPackage(MEET))) {
            CallOutcome.CALLING
        } else {
            openApp(MEET)
        }

    /**
     * WhatsApp, Signal, and Telegram put a call row on the person's contact; opening it starts
     * the call. Without that row (the app has not been let at the contacts), the app opens to
     * the person instead.
     */
    private fun contactCall(
        network: NetworkId,
        video: Boolean,
        phone: String?,
    ): CallOutcome {
        val mime = callMimeType(network, video)
        val row = if (phone != null && mime != null) findDataRow(mime, phone) else null
        val call =
            row?.let {
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, it), mime)
            }
        return if (call != null && launch(call)) CallOutcome.CALLING else openPerson(network, phone)
    }

    private fun openPerson(
        network: NetworkId,
        phone: String?,
    ): CallOutcome {
        val link = phone?.let { personLink(network, it) }
        return if (link != null && launch(Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(network.appPackage))) {
            CallOutcome.OPENED_CHAT_NEEDS_SYNC
        } else {
            openApp(network.appPackage)
        }
    }

    /** Instagram and Messenger open the chat itself; neither lets another app start a call. */
    private fun openThread(
        network: NetworkId,
        thread: String?,
    ): CallOutcome {
        val link = thread?.let { threadLink(network, it) }
        return if (link != null && launch(Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(network.appPackage))) {
            CallOutcome.OPENED_CHAT_NO_CALLS
        } else {
            openApp(network.appPackage)
        }
    }

    private fun tel(phone: String) = "tel:$phone".toUri()

    private fun outcome(
        intent: Intent,
        started: CallOutcome,
    ) = if (launch(intent)) started else CallOutcome.UNAVAILABLE

    private fun findDataRow(
        mime: String,
        phone: String,
    ): Long? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
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

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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

    internal companion object {
        const val MEET = "com.google.android.apps.tachyon"

        /** The only call action Meet's call screen answers (read off the phone, 2026-10-06). */
        const val MEET_CALL = "com.google.android.apps.tachyon.action.CALL"
        const val SIGNIFICANT_DIGITS = 9

        /** The call rows each app adds to contacts, as the owner's phone lists them (2026-10-06). */
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
                    if (video) {
                        "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.videocall"
                    } else {
                        "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call"
                    }
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

        /** The public link that opens a chat with a number in each app. */
        fun personLink(
            network: NetworkId,
            phone: String,
        ): String? {
            val digits = phone.filter(Char::isDigit)
            if (digits.isEmpty()) return null
            return when (network) {
                NetworkId.WHATSAPP -> "https://wa.me/$digits"
                NetworkId.SIGNAL -> "https://signal.me/#p/+$digits"
                NetworkId.TELEGRAM -> "https://t.me/+$digits"
                else -> null
            }
        }

        /** The public link that opens a chat by its id in each app. */
        fun threadLink(
            network: NetworkId,
            thread: String,
        ): String? =
            when (network) {
                NetworkId.INSTAGRAM -> "https://www.instagram.com/direct/t/$thread/"
                NetworkId.MESSENGER, NetworkId.FBPAGE -> "https://www.messenger.com/t/$thread"
                else -> null
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
