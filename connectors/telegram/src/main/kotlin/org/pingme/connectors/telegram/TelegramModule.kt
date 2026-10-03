// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.connectors.telegram.td.TdlibBridge
import org.pingme.connectors.telegram.td.TelegramBridge
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds Telegram to the registry, over TDLib. */
@Module
@InstallIn(SingletonComponent::class)
interface TelegramModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.TELEGRAM)
    fun connector(connector: TelegramConnector): Connector

    @Binds
    fun bridge(bridge: TdlibBridge): TelegramBridge
}
