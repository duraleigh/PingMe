// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

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

/** The connector contract (BUILD_PLAN.md P1.3) against the pretend WhatsApp. */
class WhatsappContractTest : ConnectorContractTest() {
    class WhatsappHarness : Harness {
        private val bridge = FakeWaBridge()
        private val credentials = MemoryCredentialStore()
        override val connector =
            WhatsappConnector(bridge, credentials, Files.createTempDirectory("whatsapp").toFile())

        override fun answer(step: LoginStep): LoginResponse? =
            when (step) {
                is LoginStep.EnterText -> LoginResponse.Text("+1 555 555 0100")
                else -> null
            }

        private var account: AccountId? = null

        override fun accountFor(done: LoginStep.Done) =
            Account(
                id = AccountId("wa-${UUID.randomUUID()}").also { account = it },
                network = NetworkId.WHATSAPP,
                displayName = done.accountName,
                colorArgb = 0,
                state = ConnectionState.Connected,
                showInInbox = true,
                notificationMode = NotificationMode.NORMAL,
                credentialRef = done.credentialRef,
            )

        override suspend fun credentialsFor(done: LoginStep.Done) =
            Credentials(done.credentialRef, credentials.load(done.credentialRef)!!)

        override suspend fun chatWithHistory(account: Account) = account.id.chat(FakeWhatsapp.SAM)

        override suspend fun receiveText(
            chatId: ChatId,
            text: String,
        ) = bridge.network.receive(chatId.remoteId, text)

        override suspend fun startRemoteTyping(chatId: ChatId) = bridge.network.remoteTyping(chatId.remoteId)

        override suspend fun textsSentTo(chatId: ChatId): List<String> =
            bridge.network.sentTexts[chatId.remoteId].orEmpty()

        override suspend fun reactionsOn(messageId: MessageId) = bridge.network.myReactionsOn(messageId.remoteId)

        // The pretend network keeps "chat/id"; PingMe's ids carry the account in front.
        override suspend fun deletedForEveryone(): Set<MessageId> =
            bridge.network.revoked
                .map { MessageId("${account?.value}/$it") }
                .toSet()
    }

    override fun newHarness(): Harness = WhatsappHarness()
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
