package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMediaBadgesTest {
    private fun labels(facts: PlayerMediaFacts) = PlayerMediaBadges.from(facts).map { it.label }

    @Test fun `decoder facts win over the file name`() {
        val facts = PlayerMediaFacts("Movie.2026.1080p.WEB-DL.DDP5.1.Atmos.DV.HDR10Plus.H.264-Group.mkv", 80_000_000_000,
            width = 3840, height = 1600, videoMime = MimeTypes.VIDEO_DOLBY_VISION, videoCodecs = "dvhe.08.06",
            colorTransfer = C.COLOR_TRANSFER_ST2084, audioMime = MimeTypes.AUDIO_E_AC3_JOC, channels = 6)
        assertEquals(listOf("4K", "80.00 GB", "WEB-DL", "HEVC", "DV", "HDR10+", "ATMOS", "DD+", "5.1"), labels(facts))
    }

    @Test fun `file name fills in before the decoder reports`() {
        assertEquals(listOf("4K", "BLU-RAY", "HEVC", "HDR10", "DTS-HD MA", "7.1"),
            labels(PlayerMediaFacts("Movie.2160p.BluRay.HDR10.x265.DTS-HD.MA.7.1-Group.mkv")))
        assertEquals(listOf("REMUX", "H.264", "TRUEHD"), labels(PlayerMediaFacts("Movie.BDRemux.AVC.TrueHD-Group.mkv")))
    }

    @Test fun `cropped and small frames get the usual names`() {
        assertEquals("4K", PlayerMediaBadges.quality(3840, 1608))
        assertEquals("1080p", PlayerMediaBadges.quality(1920, 800))
        assertEquals("720p", PlayerMediaBadges.quality(1280, 536))
        assertEquals("480p", PlayerMediaBadges.quality(720, 480))
        assertEquals(null, PlayerMediaBadges.quality(0, 0))
    }

    @Test fun `plain stereo stream shows only what is known`() {
        assertEquals(listOf("720p", "H.264", "AAC", "2.0"),
            labels(PlayerMediaFacts(width = 1280, height = 720, videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC, channels = 2)))
    }
}
