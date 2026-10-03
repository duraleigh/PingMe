// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.connectors.instagram.bridge.IgEvent
import org.pingme.connectors.instagram.bridge.IgMessage
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.Credentials
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import java.nio.file.Files

/** A message for a thread not listed yet brings the thread first: named, with its folder (owner, Gate G7). */
class IgThreadsTest {
    @Test
    fun aMessageForAnUnlistedThreadFetchesTheThreadFirst() =
        runBlocking {
            val bridge = FakeIgBridge()
            val connector =
                InstagramConnector(bridge, MemoryCredentialStore(), Files.createTempDirectory("ig").toFile())
            val account =
                Account(
                    AccountId("ig"),
                    NetworkId.INSTAGRAM,
                    "Instagram",
                    0,
                    ConnectionState.Connected,
                    true,
                    NotificationMode.NORMAL,
                    "instagram/100",
                )
            val events = Channel<ConnectorEvent>(Channel.UNLIMITED)
            val flow =
                connector.connect(
                    account,
                    Credentials(
                        "instagram/100",
                        """{"sessionid":"s","csrftoken":"c","ds_user_id":"100"}""".toByteArray(),
                    ),
                )
            val job = launch(Dispatchers.Default) { flow.collect { events.send(it) } }
            withTimeout(5000) { while (events.receive() !is ConnectorEvent.State) Unit }
            bridge.network.sessions.forEach {
                it.emit(IgEvent.Message(IgMessage("n1", "777", "400", 1_759_300_000_000, "text", "hey there")))
            }
            val seen = ArrayList<ConnectorEvent>()
            withTimeout(5000) {
                while (seen.none {
                        it is ConnectorEvent.NewMessage &&
                            it.message.message.chatId.value.endsWith(
                                "777",
                            )
                    }
                ) {
                    seen += events.receive()
                }
            }
            val chat =
                seen.filterIsInstance<ConnectorEvent.ChatUpdated>().first {
                    it.chat.id.value
                        .endsWith("777")
                }
            val message =
                seen.filterIsInstance<ConnectorEvent.NewMessage>().first {
                    it.message.message.chatId ==
                        chat.chat.id
                }
            assertEquals("New Person", chat.chat.title)
            assertEquals(ChatFolder.GENERAL, chat.chat.folder)
            assertEquals("New Person", message.message.sender?.displayName)
            assertEquals(true, seen.indexOf(chat) < seen.indexOf(message))
            job.cancel()
        }
}
