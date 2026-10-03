// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram.td

import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TDLib as Kotlin sees it: a client per account that answers requests and streams
 * updates. The real one is TDLib's own Java client over its native library; tests
 * replace it with a fake, so no test ever loads the native library.
 */
interface TelegramBridge {
    /** A new client; every update TDLib sends goes to [onUpdate], on TDLib's own thread. */
    fun newClient(onUpdate: (TdApi.Object) -> Unit): TelegramClient
}

interface TelegramClient {
    /** Sends a request and waits for its answer; a TDLib error becomes a [TdError]. */
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T

    /** Asks TDLib to close; the updates end with the closed state. */
    fun close()
}

/** TDLib's error answer, with its code (401 and 404 are the ones callers look at). */
class TdError(
    val code: Int,
    message: String,
) : Exception("$code: $message")

/** The real client: TDLib's Java API from the prebuilt package. */
@Singleton
class TdlibBridge
    @Inject
    constructor() : TelegramBridge {
        override fun newClient(onUpdate: (TdApi.Object) -> Unit): TelegramClient {
            val client = Client.create({ onUpdate(it) }, null, null)
            return TdlibClient(client)
        }
    }

private class TdlibClient(
    private val client: Client,
) : TelegramClient {
    override suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client.send(function, { result ->
                when (result) {
                    is TdApi.Error -> {
                        cont.resumeWithException(TdError(result.code, result.message))
                    }

                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as T)
                    }
                }
            }, { e -> cont.resumeWithException(e) })
        }

    override fun close() {
        client.send(TdApi.Close(), {}, {})
    }
}
