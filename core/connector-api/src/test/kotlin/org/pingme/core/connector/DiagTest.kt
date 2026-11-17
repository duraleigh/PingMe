// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        Diag.dir = temp.root
    }

    @After
    fun tearDown() {
        Diag.dir = null
    }

    /** One repeating line erased all but the last two hours of the diary (owner, 2026-11-01). */
    @Test
    fun aRepeatingLineIsWrittenOnceWithACount() {
        repeat(1000) { Diag.note("T", "the same thing") }
        Diag.note("T", "something else")
        val lines = File(temp.root, "pingme.log").readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].endsWith("(the line above came 999 more time(s))"))
        assertTrue(lines[2].endsWith("T: something else"))
    }

    @Test
    fun connectionsHaveADiaryOfTheirOwn() {
        Diag.connection("T", "connected")
        Diag.note("T", "a chat moved")
        assertEquals(1, File(temp.root, "connection.log").readLines().size)
        assertEquals(1, File(temp.root, "pingme.log").readLines().size)
        assertFalse(File(temp.root, "pingme.log").readText().contains("connected"))
    }
}
