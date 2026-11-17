// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.robolectric.RobolectricTestRunner

/** New chat's one network pill beside "On" (owner, 2026-11-02). */
@RunWith(RobolectricTestRunner::class)
class NetworkPillTest {
    @get:Rule
    val compose = createComposeRule()

    private fun account(
        id: String,
        network: NetworkId,
    ) = Account(AccountId(id), network, id, 0, ConnectionState.Connected, true, NotificationMode.NORMAL, "")

    private val accounts =
        listOf(account("gm", NetworkId.GMESSAGES), account("wa", NetworkId.WHATSAPP), account("tg", NetworkId.TELEGRAM))

    private var picked by mutableStateOf<AccountId?>(AccountId("gm"))

    private fun show() =
        compose.setContent {
            PingMeTheme(Appearance()) { NetworkPill(accounts, picked, { picked = it }) }
        }

    @Test
    fun onlyThePickedNetworkShows() {
        show()
        compose.onNodeWithText("Google Messages").assertExists()
        compose.onNodeWithText("WhatsApp").assertDoesNotExist()
    }

    @Test
    fun aFlickLeftOrUpMovesOnAndRightOrDownGoesBack() {
        show()
        compose.onNodeWithTag(NEW_CHAT_NETWORK).performTouchInput { swipe(center, center - Offset(40f, 0f), 60) }
        compose.waitForIdle()
        assertEquals(AccountId("wa"), picked)
        compose.onNodeWithTag(NEW_CHAT_NETWORK).performTouchInput { swipe(center, center - Offset(0f, 40f), 60) }
        compose.waitForIdle()
        assertEquals(AccountId("tg"), picked)
        compose.onNodeWithTag(NEW_CHAT_NETWORK).performTouchInput { swipe(center, center + Offset(0f, 40f), 60) }
        compose.waitForIdle()
        assertEquals(AccountId("wa"), picked)
        compose.onNodeWithText("WhatsApp").assertExists()
    }

    @Test
    fun aFlickPastTheLastComesRoundToTheFirst() {
        picked = AccountId("tg")
        show()
        compose.onNodeWithTag(NEW_CHAT_NETWORK).performTouchInput { swipe(center, center - Offset(40f, 0f), 60) }
        compose.waitForIdle()
        assertEquals(AccountId("gm"), picked)
    }

    @Test
    fun aTapListsEveryNetworkToPickFrom() {
        show()
        compose.onNodeWithTag(NEW_CHAT_NETWORK).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Telegram").performClick()
        compose.waitForIdle()
        assertEquals(AccountId("tg"), picked)
    }
}
