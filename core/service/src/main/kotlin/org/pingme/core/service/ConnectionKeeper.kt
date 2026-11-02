// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.pingme.core.connector.Diag
import org.pingme.core.store.AccountRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the connection service back by itself (owner, 2026-11-01). The phone shut PingMe down
 * eight times in five days, each within seconds of the top of an hour, and PingMe could then sit
 * with no connections until it was next opened. So every start of the process, and an alarm
 * every 15 minutes, checks the service is running and starts it if not. Each shutdown the phone
 * recorded goes into the connection diary at the next start, with the phone's own words for it.
 */
@Singleton
class ConnectionKeeper
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val accounts: AccountRepository,
    ) {
        /** Called once per start of the process. */
        fun processStarted() {
            noteShutdowns()
            Diag.connection(TAG, "PingMe started; ${allowances()}")
            // The first check soon, by alarm: starting up has enough to do without opening the store.
            arm(FIRST_CHECK_MS)
        }

        /** The alarm fired, or the phone restarted, or PingMe was updated. */
        suspend fun checkNow(why: String) {
            arm()
            val needed = accounts.accounts().first().any { it.state.wantsConnection() }
            if (!needed || ConnectionService.running) return
            Diag.connection(TAG, "The connection service was not running ($why): starting it")
            try {
                ConnectionService.start(context)
            } catch (
                // Android may refuse a start from the background; the next alarm tries again.
                e: IllegalStateException,
            ) {
                Diag.connection(TAG, "Android refused to start the connection service: ${e.message}")
            }
        }

        /** The next check, in 15 minutes unless told otherwise, even while the phone sleeps. */
        private fun arm(after: Long = WATCH_EVERY_MS) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + after
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchIntent())
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchIntent())
            }
        }

        private fun watchIntent(): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                WATCH_REQUEST,
                Intent(context, ConnectionReceiver::class.java).setAction(ConnectionReceiver.ACTION_WATCH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun allowances(): String {
            val power = context.getSystemService(PowerManager::class.java)
            val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
            return "battery unrestricted=$unrestricted"
        }

        /** Every shutdown the phone recorded since the last start, in its own words. */
        private fun noteShutdowns() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val since = seen.getLong(LAST_EXIT, 0L)
            val exits =
                manager
                    .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                    .filter { it.processName == context.packageName && it.timestamp > since }
                    .sortedBy { it.timestamp }
            exits.forEach { Diag.connection(TAG, describe(it)) }
            exits.lastOrNull()?.let { seen.edit().putLong(LAST_EXIT, it.timestamp).apply() }
        }

        private fun describe(exit: ApplicationExitInfo): String {
            val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
            return "The phone shut PingMe down at $at: reason ${exit.reason}, \"${exit.description}\", " +
                "importance ${exit.importance}, memory ${exit.rss / KB_PER_MB} MB"
        }

        private companion object {
            const val TAG = "PingMeKeeper"
            const val PREFS = "connection_keeper"
            const val LAST_EXIT = "last_exit"
            const val MAX_EXITS = 16
            const val KB_PER_MB = 1024
            const val WATCH_REQUEST = 7100
            const val WATCH_EVERY_MS = 15L * 60 * 1000
            const val FIRST_CHECK_MS = 60L * 1000
        }
    }
