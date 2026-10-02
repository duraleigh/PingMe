// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.instagram.bridge.GomobileIgBridge
import org.pingme.connectors.instagram.bridge.IgBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds Instagram to the registry, over the real Go bridge. */
@Module
@InstallIn(SingletonComponent::class)
interface InstagramModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.INSTAGRAM)
    fun connector(connector: InstagramConnector): Connector

    @Binds
    fun bridge(bridge: GomobileIgBridge): IgBridge
}
