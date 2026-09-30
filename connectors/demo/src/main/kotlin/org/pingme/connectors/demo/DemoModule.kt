// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DemoProvidesModule {
    @Provides
    @Singleton
    fun controls() = DemoControls()
}

/** Adds the demo network to the registry. The app includes this module in debug builds only. */
@Module
@InstallIn(SingletonComponent::class)
interface DemoBindsModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.DEMO)
    fun connector(connector: DemoConnector): Connector
}
