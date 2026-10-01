// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

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
import kotlin.time.Clock

/** The connector contract (BUILD_PLAN.md P3.3) against the recorded libgm session. */
class GmessagesContractTest : ConnectorContractTest() {
    class GmessagesHarness : Harness {
        private val bridge = FakeGmBridge()
        private val credentials = MemoryCredentialStore()
        override val connector =
            GmessagesConnector(
                bridge,
                AllGood,
                credentials,
                Files.createTempDirectory("gmessages").toFile(),
                Clock.System,
            )

        override fun answer(step: LoginStep): LoginResponse? =
            when (step) {
                is LoginStep.OpenWebView -> LoginResponse.Cookies(FakeGmBridge.REQUIRED.associateWith { "value-$it" })
                is LoginStep.Fix -> LoginResponse.CheckAgain
                else -> null
            }

        override fun accountFor(done: LoginStep.Done) =
            Account(
                id = AccountId("gm-${UUID.randomUUID()}"),
                network = NetworkId.GMESSAGES,
                displayName = done.accountName,
                colorArgb = 0,
                state = ConnectionState.Connected,
                showInInbox = true,
                notificationMode = NotificationMode.NORMAL,
                credentialRef = done.credentialRef,
            )

        override suspend fun credentialsFor(done: LoginStep.Done) =
            Credentials(done.credentialRef, credentials.load(done.credentialRef)!!)

        override suspend fun chatWithHistory(account: Account) = account.id.chat("12")

        override suspend fun receiveText(
            chatId: ChatId,
            text: String,
        ) = bridge.phone.receive(chatId.remoteId, text)

        override suspend fun startRemoteTyping(chatId: ChatId) = bridge.phone.remoteTyping(chatId.remoteId)

        override suspend fun textsSentTo(chatId: ChatId): List<String> =
            bridge.phone.sentTexts[chatId.remoteId].orEmpty()

        override suspend fun reactionsOn(messageId: MessageId) = bridge.phone.myReactionsOn(messageId.remoteId)

        override suspend fun deletedForEveryone(): Set<MessageId> = emptySet()
    }

    override fun newHarness(): Harness = GmessagesHarness()
}

/** A phone with Google Messages installed and set as the SMS app. */
object AllGood : GmessagesChecks {
    override fun installed() = true

    override fun isDefaultSmsApp() = true
}
