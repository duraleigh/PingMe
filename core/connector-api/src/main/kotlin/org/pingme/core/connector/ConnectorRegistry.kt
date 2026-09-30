// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import org.pingme.core.model.NetworkId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every connector the build includes, by network (BUILD_PLAN.md P1.3). Each connector
 * module adds itself with `@Binds @IntoMap @NetworkKey(NetworkId.X)`.
 */
@Singleton
class ConnectorRegistry
    @Inject
    constructor(
        private val connectors: Map<NetworkId, @JvmSuppressWildcards Connector>,
    ) {
        val networks: Set<NetworkId> get() = connectors.keys

        operator fun get(network: NetworkId): Connector? = connectors[network]
    }

@MapKey
annotation class NetworkKey(
    val value: NetworkId,
)

@Module
@InstallIn(SingletonComponent::class)
interface ConnectorRegistryModule {
    /** Declares the map, so the registry works (empty) before any connector is added. */
    @Multibinds
    fun connectors(): Map<NetworkId, Connector>
}
