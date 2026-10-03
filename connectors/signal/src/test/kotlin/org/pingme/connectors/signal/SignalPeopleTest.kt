// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.AddressBook
import org.pingme.core.connector.AddressBookEntry
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.Credentials
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import java.nio.file.Files

/** The phone's contacts who are on Signal become the account's people (owner, Gate G7). */
class SignalPeopleTest {
    @Test
    fun addressBookNumbersAreReadInEveryShape() {
        assertEquals("+19195550123", normalizeNumber("(919) 555-0123", "+19195550000"))
        assertEquals("+19195550123", normalizeNumber("1 919 555 0123", "+19195550000"))
        assertEquals("+447700900123", normalizeNumber("+44 7700 900123", "+19195550000"))
        assertEquals("+447700900123", normalizeNumber("7700900123", "+447700900000"))
        assertNull(normalizeNumber("911", "+19195550000"))
        assertNull(normalizeNumber("not a number", "+19195550000"))
    }

    @Test
    fun contactsOnSignalArriveAsPeopleNamedFromTheAddressBook() =
        runBlocking {
            val book =
                object : AddressBook {
                    override suspend fun entries() =
                        listOf(
                            AddressBookEntry("Sam Ortiz", listOf("(555) 555-0123")),
                            AddressBookEntry("Even Person", listOf("+15555550124")),
                            AddressBookEntry("Odd Person", listOf("+15555550125")),
                        )
                }
            val bridge = FakeSigBridge()
            val dir = Files.createTempDirectory("signal").toFile()
            // The pretend Signal is linked once its store file exists.
            java.io
                .File(dir, "x.db")
                .also { it.parentFile?.mkdirs() }
                .writeText("linked")
            val connector = SignalConnector(bridge, MemoryCredentialStore(), dir, book)
            val account =
                Account(
                    AccountId("sig"),
                    NetworkId.SIGNAL,
                    "Signal",
                    0,
                    ConnectionState.Connected,
                    true,
                    NotificationMode.NORMAL,
                    "signal/x",
                )
            val events =
                Channel<ConnectorEvent>(Channel.UNLIMITED)
            val flow = connector.connect(account, Credentials("signal/x", ByteArray(0)))
            val job =
                launch(Dispatchers.Default) { flow.collect { events.send(it) } }
            withTimeout(5000) { while (events.receive() !is ConnectorEvent.State) Unit }
            connector.refreshPeople(account.id)
            val people =
                withTimeout(5000) {
                    var found: ConnectorEvent.PeopleUpdated? = null
                    while (found == null) found = events.receive() as? ConnectorEvent.PeopleUpdated
                    found.people
                }
            assertEquals(listOf("Sam Ortiz", "Even Person"), people.map { it.displayName })
            assertEquals(listOf("+15555550123", "+15555550124"), people.map { it.phoneNumber })
            job.cancel()
        }
}
