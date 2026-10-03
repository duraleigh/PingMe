// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What another app handed PingMe through Android's share sheet: some text, some files, or both. */
data class SharePayload(
    val text: String,
    val uris: List<Uri>,
)

/**
 * Carries a share from the activity's intent to the picker screen (owner, Gate G3: PingMe in
 * the share menu). The activity hands the intent here; the navigation host opens the picker
 * and takes the request down once the picker has it.
 */
@Singleton
class ShareRequests
    @Inject
    constructor() {
        private val wanted = MutableStateFlow<SharePayload?>(null)

        /** The share waiting for a picker, until one has taken it. */
        val requested: StateFlow<SharePayload?> = wanted.asStateFlow()

        /** The last share taken by a picker, which the picker screen reads. */
        var current: SharePayload? = null
            private set

        /** Reads a share out of an activity's intent, if it is one. */
        fun fromIntent(intent: Intent?) {
            payloadOf(intent)?.let { wanted.value = it }
        }

        /** The picker has the share: nothing else needs to open for it. */
        fun take(): SharePayload? {
            val payload = wanted.value ?: return current
            current = payload
            wanted.value = null
            return payload
        }

        fun finish() {
            current = null
        }

        companion object {
            fun payloadOf(intent: Intent?): SharePayload? {
                val action = intent?.action ?: return null
                val uris =
                    when (action) {
                        Intent.ACTION_SEND -> listOfNotNull(intent.stream())
                        Intent.ACTION_SEND_MULTIPLE -> intent.streams()
                        else -> return null
                    }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (text.isBlank() && uris.isEmpty()) return null
                return SharePayload(text, uris)
            }

            private fun Intent.stream(): Uri? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    getParcelableExtra(Intent.EXTRA_STREAM)
                }

            private fun Intent.streams(): List<Uri> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
        }
    }
