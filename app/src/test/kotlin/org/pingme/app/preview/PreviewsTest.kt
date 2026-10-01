// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every screen preview draws, and shows what it is for (BUILD_PLAN.md P2.8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PreviewsTest {
    @get:Rule
    val compose = createComposeRule()

    // Some previews hold endless animations (the loading indicator), so time only moves when told.
    private fun draws(
        expected: String,
        preview: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { preview() }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        val found = compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("\"$expected\" is not drawn", found)
    }

    @Test fun chat() = draws("Are you still coming tonight?") { ChatPreview() }

    @Test fun chatDark() = draws("Dinner at 7?") { ChatDarkPreview() }

    @Test fun chatDetails() = draws("Sam Ortiz") { ChatDetailsPreview() }

    @Test fun archived() = draws("Archived") { ChatListPreview() }

    @Test fun newGroup() = draws("Climbing") { NewChatPreview() }

    @Test fun search() = draws("Dinner at 7?") { SearchPreview() }

    @Test fun appearance() = draws("Appearance") { AppearancePreview() }

    @Test fun settings() = draws("Privacy") { SettingsHomePreview() }

    @Test fun accounts() = draws("Show in inbox") { AccountsPreview() }

    @Test fun notificationsAndPrivacy() = draws("Auto-copy one-time codes") { NotificationsPrivacyPreview() }

    @Test fun reactionsAndMotion() = draws("Flippy reactions") { ReactionsMotionPreview() }

    @Test fun storageSpacesBackup() = draws("Backup saved.") { StorageSpacesBackupPreview() }

    @Test fun setupMode() = draws("Google Messages mode") { SetupModePreview() }

    @Test fun setupBattery() = draws("Stay connected") { SetupBatteryPreview() }

    @Test fun setupNetwork() = draws("Instagram") { SetupNetworkPreview() }

    @Test fun loginQr() = draws("Device pairing") { LoginQrPreview() }

    @Test fun loginEmoji() = draws("🦊") { LoginEmojiPreview() }

    @Test fun loginCode() = draws("That code didn't work.") { LoginCodePreview() }

    private companion object {
        const val FRAME_MS = 500L
    }
}
