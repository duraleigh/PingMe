// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

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

/** The connector contract (BUILD_PLAN.md P1.3) against the pretend Signal. */
class SignalContractTest : ConnectorContractTest() {
    class SignalHarness : Harness {
        private val bridge = FakeSigBridge()
        private val credentials = MemoryCredentialStore()
        private var account: AccountId? = null
        override val connector = SignalConnector(bridge, credentials, Files.createTempDirectory("signal").toFile())

        override fun answer(step: LoginStep): LoginResponse? = null

        override fun accountFor(done: LoginStep.Done) =
            Account(
                id = AccountId("sig-${UUID.randomUUID()}").also { account = it },
                network = NetworkId.SIGNAL,
                displayName = done.accountName,
                colorArgb = 0,
                state = ConnectionState.Connected,
                showInInbox = true,
                notificationMode = NotificationMode.NORMAL,
                credentialRef = done.credentialRef,
            )

        override suspend fun credentialsFor(done: LoginStep.Done) =
            Credentials(done.credentialRef, credentials.load(done.credentialRef)!!)

        override suspend fun chatWithHistory(account: Account) = account.id.chat(FakeSignal.SAM)

        override suspend fun receiveText(
            chatId: ChatId,
            text: String,
        ) = bridge.network.receive(chatId.remoteId, text)

        override suspend fun startRemoteTyping(chatId: ChatId) = bridge.network.remoteTyping(chatId.remoteId)

        override suspend fun textsSentTo(chatId: ChatId): List<String> =
            bridge.network.sentTexts[chatId.remoteId].orEmpty()

        override suspend fun reactionsOn(messageId: MessageId) = bridge.network.reactions[messageId.remoteId].orEmpty()

        override suspend fun deletedForEveryone(): Set<MessageId> =
            bridge.network.revoked
                .map { messageId(it) }
                .toSet()

        private fun messageId(remote: String) = MessageId("${account?.value}/$remote")
    }

    override fun newHarness(): Harness = SignalHarness()
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
