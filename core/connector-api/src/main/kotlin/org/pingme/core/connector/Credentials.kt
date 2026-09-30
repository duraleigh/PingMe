// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

/** Thrown for an operation the network cannot do. [reason] is shown to the user as is. */
class UnsupportedCapabilityException(
    val reason: String,
) : UnsupportedOperationException(reason)

/**
 * A login's secret material as the connector saved it, found by [ref]. Stored encrypted
 * with Keystore-held keys by the app's [CredentialStore] (DESIGN.md 6.5).
 */
class Credentials(
    val ref: String,
    val secret: ByteArray,
)

/** Encrypted storage for connector credentials. The app provides the implementation. */
interface CredentialStore {
    suspend fun save(
        ref: String,
        secret: ByteArray,
    )

    suspend fun load(ref: String): ByteArray?

    suspend fun delete(ref: String)
}
