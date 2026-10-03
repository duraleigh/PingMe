// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger

import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.Credentials
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.chat
import org.pingme.core.connector.contract.ConnectorContractTest
import org.pingme.core.connector.remoteId
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The connector contract (BUILD_PLAN.md P1.3) against the pretend Messenger. */
class MessengerContractTest : ConnectorContractTest() {
    class MessengerHarness : Harness {
        private val bridge = FakeFbBridge()
        private val credentials = MemoryCredentialStore()
        private var account: AccountId? = null
        override val connector =
            MessengerConnector(bridge, credentials, Files.createTempDirectory("messenger").toFile())

        override fun answer(step: LoginStep): LoginResponse? =
            when (step) {
                is LoginStep.OpenWebView -> {
                    LoginResponse.Cookies(mapOf("xs" to "x", "c_user" to "100", "datr" to "d"))
                }

                else -> {
                    null
                }
            }

        override fun accountFor(done: LoginStep.Done) =
            Account(
                id = AccountId("fb-${UUID.randomUUID()}").also { account = it },
                network = NetworkId.MESSENGER,
                displayName = done.accountName,
                colorArgb = 0,
                state = ConnectionState.Connected,
                showInInbox = true,
                notificationMode = NotificationMode.NORMAL,
                credentialRef = done.credentialRef,
            )

        override suspend fun credentialsFor(done: LoginStep.Done) =
            Credentials(done.credentialRef, credentials.load(done.credentialRef)!!)

        override suspend fun chatWithHistory(account: Account) = account.id.chat(FakeMessenger.THREAD)

        override suspend fun receiveText(
            chatId: ChatId,
            text: String,
        ) = bridge.network.receive(chatId.remoteId, text)

        override suspend fun startRemoteTyping(chatId: ChatId) = bridge.network.remoteTyping(chatId.remoteId)

        override suspend fun textsSentTo(chatId: ChatId): List<String> =
            bridge.network.sentTexts[chatId.remoteId].orEmpty()

        override suspend fun reactionsOn(messageId: MessageId) = bridge.network.reactions[messageId.remoteId].orEmpty()

        override suspend fun deletedForEveryone(): Set<MessageId> =
            bridge.network.unsent
                .map { MessageId("${account?.value}/$it") }
                .toSet()
    }

    override fun newHarness(): Harness = MessengerHarness()
}

/** A credential store that keeps secrets in memory for one test. */
class MemoryCredentialStore : CredentialStore {
    private val secrets = ConcurrentHashMap<String, ByteArray>()

    override suspend fun save(
        ref: String,
        secret: ByteArray,
    ) {
        secrets[ref] = secret
    }

    override suspend fun load(ref: String) = secrets[ref]

    override suspend fun delete(ref: String) {
        secrets.remove(ref)
    }
}
