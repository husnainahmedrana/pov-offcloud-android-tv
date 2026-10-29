package dev.pov.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSyncTest {
    @After fun reset() = SubtitleSync.reset()

    @Test fun `steps add up, stay within limits and label both directions`() {
        assertEquals("0.0 s", SubtitleSync.label())
        SubtitleSync.adjust(1000); SubtitleSync.adjust(100)
        assertEquals(1100L, SubtitleSync.offsetMs); assertEquals("+1.1 s", SubtitleSync.label())
        SubtitleSync.adjust(-2500)
        assertEquals("−1.4 s", SubtitleSync.label())
        repeat(100) { SubtitleSync.adjust(-1000) }
        assertEquals(-SubtitleSync.LIMIT_MS, SubtitleSync.offsetMs)
        SubtitleSync.reset()
        assertEquals(0L, SubtitleSync.offsetMs)
    }
}
