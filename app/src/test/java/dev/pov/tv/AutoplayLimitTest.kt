package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class AutoplayLimitTest {
    @Test fun `catalog runtimes become minutes`() {
        assertEquals(126, AutoplayLimit.runtimeMinutes("126 min"))
        assertEquals(126, AutoplayLimit.runtimeMinutes("2h 6min"))
        assertEquals(45, AutoplayLimit.runtimeMinutes("45"))
        assertNull(AutoplayLimit.runtimeMinutes(""))
        assertNull(AutoplayLimit.runtimeMinutes("N/A"))
    }

    @Test fun `the estimated bitrate appears as a badge beside the size`() {
        val source = dev.pov.tv.data.SourceItem("Movie.mkv", "fixture", sizeBytes = 10_000_000_000L)
        assertEquals("≈11 Mbps", SourceBadges.from(source, runtimeMinutes = 120)[2].label)
        assertTrue(SourceBadges.from(source).none { it.label.endsWith("Mbps") })
    }

    @Test fun `bitrate decides when the runtime is known, size otherwise`() {
        val tenGb = 10_000_000_000L
        // 10 GB over 120 minutes is about 11 Mbps.
        assertEquals(11.1, AutoplayLimit.mbps(tenGb, 120)!!, 0.1)
        assertFalse(AutoplayLimit.tooBig(tenGb, 120, maxMbps = 25, maxBytes = 5_000_000_000L))
        assertTrue(AutoplayLimit.tooBig(tenGb, 45, maxMbps = 25, maxBytes = 50_000_000_000L))
        // Unknown runtime: the size cap for this kind of title applies.
        assertTrue(AutoplayLimit.tooBig(tenGb, null, maxMbps = 25, maxBytes = 5_000_000_000L))
        assertFalse(AutoplayLimit.tooBig(null, 120, maxMbps = 25, maxBytes = 5_000_000_000L))
    }
}
