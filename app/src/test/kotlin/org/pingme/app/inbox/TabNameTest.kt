// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.NetworkId

/** The bottom bar's one-word names (owner, 2026-11-02). */
class TabNameTest {
    @Test
    fun longNamesAreShortOnTheBarOnly() {
        assertEquals("Messages", NetworkId.GMESSAGES.tabName)
        assertEquals("Facebook", NetworkId.MESSENGER.tabName)
        assertEquals("Voice", NetworkId.GVOICE.tabName)
        assertEquals("Instagram", NetworkId.INSTAGRAM.tabName)
        assertEquals("Google Messages", NetworkId.GMESSAGES.displayName)
    }
}
