// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import org.pingme.core.connector.CredentialStore
import java.util.concurrent.ConcurrentHashMap

class MemoryCredentialStore : CredentialStore {
    private val secrets = ConcurrentHashMap<String, ByteArray>()

    override suspend fun save(
        ref: String,
        secret: ByteArray,
    ) {
        secrets[ref] = secret
    }

    override suspend fun load(ref: String) = secrets[ref]

    override suspend fun delete(ref: String) {
        secrets.remove(ref)
    }
}
