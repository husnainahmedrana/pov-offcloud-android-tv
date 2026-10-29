package dev.pov.tv.data

import java.util.IdentityHashMap
import java.util.Locale

/** Language claims read from release names and provider text; nothing is probed until the viewer plays a link. */
object SourceLanguages {
    data class Detected(val audio: Set<String>, val subtitles: Set<String>, val multiAudio: Boolean)

    // Extra release-name spellings. Two-letter codes are too ambiguous on their own and only appear with a region.
    private val AUDIO_WORDS = mapOf(
        "en" to listOf("eng", "🇬🇧", "🇺🇸"),
        "pt" to listOf("por", "portugues", "brazilian", "pt[ ._-]?br", "pt[ ._-]?pt", "dublado", "dub[ ._-]?pt", "🇧🇷", "🇵🇹"),
        "es" to listOf("spa", "esp", "espanol", "castellano", "latino", "es[ ._-]?mx", "🇪🇸", "🇲🇽"),
        "fr" to listOf("fre", "fra", "vff", "vfq", "vfi", "vf2", "truefrench", "🇫🇷"),
        "de" to listOf("ger", "deu", "🇩🇪"),
        "it" to listOf("ita", "🇮🇹"),
        "hi" to listOf("hin", "🇮🇳"),
        "ur" to listOf("urd", "🇵🇰"),
        "ar" to listOf("ara", "🇸🇦", "🇪🇬", "🇦🇪"),
        "ru" to listOf("rus", "🇷🇺"),
        "ja" to listOf("jpn", "jap", "🇯🇵"),
        "ko" to listOf("kor", "🇰🇷"),
        "zh" to listOf("chi", "zho", "chs", "cht", "mandarin", "cantonese", "🇨🇳", "🇹🇼", "🇭🇰"),
        "tr" to listOf("tur", "🇹🇷"),
        "pl" to listOf("pol", "pldub", "🇵🇱"),
        "nl" to listOf("dut", "nld", "🇳🇱"),
        "ta" to emptyList(), "te" to emptyList(), "ml" to emptyList(), "bn" to emptyList(),
    )
    private val SUBTITLE_WORDS = mapOf("pt" to listOf("legendado", "legendas?"), "fr" to listOf("vostfr"), "es" to listOf("subtitulado"))
    private const val EDGE_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val EDGE_AFTER = "(?![\\p{L}\\p{N}])"
    // Providers such as Meteor print one line per kind: "🌐 🇮🇳" for audio and "💬 🇬🇧 🇮🇳" for subtitles.
    private val segments = Regex("\\n| · | \\| ")
    private val subtitleLine = Regex("(?i)^\\s*(?:💬|📝|sub(?:title)?s?\\s*:)")
    private val audioLine = Regex("^\\s*(?:🌐|🌎|🌍|🗣|🔊)")
    private val subtitleBefore = Regex("(?i)(?:sub(?:title)?s?|💬|📝)[\\s:._\\-\\[(]{0,3}$")
    private val subtitleAfter = Regex("(?i)^[\\s._\\-\\])]{0,3}(?:sub(?:title)?s?|subbed|legendado)$EDGE_AFTER")
    private val multi = Regex("(?i)$EDGE_BEFORE(?:multi|dual)(?:[ ._-]?audio)?$EDGE_AFTER(?![ ._-]?sub)")
    private val vostfr = Regex("(?i)${EDGE_BEFORE}vostfr$EDGE_AFTER")
    private val subtitleWords = SUBTITLE_WORDS.mapValues { (_, words) -> Regex("(?i)$EDGE_BEFORE(?:${words.joinToString("|")})$EDGE_AFTER") }

    /** One alternation for every language, so each text is scanned once; the matching group names the language. */
    private class Scanner(val codes: List<String>, val regex: Regex)
    private val scanners = HashMap<Set<String>, Scanner>()

    private fun alternatives(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val names = listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale))
            .filter { it.length > 2 && !it.equals(code, true) }.map(Regex::escape)
        val (flags, words) = AUDIO_WORDS[code].orEmpty().partition { it.any(Character::isSurrogate) }
        val textual = (names + words).distinct().joinToString("|")
        return listOfNotNull(textual.takeIf { it.isNotEmpty() }?.let { "$EDGE_BEFORE(?:$it)$EDGE_AFTER" },
            flags.joinToString("|").takeIf { it.isNotEmpty() }).joinToString("|")
    }

    private fun scanner(extra: Collection<String>): Scanner {
        val codes = (AUDIO_WORDS.keys + extra).filter { it.isNotEmpty() && it != "off" }.toSet()
        return synchronized(scanners) {
            scanners.getOrPut(codes) {
                val ordered = codes.toList().filter { alternatives(it).isNotEmpty() }
                Scanner(ordered, Regex("(?i)" + ordered.mapIndexed { index, code -> "(?<l$index>${alternatives(code)})" }.joinToString("|")))
            }
        }
    }

    fun detect(text: String, extra: Collection<String> = emptyList()): Detected {
        val sample = text.take(4000)
        val scan = scanner(extra)
        val audio = linkedSetOf<String>(); val subtitles = linkedSetOf<String>()
        for (segment in sample.split(segments)) {
            val kind = when { subtitleLine.containsMatchIn(segment) -> 1; audioLine.containsMatchIn(segment) -> 2; else -> 0 }
            for (match in scan.regex.findAll(segment)) {
                val code = scan.codes[scan.codes.indices.first { match.groups["l$it"] != null }]
                val subtitle = when (kind) {
                    1 -> true
                    2 -> false
                    else -> subtitleBefore.containsMatchIn(segment.substring(maxOf(0, match.range.first - 12), match.range.first)) ||
                        subtitleAfter.containsMatchIn(segment.substring(match.range.last + 1, minOf(segment.length, match.range.last + 13)))
                }
                if (subtitle) subtitles += code else audio += code
            }
        }
        subtitleWords.forEach { (code, regex) -> if (regex.containsMatchIn(sample)) subtitles += code }
        // "VOSTFR" releases keep the original audio; French belongs to the subtitles only.
        if (vostfr.containsMatchIn(sample)) audio -= "fr"
        return Detected(audio, subtitles, multi.containsMatchIn(sample))
    }
}

/**
 * Puts links in the viewer's audio language first, then untagged links when the title's original language matches,
 * then multi-audio, other untagged and other-language links. Links with subtitles in the preferred language lead
 * within each group, and quality and size sort inside that.
 */
data class SourceLanguageOrder(val audio: String, val subtitle: String, val fallback: String = "") {
    private val subtitleCode = subtitle.takeUnless { it == "off" }.orEmpty()
    val active: Boolean get() = audio.isNotEmpty() || subtitleCode.isNotEmpty()
    private val cache = IdentityHashMap<SourceItem, SourceLanguages.Detected>()

    fun detect(source: SourceItem): SourceLanguages.Detected = synchronized(cache) {
        cache.getOrPut(source) { SourceLanguages.detect("${source.title} · ${source.detail}", listOf(audio, subtitleCode, fallback)) }
    }

    /** True only for an explicit tag; untagged links never get a language badge. */
    fun audioMatch(source: SourceItem): Boolean = audio.isNotEmpty() && audio in detect(source).audio
    fun multiAudio(source: SourceItem): Boolean = audio.isNotEmpty() && !audioMatch(source) && detect(source).multiAudio
    fun subtitleMatch(source: SourceItem): Boolean = subtitleCode.isNotEmpty() && subtitleCode in detect(source).subtitles

    /** Untagged releases are usually the original version; the language most single-audio links carry stands in for it. */
    fun originalLanguage(sources: List<SourceItem>): String = sources.mapNotNull { detect(it).audio.singleOrNull() }
        .groupingBy { it }.eachCount().let { counts ->
            val top = counts.values.maxOrNull() ?: return "en"
            // A tie is no evidence of a foreign original; English is the usual one.
            counts.filterValues { it == top }.keys.let { if ("en" in it) "en" else it.first() }
        }

    private fun audioGroup(found: SourceLanguages.Detected, original: String): Int = when {
        audio.isEmpty() || audio in found.audio -> 0
        found.audio.isEmpty() && !found.multiAudio && original == audio -> 1
        found.multiAudio -> 2
        // With a fallback set, links in it (tagged, or untagged originals in it) come before other languages.
        fallback.isNotEmpty() && (fallback in found.audio || (found.audio.isEmpty() && original == fallback)) -> 3
        found.audio.isEmpty() -> 4
        else -> 5
    }

    fun sorted(sources: List<SourceItem>, base: SourceSortOrder): List<SourceItem> {
        val ordered = base.sorted(sources)
        if (!active) return ordered
        val original = originalLanguage(ordered)
        return ordered.map { source ->
            val found = detect(source)
            val subtitles = when {
                subtitleCode.isEmpty() || subtitleCode in found.subtitles -> 0
                fallback.isNotEmpty() && fallback in found.subtitles -> 1
                else -> 2
            }
            source to audioGroup(found, original) * 3 + subtitles
        }.sortedBy { it.second }.map { it.first }
    }
}
