// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.pingme.connectors.signal.bridge.SigBridge
import org.pingme.connectors.signal.bridge.SigError
import org.pingme.connectors.signal.bridge.SigEvent
import org.pingme.connectors.signal.bridge.SigSession
import org.pingme.connectors.signal.bridge.sigJson
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.loginFlow
import java.io.File

/**
 * Linking Signal (BUILD_PLAN.md Phase 6, network 3): Signal links a new device one way
 * only, by the phone's Signal app scanning a QR code, so:
 * 1. ShowQr: the code, with a Share button that sends it as a picture to another screen,
 *    since PingMe runs on the same phone as the Signal app (owner, 2026-10-02). A fresh
 *    code replaces it every 45 seconds, up to six times.
 * 2. In Signal on the phone: Settings > Linked devices > Link new device, scan it, and
 *    say yes to transferring the message history.
 * 3. Done once Signal confirms: the keys are in `signal/<account id>.db`, and the
 *    credential ref `signal/<account id>` names them.
 */
internal fun signalLoginFlow(
    bridge: SigBridge,
    credentials: CredentialStore,
    dir: File,
): LoginFlow =
    loginFlow {
        show(LoginStep.WaitForConfirmation("start", "Asking Signal for a code…", emoji = null))
        val events = Channel<SigEvent>(Channel.UNLIMITED)
        val linkPath = SignalConnector.storePath(dir, "signal/link-${System.currentTimeMillis()}")
        val session: SigSession =
            try {
                bridge.newSession(linkPath) { json ->
                    runCatching {
                        sigJson.decodeFromString(
                            SigEvent.serializer(),
                            json,
                        )
                    }.getOrNull()?.let { events.trySend(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                return@loginFlow failed(e)
            }
        try {
            when (val outcome = link(session, events)) {
                is SigEvent.LinkDone -> {
                    withContext(Dispatchers.IO) { session.close() }
                    val ref = "signal/${outcome.id}"
                    moveStore(linkPath, SignalConnector.storePath(dir, ref))
                    credentials.save(ref, outcome.phone.toByteArray())
                    show(LoginStep.Done("done", ref, accountName = outcome.phone.ifEmpty { "Signal" }))
                }

                is SigEvent.LinkError -> {
                    failedWith(reasonFor(outcome.reason))
                }

                else -> {
                    failedWith("Signal did not finish linking in time. Try again.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            failed(e)
        } finally {
            withContext(Dispatchers.IO) { runCatching { session.cancelLink() } }
        }
    }

/** Shows each QR code as it comes and waits for Signal's answer. */
private suspend fun LoginScope.link(
    session: SigSession,
    events: Channel<SigEvent>,
): SigEvent? {
    withContext(Dispatchers.IO) { session.startLink(DEVICE_NAME) }
    return withTimeoutOrNull(LINK_TIMEOUT_MS) {
        while (true) {
            when (val event = events.receive()) {
                is SigEvent.LinkQr -> {
                    show(LoginStep.ShowQr("qr", event.url, QR_HINT, canShare = true))
                }

                is SigEvent.LinkDone, is SigEvent.LinkError -> {
                    return@withTimeoutOrNull event
                }

                else -> {
                    Unit
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}

/** The linked store takes its account's name; SQLite's side files move with it. */
private suspend fun moveStore(
    from: String,
    to: String,
) = withContext(Dispatchers.IO) {
    for (suffix in listOf("", "-wal", "-shm", "-journal", ".transferred")) {
        val source = File(from + suffix)
        if (source.exists()) {
            val target = File(to + suffix)
            target.delete()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }
}

private suspend fun LoginScope.failed(e: Exception) = failedWith(reasonFor(e.message.orEmpty()))

private suspend fun LoginScope.failedWith(reason: String) = show(LoginStep.Failed("failed", reason, canRetry = true))

/** Plain words for each linking failure. */
internal fun reasonFor(message: String): String =
    when {
        message.startsWith("${SigError.NOT_CONNECTED}:") || "connect" in message.lowercase() -> {
            "Could not reach Signal. Check the connection and try again."
        }

        "cancel" in message.lowercase() -> {
            "Linking cancelled"
        }

        "no QR code was scanned" in message -> {
            "No code was scanned in time. Try again."
        }

        "429" in message || "rate" in message.lowercase() -> {
            "Signal asked PingMe to wait before linking again. Try in a few minutes."
        }

        message.isBlank() -> {
            "Signal did not finish linking. Try again."
        }

        else -> {
            "Signal could not link: ${message.substringAfter(": ")}"
        }
    }

private const val DEVICE_NAME = "PingMe"
private const val LINK_TIMEOUT_MS = 6 * 50 * 1000L

private const val QR_HINT =
    "In Signal on your phone: Settings > Linked devices > Link new device, then scan this " +
        "code. It is on this phone, so share it to another screen first. Say yes when Signal " +
        "offers to transfer your message history."
