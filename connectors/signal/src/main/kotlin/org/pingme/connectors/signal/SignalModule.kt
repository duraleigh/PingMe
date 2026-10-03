// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.signal.bridge.GomobileSigBridge
import org.pingme.connectors.signal.bridge.SigBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds Signal to the registry, over the real Go bridge. */
@Module
@InstallIn(SingletonComponent::class)
interface SignalModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.SIGNAL)
    fun connector(connector: SignalConnector): Connector

    @Binds
    fun bridge(bridge: GomobileSigBridge): SigBridge
}
