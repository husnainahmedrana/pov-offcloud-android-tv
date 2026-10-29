package dev.pov.tv

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import java.util.Locale
import kotlin.math.abs

/**
 * Subtitle timing offset for the current video. Positive values show subtitles later, negative values earlier.
 * It resets for every new video, because each release is out of sync by its own amount.
 */
internal object SubtitleSync {
    const val LIMIT_MS = 30_000L
    @Volatile var offsetMs: Long = 0L
        private set

    fun reset() { offsetMs = 0L }
    fun adjust(stepMs: Long) { offsetMs = (offsetMs + stepMs).coerceIn(-LIMIT_MS, LIMIT_MS) }

    fun label(value: Long = offsetMs): String = when {
        value == 0L -> "0.0 s"
        else -> (if (value > 0) "+" else "−") + String.format(Locale.US, "%.1f s", abs(value) / 1000.0)
    }
}

/** Renders text as if playback were [SubtitleSync.offsetMs] behind, so cues appear later (or earlier when negative). */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SubtitleSyncRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - SubtitleSync.offsetMs * 1000, elapsedRealtimeUs)
    }
}
