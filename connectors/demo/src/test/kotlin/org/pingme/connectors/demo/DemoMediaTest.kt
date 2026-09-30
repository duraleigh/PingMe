// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem

/** The generated demo media must be real files that decoders accept. */
class DemoMediaTest {
    @Test
    fun picturesAreValidPngs() {
        val image = ImageIO.read(ByteArrayInputStream(DemoMedia.png(40, 30, 0x6750A4, 0xB3261E)))
        assertEquals(40, image.width)
        assertEquals(30, image.height)
        assertEquals(0x6750A4, image.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0xB3261E, image.getRGB(39, 29) and 0xFFFFFF)
    }

    @Test
    fun gifsAreValidAnimations() {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input =
            ImageIO.createImageInputStream(
                ByteArrayInputStream(DemoMedia.gif(17, listOf(0xFF0000, 0x00FF00, 0x0000FF), 30)),
            )
        assertEquals(3, reader.getNumImages(true))
        listOf(0xFF0000, 0x00FF00, 0x0000FF).forEachIndexed { frame, colour ->
            val image = reader.read(frame)
            assertEquals(17, image.width)
            assertEquals(colour, image.getRGB(16, 16) and 0xFFFFFF)
        }
    }

    @Test
    fun voiceNotesAreValidWavs() {
        val audio = AudioSystem.getAudioInputStream(ByteArrayInputStream(DemoMedia.wav(1500)))
        assertEquals(8000f, audio.format.sampleRate)
        assertEquals(12_000L, audio.frameLength)
    }
}
