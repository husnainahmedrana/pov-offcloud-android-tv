package dev.pov.tv

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.pov.tv.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Offline protocol fixtures in the real TV UI. No account tokens are read or replaced. */
class WeTrakrInstrumentation : Instrumentation() {
    private fun <T> onMain(task: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(task)) }
        return result.get().getOrThrow()
    }
    private fun views(root: View): List<View> = buildList { add(root); if (root is ViewGroup) for (i in 0 until root.childCount) addAll(views(root.getChildAt(i))) }
    private var liveReadOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); liveReadOnly = arguments?.getString("live_read_only") == "true"; start() }
    private fun verifyLiveReadOnly() {
        val report = Bundle(); var stage = "connected"; var passed = false; var activity: MainActivity? = null
        try {
            val api = WeTrakrApi(SecureStore(targetContext)); check(api.connected())
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            stage = "continue_watching"; api.continueWatching()
            stage = "watchlists"; api.watchlist("movie"); api.watchlist("series")
            stage = "up_next"; api.nextEpisodes()
            stage = "liked_lists"; val liked = api.likedLists()
            liked.take(1).forEach { (id, _) -> stage = "liked_list_contents"; api.likedListPreview(id) }
            stage = "home_remote_row"
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < deadline && !onMain { views(activity!!.window.decorView).any { it.tag == "wetrakr-home-continue" } }) SystemClock.sleep(100)
            check(onMain { views(activity!!.window.decorView).any { it.tag == "wetrakr-home-continue" } })
            report.putBoolean("connected_account_preserved", true)
            report.putBoolean("live_library_and_likes_read", true)
            report.putBoolean("home_uses_wetrakr", true)
            if (SecureStore(targetContext).get("wetrakr.home_liked_opt_in") != "true") {
                check(onMain { views(activity!!.window.decorView).none { it.tag == "wetrakr-home-liked" } })
                report.putBoolean("home_liked_rows_disabled_by_default", true)
            }
            passed = true
        } catch (error: Throwable) {
            report.putString("stage", stage); report.putString("error_type", error.javaClass.simpleName)
            if (error is ApiException) report.putInt("http_status", error.code)
        } finally {
            onMain { activity?.finish() }; report.putBoolean("passed", passed); finish(if (passed) -1 else 0, report)
        }
    }
    override fun onStart() {
        if (liveReadOnly) { verifyLiveReadOnly(); return }
        val report = Bundle(); var passed = false; var stage = "start"; var activity: MainActivity? = null
        val store = SecureStore(targetContext)
        val hadLiked = store.has("wetrakr.home_liked_opt_in"); val originalLiked = store.get("wetrakr.home_liked_opt_in")
        val playbackPrefs = targetContext.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
        val originalPlayback = playbackPrefs.all.toMap()
        val likedRequests = java.util.concurrent.atomic.AtomicInteger(0)
        val deleted = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val memory = mutableMapOf("client_id" to "fixture-client-1234567890", "access_token" to "fixture-access", "refresh_token" to "fixture-refresh",
                "expires_at" to (System.currentTimeMillis() + 600000).toString(), "account_revision" to "fixture-tv-account")
            fun media(type: String, id: String, title: String) = JSONObject().put("type", type).put("title", title).put("ids", JSONObject().put("imdb", id))
            val movie = media("movie", "tt1234567", "Example Movie")
            val show = media("show", "tt7654321", "Example Show")
            val client = WeTrakrApi(object : WeTrakrCredentials {
                override fun get(key: String) = memory[key].orEmpty()
                override fun put(values: Map<String, String>) { memory.putAll(values) }
            }, { path, _, _, _ -> when {
                path == "/oauth/device/code" -> """{"device_code":"fixture-device-code","user_code":"ABC123","verification_url":"https://wetrakr.com/activate","expires_in":600,"interval":5}"""
                path == "/oauth/device/token" -> throw ApiException(400, "Pending", "authorization_pending")
                path.startsWith("/sync/tracking/playing/movies") -> if (deleted.get()) "[]" else JSONArray().put(JSONObject(movie.toString()).put("playback", JSONObject().put("progress_percent", 42))).toString()
                path.startsWith("/sync/tracking/playing/episodes") -> "[]"
                path.startsWith("/sync/tracking/planning/movies") -> JSONArray().put(movie).toString()
                path.startsWith("/sync/tracking/planning/shows") -> JSONArray().put(show).toString()
                path.startsWith("/sync/tracking/watching/shows") -> JSONArray().put(JSONObject(show.toString()).put("next_watching_episode", JSONObject().put("season_number", 1).put("number", 3))).toString()
                path == "/scrobble/playing" -> { deleted.set(true); """{"action":"cancel"}""" }
                path.startsWith("/sync/likes/lists") -> { likedRequests.incrementAndGet(); """[{"list":{"id":125,"name":"Example liked list"}}]""" }
                path.startsWith("/lists/125/items/all") -> JSONArray().put(movie).toString()
                path.startsWith("/sync/lists/fixture-list/items") -> JSONArray().put(movie).toString()
                path.startsWith("/sync/lists") -> """[{"id":"fixture-list","name":"Example list"}]"""
                else -> error("Unexpected offline fixture request")
            } })
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val main = activity
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun method(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            val theme = field("theme"); val preview = field("publicPreview"); val weField = field("weTrakrApi")
            val previousTheme = onMain { theme.get(main) }; val previousPreview = onMain { preview.getBoolean(main) }; val previousClient = onMain { weField.get(main) }
            fun captions() = onMain { views(main.window.decorView).filterIsInstance<TextView>().map { it.text.toString() } }
            fun await(test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (SystemClock.elapsedRealtime() < deadline) { if (test()) return; SystemClock.sleep(100) }
                error("Offline TV check timed out")
            }
            try {
                store.remove("wetrakr.home_liked_opt_in")
                onMain { weField.set(main, client); preview.setBoolean(main, true) }
                stage = "home_likes_default_off"
                onMain {
                    val page = MainActivity::class.java.getDeclaredMethod("page", String::class.java, String::class.java).apply { isAccessible = true }
                    val id = page.invoke(main, "Fixture Home", "") as Int
                    MainActivity::class.java.getDeclaredMethod("homeLikedLists", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(main, id)
                }
                check(likedRequests.get() == 0)
                check(onMain { views(main.window.decorView).none { it.tag == "wetrakr-home-liked" } })
                onMain { method("settings").invoke(main) }
                check(onMain { views(main.window.decorView).any { it.contentDescription?.toString() == "WeTrakr liked lists on Home · Off" } })
                report.putBoolean("home_likes_default_off_without_api_requests", true)
                for (spec in ThemeSpec.entries) {
                    stage = "tracking_page_${spec.name}"
                    onMain { theme.set(main, spec); method("shell").invoke(main); method("weTrakrPage").invoke(main) }
                    await { captions().contains("Movie watchlist") && captions().contains("TV watchlist") && captions().contains("Up next") }
                    check(captions().contains("My WeTrakr")); check(captions().contains("Continue watching"))
                    stage = "liked_shelf_${spec.name}"
                    await { captions().any { it.contains("Example liked list") } }
                    stage = "thumbnail_focus_${spec.name}"
                    await { onMain { main.window.decorView.findFocus()?.tag.toString().startsWith("row:Movie watchlist:") } }
                    stage = "shelf_order_${spec.name}"
                    onMain {
                        val texts = views(main.window.decorView).filterIsInstance<TextView>()
                        val watchlist = texts.first { it.text.toString() == "Movie watchlist" }
                        val progress = texts.first { it.text.toString() == "Continue watching" }
                        val positions = IntArray(2); watchlist.getLocationOnScreen(positions); val firstY = positions[1]
                        progress.getLocationOnScreen(positions); check(firstY < positions[1])
                        check(views(main.window.decorView).any { it.tag == "wetrakr-section-liked" })
                    }
                    if (spec == ThemeSpec.FLIX) {
                        SystemClock.sleep(300)
                        val bitmap = uiAutomation.takeScreenshot()
                        java.io.File(targetContext.externalCacheDir, "wetrakr-library-FLIX.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    store.put("wetrakr.home_liked_opt_in", "true")
                    onMain {
                        @Suppress("UNCHECKED_CAST") val navigation = field("navigationViews").get(main) as Map<String, View>
                        val settings = checkNotNull(navigation["settings"]); val bounds = Rect()
                        check(settings.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0)
                        val mark = views(checkNotNull(navigation["wetrakr"])).filterIsInstance<android.widget.ImageView>().single()
                        val markBounds = Rect(); check(mark.getGlobalVisibleRect(markBounds))
                        check(markBounds.width() == mark.width && markBounds.height() == mark.height)
                    }
                    stage = "home_rows_${spec.name}"
                    onMain {
                        val page = MainActivity::class.java.getDeclaredMethod("page", String::class.java, String::class.java).apply { isAccessible = true }
                        val id = page.invoke(main, "Fixture Home", "") as Int
                        MainActivity::class.java.getDeclaredMethod("homeContinueWatching", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(main, id)
                        MainActivity::class.java.getDeclaredMethod("homeLikedLists", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(main, id)
                    }
                    await { captions().contains("Continue Watching · WeTrakr") && captions().contains("Liked lists · WeTrakr") && captions().any { it.contains("Example liked list") } && captions().contains("Example Movie") }
                    if (spec == ThemeSpec.FLIX) {
                        SystemClock.sleep(300)
                        val bitmap = uiAutomation.takeScreenshot()
                        java.io.File(targetContext.externalCacheDir, "wetrakr-home-${spec.name}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    onMain { method("settings").invoke(main) }
                    check(captions().none { it.contains("Trakt") })
                    val scrobbling = onMain { views(main.window.decorView).first { it.contentDescription?.toString() == "WeTrakr scrobbling · On" } }
                    onMain { scrobbling.performClick() }; check(!client.scrobblingEnabled())
                    onMain { views(main.window.decorView).first { it.contentDescription?.toString() == "WeTrakr scrobbling · Off" }.performClick() }; check(client.scrobblingEnabled())
                    onMain { views(main.window.decorView).first { it.contentDescription?.toString() == "WeTrakr liked lists on Home · On" }.performClick() }
                    check(store.get("wetrakr.home_liked_opt_in") == "false")
                    onMain { views(main.window.decorView).first { it.contentDescription?.toString() == "WeTrakr liked lists on Home · Off" }.performClick() }
                    check(store.get("wetrakr.home_liked_opt_in") == "true")
                    onMain { method("weTrakrLists").invoke(main) }
                    await { captions().contains("Example list") }
                }
                report.putInt("themes_with_tracking_lists_and_visible_settings", ThemeSpec.entries.size)
                report.putBoolean("remote_home_rows_and_settings_toggles", true)
                report.putBoolean("wetrakr_opens_on_thumbnail_shelves_and_includes_liked_lists", true)
                stage = "remove_continue_watching"
                onMain { method("weTrakrPage").invoke(main) }
                await { captions().any { it.contains("Example Movie") && it.contains("42%") } }
                onMain { views(main.window.decorView).filterIsInstance<TextView>().first { it.text.toString().contains("Example Movie") && it.text.toString().contains("42%") }.performLongClick() }
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await { deleted.get() }
                await { !captions().any { it.contains("Example Movie") && it.contains("42%") } }
                check(PlaybackStore(targetContext).isHidden(identity = PlaybackIdentity("tt1234567", "movie", "Example Movie")))
                report.putBoolean("remote_remove_and_local_hide", true)
                stage = "device_login_dialog"
                onMain { method("startWeTrakr").invoke(main) }
                // The activation code is synthetic; inspect only our public test dialog.
                await { onMain {
                    val dialog = field("authorization").get(main) as? DeviceAuthorizationDialog
                    dialog != null && (DeviceAuthorizationDialog::class.java.getDeclaredField("code").apply { isAccessible = true }.get(dialog) as TextView).text.toString() == "ABC123"
                } }
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync()
                check(memory["access_token"] == "fixture-access")
                report.putBoolean("device_code_dialog_and_cancel_work", true)
                passed = true
            } finally { onMain { weField.set(main, previousClient); theme.set(main, previousTheme); preview.setBoolean(main, previousPreview) } }
        } catch (error: Throwable) { report.putString("error_type", error.javaClass.simpleName); report.putString("stage", stage) }
        finally {
            if (hadLiked) store.put("wetrakr.home_liked_opt_in", originalLiked) else store.remove("wetrakr.home_liked_opt_in")
            val editor = playbackPrefs.edit().clear()
            originalPlayback.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
            } }
            check(editor.commit())
            onMain { activity?.finish() }
            report.putBoolean("passed", passed); finish(if (passed) -1 else 0, report)
        }
    }
}
