// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.pingme.core.service.notify.ChatPresence
import javax.inject.Inject

@HiltAndroidApp
class PingMeApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var presence: ChatPresence

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // PingMe's own diagnostic file, read over wireless debugging when a fault needs chasing.
        // It sits in the app's own folder under Android/data, which debugging can read and
        // other apps cannot; private storage would need a debug build to read.
        org.pingme.core.connector.Diag.dir =
            getExternalFilesDir(null)?.let { java.io.File(it, "diag") } ?: java.io.File(filesDir, "diag")
        // With PingMe on screen a message makes its sound and nothing lands in the shade (owner, Gate G3).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    presence.appVisible = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    presence.appVisible = false
                }
            },
        )
    }
}
