package dev.pov.tv

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.TextView
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceSortOrder
import java.util.concurrent.atomic.AtomicReference

/** Synthetic source rows only. Does not resolve streams or change account/history data. */
class SourceSortingInstrumentation : Instrumentation() {
    private var capture = false
    private fun <T> onMain(task: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(task)) }
        return result.get().getOrThrow()
    }
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun pickDialogItem(index: Int) {
        waitForIdleSync(); SystemClock.sleep(200)
        repeat(4) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP) }
        repeat(index) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForIdleSync(); SystemClock.sleep(200)
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); capture = arguments?.getString("capture") == "true"; start() }
    override fun onStart() {
        val report = Bundle()
        val prefs = targetContext.getSharedPreferences("pov_sources", 0)
        val original = prefs.all
        var activity: MainActivity? = null
        var stage = "start"
        var passed = false
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val main = activity
            val theme = MainActivity::class.java.getDeclaredField("theme").apply { isAccessible = true }
            val preview = MainActivity::class.java.getDeclaredField("publicPreview").apply { isAccessible = true }
            val shell = MainActivity::class.java.getDeclaredMethod("shell").apply { isAccessible = true }
            val sources = MainActivity::class.java.getDeclaredMethod("sourceResults", PlaybackIdentity::class.java, List::class.java).apply { isAccessible = true }
            val settings = MainActivity::class.java.getDeclaredMethod("settings").apply { isAccessible = true }
            val originalTheme = onMain { theme.get(main) }
            val originalPreview = onMain { preview.getBoolean(main) }
            fun item(title: String, quality: Int?, size: Long?) = SourceItem(title, "Public preview", directUrl = "https://example.invalid/$title", qualityHeight = quality, sizeBytes = size)
            val items = listOf(item("Example.Movie.1080p.BluRay.H264.mkv", 1080, 4000000000), item("Example.Movie.2160p.DV.HDR10Plus.BluRay.REMUX.HEVC.mkv", 2160, 10000000000),
                item("Example Movie · Unknown metadata", null, null), item("Example.Movie.2160p.DV.WEB-DL.HEVC.mkv", 2160, 2500000000),
                item("Example.Movie.1080p.WEB-DL.H264.mkv", 1080, 1000000000), item("Example.Movie.720p.WEBRip.H264.mkv", 720, 700000000))
            val identity = PlaybackIdentity("tt1234567", "movie", "Source sorting preview")
            fun render() = onMain { sources.invoke(main, identity, items) }
            fun rows() = onMain { views(main.window.decorView).filterIsInstance<TvChoiceRow>() }
            try {
                for (spec in ThemeSpec.entries) {
                    onMain { preview.setBoolean(main, true); theme.set(main, spec); shell.invoke(main) }
                    for (quality in listOf(true, false)) for (size in listOf(true, false)) {
                        stage = "sort_${spec.name}_${quality}_$size"
                        val order = SourceSortOrder(quality, size)
                        onMain { SourceSortPreferences(main).order = order }
                        render(); waitForIdleSync()
                        val cards = rows()
                        check(cards.size == items.size)
                        val expected = order.sorted(items)
                        onMain {
                            check(cards.map { it.tag } == expected.map(TvChoiceKeys::source))
                            cards.zip(expected).forEach { (card, item) ->
                                check(card.contentDescription.toString().contains(dev.pov.tv.data.SourceMetadata.sizeLabel(item.sizeBytes)))
                                check(card.isFocusable && card.isClickable)
                                val badges = views(card).filterIsInstance<TextView>().filter { it.tag?.toString()?.startsWith("source-badge:") == true }
                                check(badges.map { it.text.toString() } == SourceBadges.from(item).map { it.label })
                                check(badges.all { it.typeface.isBold && !it.isFocusable && !it.isClickable })
                            }
                            check(cards[0].requestFocus())
                            check(TvChoiceList.nextFrom(cards[0], View.FOCUS_DOWN) === cards[1])
                            check(TvChoiceList.nextFrom(cards[1], View.FOCUS_UP) === cards[0])
                        }
                    }
                }
                report.putInt("theme_sort_combinations_passed", ThemeSpec.entries.size * 4)
                stage = "badge_row_dpad_navigation"
                val targets = rows()
                onMain { targets[0].requestFocus() }
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); waitForIdleSync()
                check(onMain { main.currentFocus === targets[1] })
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); waitForIdleSync()
                check(onMain { main.currentFocus === targets[0] })
                report.putBoolean("badge_rows_navigate_by_dpad", true)
                stage = "results_sort_dialog"
                onMain { SourceSortPreferences(main).order = SourceSortOrder() }
                render(); waitForIdleSync()
                onMain { views(main.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith("Sort ·") }.performClick() }
                stage = "results_size_category"
                pickDialogItem(2)
                stage = "results_largest_option"
                pickDialogItem(1)
                stage = "results_saved_order"
                check(onMain { SourceSortPreferences(main).order } == SourceSortOrder(true, false))
                stage = "results_rerendered_order"
                check(rows().map { it.tag } == SourceSortOrder(true, false).sorted(items).map(TvChoiceKeys::source))
                stage = "settings_sort_dialog"
                onMain { settings.invoke(main) }; waitForIdleSync()
                onMain { views(main.window.decorView).first { it.isClickable && (it.contentDescription ?: "").startsWith("Source sorting ·") }.performClick() }
                pickDialogItem(1)
                pickDialogItem(1)
                check(onMain { SourceSortPreferences(main).order } == SourceSortOrder(false, false))
                report.putBoolean("settings_and_results_dialogs_persist_and_apply", true)
                if (capture) {
                    stage = "public_badge_preview"
                    onMain {
                        theme.set(main, originalTheme); shell.invoke(main)
                        SourceSortPreferences(main).order = SourceSortOrder()
                        sources.invoke(main, PlaybackIdentity("tt1234567", "movie", "Source badge preview"),
                            listOf(items[3], items[4], items[5]))
                    }
                    waitForIdleSync(); SystemClock.sleep(800)
                    val screenshot = java.io.File(checkNotNull(targetContext.externalCacheDir), "pov-source-badges-preview.png")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("screencap -p")).use { input ->
                        screenshot.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(screenshot.length() > 0)
                    report.putBoolean("public_badge_preview_captured", true)
                }
                passed = true
            } finally { onMain { theme.set(main, originalTheme); preview.setBoolean(main, originalPreview) } }
        } catch (error: Throwable) { report.putString("error_type", error.javaClass.simpleName); report.putString("stage", stage) }
        finally {
            val edit = prefs.edit()
            for (key in listOf("quality_descending", "size_ascending")) {
                val value = original[key]
                if (value is Boolean) edit.putBoolean(key, value) else edit.remove(key)
            }
            edit.commit()
            onMain { activity?.finish() }
            report.putBoolean("passed", passed)
            finish(if (passed) -1 else 0, report)
        }
    }
}
