// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Google Messages' package name: the pairing partner, and the "Action needed" deep link. */
const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"

/**
 * What the phone must have before pairing (DESIGN.md 5.4 step 1): Google Messages
 * installed and set as the default SMS app. Whether RCS is on only shows after pairing,
 * in the phone's settings event.
 */
interface GmessagesChecks {
    fun installed(): Boolean

    fun isDefaultSmsApp(): Boolean
}

class AndroidGmessagesChecks
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GmessagesChecks {
        override fun installed(): Boolean =
            try {
                context.packageManager.getPackageInfo(GOOGLE_MESSAGES_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        override fun isDefaultSmsApp(): Boolean = Telephony.Sms.getDefaultSmsPackage(context) == GOOGLE_MESSAGES_PACKAGE
    }
