// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.pingme.core.connector.Connector
import org.pingme.core.connector.NetworkKey
import org.pingme.core.model.NetworkId

/** Adds the Facebook Page inbox to the registry, over Meta's Graph API. */
@Module
@InstallIn(SingletonComponent::class)
interface FbPageModule {
    @Binds
    @IntoMap
    @NetworkKey(NetworkId.FBPAGE)
    fun connector(connector: FbPageConnector): Connector

    @Binds
    fun api(api: HttpPageApi): PageApi
}
