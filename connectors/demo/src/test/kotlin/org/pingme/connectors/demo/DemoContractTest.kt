// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import org.pingme.core.connector.Credentials
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.connector.chat
import org.pingme.core.connector.contract.ConnectorContractTest
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Capabilities
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** The connector contract against the demo network with every capability on. */
class DemoContractTest : ConnectorContractTest() {
    open class DemoHarness(
        capabilities: Capabilities,
    ) : Harness {
        private val credentials = MemoryCredentialStore()
        private val controls =
            DemoControls().apply {
                update {
                    it.copy(capabilities = capabilities, liveActivity = false, confirmationDelay = 10.milliseconds)
                }
            }
        private val demo =
            DemoConnector(controls, credentials, Files.createTempDirectory("demo").toFile(), Clock.System)
        override val connector = demo

        override fun answer(step: LoginStep): LoginResponse? =
            when (step) {
                is LoginStep.Choose -> {
                    LoginResponse.Choice("account")
                }

                is LoginStep.EnterText -> {
                    LoginResponse.Text(
                        if (step.kind ==
                            TextKind.PHONE_NUMBER
                        ) {
                            "+15555550100"
                        } else {
                            "123456"
                        },
                    )
                }

                is LoginStep.OpenWebView -> {
                    LoginResponse.Cookies(mapOf("session" to "demo"))
                }

                else -> {
                    null
                }
            }

        override fun accountFor(done: LoginStep.Done) =
            Account(
                id = AccountId(UUID.randomUUID().toString()),
                network = NetworkId.DEMO,
                displayName = done.accountName,
                colorArgb = 0,
                state = ConnectionState.Connected,
                showInInbox = true,
                notificationMode = NotificationMode.NORMAL,
                credentialRef = done.credentialRef,
            )

        override suspend fun credentialsFor(done: LoginStep.Done) =
            Credentials(done.credentialRef, credentials.load(done.credentialRef)!!)

        override suspend fun chatWithHistory(account: Account) = account.id.chat("sam")

        override suspend fun receiveText(
            chatId: ChatId,
            text: String,
        ) = demo.simulate.incoming(chatId, text)

        override suspend fun startRemoteTyping(chatId: ChatId) = demo.simulate.typing(chatId, typing = true)

        override suspend fun textsSentTo(chatId: ChatId) = demo.textsSentTo(chatId)

        override suspend fun reactionsOn(messageId: MessageId) = demo.myReactionsOn(messageId)

        override suspend fun deletedForEveryone(): Set<MessageId> = demo.deletedForEveryone()
    }

    override fun newHarness(): Harness = DemoHarness(DemoControls.FULL)
}

/** The same contract with the SMS-like minimal capabilities, so every "unsupported" path runs. */
class DemoMinimalContractTest : ConnectorContractTest() {
    override fun newHarness(): Harness = DemoContractTest.DemoHarness(DemoControls.MINIMAL)
}
