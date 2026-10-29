package dev.pov.tv

import android.app.AlertDialog
import android.content.Context
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceLanguageOrder
import dev.pov.tv.data.SourceSortOrder

internal class SourceSortPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("pov_sources", Context.MODE_PRIVATE)
    var order: SourceSortOrder
        get() = SourceSortOrder(prefs.getBoolean("quality_descending", true), prefs.getBoolean("size_ascending", true))
        set(value) { prefs.edit().putBoolean("quality_descending", value.qualityDescending).putBoolean("size_ascending", value.sizeAscending).apply() }
    var languageFirst: Boolean
        get() = prefs.getBoolean("language_first", true)
        set(value) { prefs.edit().putBoolean("language_first", value).apply() }
    /** The audio and subtitle languages from Settings, or null when language ranking is off or both are automatic. */
    val languages: SourceLanguageOrder?
        get() = if (!languageFirst) null
            else AudioPreferences(context).let { audio ->
                SourceLanguageOrder(audio.language, SubtitlePreferences(context).language, if (audio.englishFallback) "en" else "")
            }.takeIf { it.active }
    private val languageLabel: String?
        get() = languages?.let { order -> if (order.audio.isNotEmpty()) "${languageName(order.audio)} first" else "${languageName(order.subtitle)} subtitles first" }
    val label: String get() = listOfNotNull(languageLabel, if (order.qualityDescending) "Highest quality" else "Lowest quality",
        if (order.sizeAscending) "Smallest first" else "Largest first").joinToString(" · ")

    fun sorted(sources: List<SourceItem>): List<SourceItem> = languages?.sorted(sources, order) ?: order.sorted(sources)

    companion object {
        private fun languageName(code: String) = SubtitlePreferences.LANGUAGES.firstOrNull { it.first == code }?.second ?: code

        fun show(context: Context, changed: () -> Unit) {
            val preferences = SourceSortPreferences(context)
            val audio = AudioPreferences(context).language; val subtitle = SubtitlePreferences(context).language.takeUnless { it == "off" }.orEmpty()
            val languageSummary = listOfNotNull(audio.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} audio" },
                subtitle.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} subtitles" }).joinToString(", ").ifEmpty { "set an audio or subtitle language in Settings" }
            AlertDialog.Builder(context).setTitle("Source sorting")
                .setItems(arrayOf("Language · ${if (preferences.languageFirst) "Preferred first ($languageSummary)" else "Off"}",
                    "Quality · ${if (preferences.order.qualityDescending) "Highest first" else "Lowest first"}",
                    "File size within each quality · ${if (preferences.order.sizeAscending) "Smallest first" else "Largest first"}")) { _, choice ->
                    if (choice == 0) {
                        AlertDialog.Builder(context).setTitle("Language order")
                            .setSingleChoiceItems(arrayOf("Preferred language first · $languageSummary, then multi-audio, then the rest",
                                "Off · quality and size only"), if (preferences.languageFirst) 0 else 1) { dialog, index ->
                                preferences.languageFirst = index == 0; dialog.dismiss(); changed()
                            }.setNegativeButton("Cancel", null).show()
                        return@setItems
                    }
                    val quality = choice == 1
                    val selected = if (quality) !preferences.order.qualityDescending else !preferences.order.sizeAscending
                    AlertDialog.Builder(context).setTitle(if (quality) "Quality order" else "File size order")
                        .setSingleChoiceItems(if (quality) arrayOf("Highest first · 4K, 1080p, 720p…", "Lowest first · 720p, 1080p, 4K…")
                            else arrayOf("Smallest first", "Largest first"), if (selected) 1 else 0) { dialog, index ->
                            val current = preferences.order
                            preferences.order = if (quality) current.copy(qualityDescending = index == 0) else current.copy(sizeAscending = index == 0)
                            dialog.dismiss(); changed()
                        }.setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Close", null).show()
        }
    }
}
