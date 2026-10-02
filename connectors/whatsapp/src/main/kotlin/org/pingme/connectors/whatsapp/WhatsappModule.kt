// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.whatsapp.bridge.GomobileWaBridge
import org.pingme.connectors.whatsapp.bridge.WaBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds WhatsApp to the registry, over the real Go bridge. */
@Module
@InstallIn(SingletonComponent::class)
interface WhatsappModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.WHATSAPP)
    fun connector(connector: WhatsappConnector): Connector

    @Binds
    fun bridge(bridge: GomobileWaBridge): WaBridge
}
