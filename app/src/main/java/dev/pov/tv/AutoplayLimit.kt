package dev.pov.tv

/**
 * Auto-play's largest stream. The estimated bitrate (file size over the title's runtime) is what a connection has to
 * keep up with, so one Mbps cap fits movies and episodes alike; when the runtime is unknown, a size cap per kind
 * stands in for it.
 */
internal object AutoplayLimit {
    /** Minutes from catalog runtimes such as "126 min", "2h 6min" or "45". */
    fun runtimeMinutes(runtime: String?): Int? {
        val text = runtime?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return null
        val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*m").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (hours == 0) text.toIntOrNull() ?: 0 else 0
        return (hours * 60 + minutes).takeIf { it in 1..600 }
    }

    /** Average bitrate in Mbps, or null without both a size and a runtime. */
    fun mbps(sizeBytes: Long?, minutes: Int?): Double? {
        if (sizeBytes == null || sizeBytes <= 0 || minutes == null || minutes <= 0) return null
        return sizeBytes * 8.0 / (minutes * 60.0) / 1_000_000.0
    }

    /** Over the bitrate cap when the bitrate is known; otherwise over the size cap for this kind of title. */
    fun tooBig(sizeBytes: Long?, minutes: Int?, maxMbps: Int, maxBytes: Long): Boolean {
        val rate = mbps(sizeBytes, minutes)
        if (rate != null && maxMbps > 0) return rate > maxMbps
        return maxBytes > 0 && (sizeBytes ?: 0) > maxBytes
    }
}
