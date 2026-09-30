// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class NetworkId {
    GMESSAGES,
    SMS,
    WHATSAPP,
    TELEGRAM,
    SIGNAL,
    GVOICE,
    INSTAGRAM,
    MESSENGER,
    FBPAGE,
    DEMO,
}

/**
 * One connected login on one network. A network can have several accounts, and everything
 * downstream (chats, unread counts, filters) is keyed by account (DESIGN.md 6.3, UI_DESIGN.md 6.5).
 */
@Serializable
data class Account(
    val id: AccountId,
    val network: NetworkId,
    val displayName: String,
    val colorArgb: Int,
    val state: ConnectionState,
    val showInInbox: Boolean,
    val notificationMode: NotificationMode,
    /** Points at the encrypted credentials; never the credentials themselves (DESIGN.md 6.5). */
    val credentialRef: String,
)

/** The states every connector reports and the UI shows honestly (DESIGN.md 5.3). */
@Serializable
sealed interface ConnectionState {
    @Serializable
    data object Connected : ConnectionState

    /** Automatic retry; no action needed from the user. */
    @Serializable
    data class Reconnecting(
        val attempt: Int,
        val nextAt: Instant,
    ) : ConnectionState

    /** The user must re-pair or re-login. [deepLink] opens the right app screen where Android allows it. */
    @Serializable
    data class ActionNeeded(
        val reason: String,
        val deepLink: String?,
    ) : ConnectionState

    @Serializable
    data object Disabled : ConnectionState

    /** Polled networks (Facebook Page inbox) show "checked 20 s ago" instead of a live state. */
    @Serializable
    data class Polled(
        val lastCheckedAt: Instant,
    ) : ConnectionState
}

@Serializable
enum class NotificationMode {
    NORMAL,
    SILENT,
    OFF,
}
