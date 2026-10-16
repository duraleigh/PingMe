// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.pingme.core.store.AccountRepository
import javax.inject.Inject

/**
 * The one foreground service, "PingMe is connected" (DESIGN.md 6.4). It hosts the
 * [ConnectorSupervisor], tells it when the network changes, and stops itself when no
 * account needs a connection.
 */
@AndroidEntryPoint
class ConnectionService : Service() {
    @Inject lateinit var supervisor: ConnectorSupervisor

    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var contactsWatcher: org.pingme.core.service.people.ContactsWatcher

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    private var accountWatch: Job? = null

    // Only a real change counts: not the network already in use when the service starts.
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var lost = false

            override fun onAvailable(network: Network) {
                val changed = lost || (current != null && current != network)
                current = network
                lost = false
                if (changed) supervisor.onNetworkChanged()
            }

            override fun onLost(network: Network) {
                if (current == network) {
                    current = null
                    lost = true
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, connectedNotification(), type)
        supervisor.start()
        contactsWatcher.start()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        accountWatch =
            scope.launch {
                accounts.accounts().collect { all ->
                    // An empty list is the store reopening, not every account gone: keep running.
                    if (all.isNotEmpty() && all.none { it.state.wantsConnection() }) stopSelf()
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ) = START_STICKY

    override fun onDestroy() {
        accountWatch?.cancel()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        supervisor.stop()
        contactsWatcher.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectedNotification() =
        NotificationCompat
            .Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(getString(R.string.connection_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.channel_connection),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        return CHANNEL
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "connection"

        /** Starts the service. Call while the app is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        /** Starts the service only when some account needs a connection. */
        suspend fun startIfNeeded(
            context: Context,
            accounts: AccountRepository,
        ) {
            if (accounts.accounts().first().any { it.state.wantsConnection() }) start(context)
        }
    }
}
