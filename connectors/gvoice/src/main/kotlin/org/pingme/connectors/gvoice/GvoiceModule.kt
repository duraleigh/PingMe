// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.gvoice.bridge.GomobileGvBridge
import org.pingme.connectors.gvoice.bridge.GvBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds Google Voice to the registry, over the real Go bridge. */
@Module
@InstallIn(SingletonComponent::class)
interface GvoiceModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.GVOICE)
    fun connector(connector: GvoiceConnector): Connector

    @Binds
    fun bridge(bridge: GomobileGvBridge): GvBridge
}
