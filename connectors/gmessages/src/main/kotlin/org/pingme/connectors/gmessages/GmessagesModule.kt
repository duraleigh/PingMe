// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.gmessages.bridge.GmBridge
import org.pingme.connectors.gmessages.bridge.GomobileGmBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds Google Messages to the registry, over the real Go bridge. */
@Module
@InstallIn(SingletonComponent::class)
interface GmessagesModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.GMESSAGES)
    fun connector(connector: GmessagesConnector): Connector

    @Binds
    fun bridge(bridge: GomobileGmBridge): GmBridge

    @Binds
    fun checks(checks: AndroidGmessagesChecks): GmessagesChecks
}
