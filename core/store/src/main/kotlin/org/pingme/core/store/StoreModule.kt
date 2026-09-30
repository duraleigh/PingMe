// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.pingme.core.store.db.PingMeDatabase
import javax.inject.Singleton
import kotlin.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object StoreModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): PingMeDatabase =
        PingMeDatabase.configure(Room.databaseBuilder(context, PingMeDatabase::class.java, PingMeDatabase.NAME))

    @Provides
    @Singleton
    fun settingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    fun clock(): Clock = Clock.System
}
