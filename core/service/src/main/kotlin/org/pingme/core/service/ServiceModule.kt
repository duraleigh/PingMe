// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.pingme.core.connector.CredentialStore
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that lives as long as the app process. Connections and timers run in it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ServiceProvidesModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun retryDelays(): RetryDelays = Backoff
}

@Module
@InstallIn(SingletonComponent::class)
interface ServiceBindsModule {
    @Binds
    fun credentialStore(store: KeystoreCredentialStore): CredentialStore
}
