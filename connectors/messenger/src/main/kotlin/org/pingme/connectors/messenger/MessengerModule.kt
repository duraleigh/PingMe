// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.messenger.bridge.FbBridge
import org.pingme.connectors.messenger.bridge.GomobileFbBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds Messenger to the registry, over the real Go bridge. */
@Module
@InstallIn(SingletonComponent::class)
interface MessengerModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.MESSENGER)
    fun connector(connector: MessengerConnector): Connector

    @Binds
    fun bridge(bridge: GomobileFbBridge): FbBridge
}
