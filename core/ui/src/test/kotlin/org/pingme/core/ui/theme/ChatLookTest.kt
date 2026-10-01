// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.NetworkId

/** A chat's own look over the app's appearance (UI_DESIGN.md 3.4). */
class ChatLookTest {
    @Test
    fun onlyWhatTheChatSetsChanges() {
        val app = Appearance(bubbleStyle = BubbleStyle.TONAL, fontScale = 1f)
        val look = ChatLook(bubbleColor = 0xFF2E7D32.toInt(), fontScale = 1.2f)
        val mixed = app.with(look, NetworkId.WHATSAPP)
        assertEquals(0xFF2E7D32.toInt(), mixed.networkColors[NetworkId.WHATSAPP])
        assertEquals(1.2f, mixed.fontScale)
        assertEquals(BubbleStyle.TONAL, mixed.bubbleStyle)
    }

    @Test
    fun aLookSurvivesBeingStoredAndNothingSetStoresNothing() {
        val look = ChatLook(bubbleStyle = BubbleStyle.OUTLINED, wallpaper = ChatWallpaper.None)
        assertEquals(look, ChatLook.fromJson(look.toJson()))
        assertNull(ChatLook().toJson())
        assertEquals(ChatLook(), ChatLook.fromJson("not json"))
    }
}
