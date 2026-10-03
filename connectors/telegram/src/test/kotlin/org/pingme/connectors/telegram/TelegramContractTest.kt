// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

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

/** The connector contract (BUILD_PLAN.md P1.3) against the pretend Telegram. */
class TelegramContractTest : ConnectorContractTest() {
    class TelegramHarness : Harness {
        private val bridge = FakeTelegramBridge()
        private val credentials = MemoryCredentialStore()
        private var account: AccountId? = null
        override val connector =
            TelegramConnector(bridge, credentials, Files.createTempDirectory("telegram").toFile(), 1, "hash")

        override fun answer(step: LoginStep): LoginResponse? =
            when (step) {
                is LoginStep.EnterText -> LoginResponse.Text(if (step.id == "code") "12345" else "+15555550100")
                else -> null
            }

        override fun accountFor(done: LoginStep.Done) =
            Account(
                id = AccountId("tg-${UUID.randomUUID()}").also { account = it },
                network = NetworkId.TELEGRAM,
                displayName = done.accountName,
                colorArgb = 0,
                state = ConnectionState.Connected,
                showInInbox = true,
                notificationMode = NotificationMode.NORMAL,
                credentialRef = done.credentialRef,
            )

        override suspend fun credentialsFor(done: LoginStep.Done) =
            Credentials(done.credentialRef, credentials.load(done.credentialRef)!!)

        override suspend fun chatWithHistory(account: Account) = account.id.chat(FakeTelegram.CHAT.toString())

        override suspend fun receiveText(
            chatId: ChatId,
            text: String,
        ) = bridge.network.receive(chatId.remoteId.toLong(), text)

        override suspend fun startRemoteTyping(chatId: ChatId) = bridge.network.remoteTyping(chatId.remoteId.toLong())

        override suspend fun textsSentTo(chatId: ChatId): List<String> =
            bridge.network.sentTexts[chatId.remoteId.toLong()].orEmpty()

        override suspend fun reactionsOn(messageId: MessageId) = bridge.network.reactions[messageId.remoteId].orEmpty()

        override suspend fun deletedForEveryone(): Set<MessageId> =
            bridge.network.revoked
                .map {
                    MessageId("${account?.value}/$it")
                }.toSet()
    }

    override fun newHarness(): Harness = TelegramHarness()
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
