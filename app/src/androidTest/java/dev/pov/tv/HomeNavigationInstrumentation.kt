package dev.pov.tv

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Native navigation and real public playback; all tracking writes use an in-memory fake. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class HomeNavigationInstrumentation : Instrumentation() {
    private var liveDiscovery = false
    private fun <T> main(task: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(task)) }; return result.get().getOrThrow()
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; SystemClock.sleep(100) }
        error("Native UI did not reach the expected state")
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); liveDiscovery = arguments?.getString("live_discovery") == "true"; start() }
    override fun onStart() {
        val report = Bundle(); var passed = false; var stage = "setup"; var app: MainActivity? = null
        val store = SecureStore(targetContext)
        val encryptedKeys = listOf("playback.last_links", "wetrakr.home_liked_opt_in")
        val savedKeys = encryptedKeys.associateWith { store.has(it) to store.get(it) }
        val preferences = targetContext.getSharedPreferences("playback", 0)
        val savedPlayback = preferences.all.toMap()
        val trackerField = InAppPlaybackSession::class.java.getDeclaredField("weTrakrApi").apply { isAccessible = true }
        var previousTracker: Any? = null
        try {
            app = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = app
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
            fun body() = field("body").get(activity) as ViewGroup
            fun texts() = views(body()).filterIsInstance<TextView>()
            fun settled() = main { @Suppress("UNCHECKED_CAST") val loads = field("pendingLoads").get(activity) as Map<Int, Int>; loads[field("generation").getInt(activity)].orZero() == 0 }
            fun children() = (0 until body().childCount).map(body()::getChildAt)
            val catalogs = AtomicInteger(); val sources = AtomicInteger(); val removals = AtomicInteger()
            val movie = MediaItemData("tt1254207", "movie", "Big Buck Bunny", "2008")
            val identity = PlaybackIdentity(movie.imdbId, movie.type, movie.title)
            val credentials = mutableMapOf("client_id" to "fixture-public-navigation", "access_token" to "fixture-access", "refresh_token" to "fixture-refresh",
                "expires_at" to (System.currentTimeMillis() + 600000).toString(), "account_revision" to "fixture-public-navigation", "scrobbling" to "false")
            val tracker = WeTrakrApi(object : WeTrakrCredentials {
                override fun get(key: String) = credentials[key].orEmpty()
                override fun put(values: Map<String, String>) { credentials.putAll(values) }
            }, { path, method, _, _ ->
                when {
                    path.startsWith("/discover/") -> error("WeTrakr discovery must not be requested")
                    path == "/scrobble/playing" && method == "DELETE" -> { removals.incrementAndGet(); """{"action":"cancel"}""" }
                    path.startsWith("/sync/tracking/playing/movies") -> """[{"type":"movie","title":"Big Buck Bunny","ids":{"imdb":"tt1254207"},"playback":{"progress_percent":20}}]"""
                    else -> { check(method == "GET"); "[]" }
                }
            })
            val catalog = CatalogApi { address ->
                catalogs.incrementAndGet()
                if (address.contains("/meta/")) JSONObject("""{"meta":{"id":"tt1254207","name":"Big Buck Bunny","type":"movie","year":"2008","description":"A public movie used for native TV navigation checks."}}""")
                else {
                    val year = java.time.Year.now(java.time.ZoneOffset.UTC).value.toString()
                    val data = JSONObject("""{"metas":[{"id":"tt1254207","name":"Big Buck Bunny","year":"2008"},{"id":"tt1234567","name":"Public sample","year":"2025"},{"id":"tt7654321","name":"Third public sample","year":"2025"}]}""")
                    if (address.contains("/year/")) for (i in 0 until data.getJSONArray("metas").length()) data.getJSONArray("metas").getJSONObject(i).put("year", year)
                    data
                }
            }
            val sourceApi = SourceResolver({ "https://provider.example.invalid" }, object : SourceTorBox {
                override fun cached(hashes: List<String>): Set<String> = emptySet()
                override fun addMagnet(hash: String): Long = error("No cloud writes allowed")
                override fun folder(kind: String, id: Long): CloudFolder = error("No cloud request allowed")
                override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = error("No cloud request allowed")
            }, { sources.incrementAndGet(); JSONObject("""{"streams":[{"title":"Public sample 1080p","url":"https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd"}]}""") })
            main {
                previousTracker = trackerField.get(InAppPlaybackSession); trackerField.set(InAppPlaybackSession, tracker)
                store.remove("wetrakr.home_liked_opt_in")
                field("weTrakrApi").set(activity, tracker); field("catalog").set(activity, catalog); field("resolver").set(activity, sourceApi)
                field("publicPreview").setBoolean(activity, false); field("theme").set(activity, ThemeSpec.FLIX); call("shell"); call("home")
            }
            val shelfNames = listOf("New Movies", "New Shows", "Featured Movies", "Featured Shows", "Popular Movies", "Popular Shows")
            fun awaitHome() = await { settled() && main { shelfNames.all { label -> views(body()).any { it.tag == "shelf:$label" } } } }
            awaitHome()
            stage = "public_catalogs_without_tracker"
            main { credentials["access_token"] = ""; call("home") }
            awaitHome()
            main {
                check(children().mapNotNull { (it.tag as? String)?.removePrefix("shelf:")?.takeIf { label -> label in shelfNames } } == shelfNames)
                check(views(body()).none { it.tag == "home-trending" })
                credentials["access_token"] = "fixture-access"; call("home")
            }
            awaitHome()
            await { main { views(body()).any { it.getTag(R.id.pov_continue_identity) == "tt1254207:0:0" } } }
            report.putBoolean("six_cinemeta_shelves_without_tracking_account", true)
            stage = "shelf_focus_regression"
            fun step(key: Int) = main {
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
            val firstHeroControl = main { views(views(body()).first { it.tag == "featured-hero" }).first { it.isFocusable } }
            await { main { activity.currentFocus === firstHeroControl } }
            report.putBoolean("initial_home_focus_starts_at_hero", true)
            step(KeyEvent.KEYCODE_DPAD_DOWN)
            check(main { activity.currentFocus?.getTag(R.id.pov_continue_identity) == "tt1254207:0:0" })
            step(KeyEvent.KEYCODE_DPAD_DOWN)
            check(main { activity.currentFocus?.tag == "shelf:New Movies" })
            for ((index, label) in shelfNames.withIndex()) {
                val first = main { views(body()).first { (it.tag as? String)?.startsWith("row:$label:") == true } }
                step(KeyEvent.KEYCODE_DPAD_DOWN); check(main { activity.currentFocus === first })
                step(KeyEvent.KEYCODE_DPAD_UP); check(main { activity.currentFocus?.tag == "shelf:$label" })
                step(KeyEvent.KEYCODE_DPAD_DOWN); check(main { activity.currentFocus === first })
                if (index < shelfNames.lastIndex) {
                    step(KeyEvent.KEYCODE_DPAD_DOWN); check(main { activity.currentFocus?.tag == "shelf:${shelfNames[index + 1]}" })
                }
            }
            report.putBoolean("headers_never_skipped_and_rows_start_first", true)
            stage = "grid_and_back"
            val homeChildren = main { children() }; val homeGeneration = main { field("generation").getInt(activity) }
            val before = catalogs.get()
            main { texts().first { it.text.startsWith("Popular Movies") }.performClick() }
            await { main { texts().any { it.text == "Popular Movies" } } }
            main { activity.onBackPressed() }
            await { main { children() == homeChildren && field("generation").getInt(activity) == homeGeneration } }
            check(catalogs.get() == before); report.putBoolean("grid_back_retains_home", true)
            stage = "hero_scroll"
            main {
                val scroll = field("scroller").get(activity) as ScrollView
                scroll.scrollTo(0, 500)
                val hero = views(body()).first { it.tag == "featured-hero" }
                views(hero).first { it.isFocusable }.requestFocus()
                (field("focusController").get(activity) as TvFocusController).revealCurrent()
                check(scroll.scrollY == 0)
            }; report.putBoolean("hero_returns_to_top", true)
            fun continueCard() = views(body()).first { it.getTag(R.id.pov_continue_identity) == "tt1254207:0:0" }
            fun dialogItem(label: String) {
                val end = SystemClock.elapsedRealtime() + 10000
                while (SystemClock.elapsedRealtime() < end) {
                    val nodes = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
                    val node = nodes.firstOrNull { it.text?.toString() == label }
                    if (node != null) {
                        val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                        val down = SystemClock.uptimeMillis()
                        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                            val touch = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                            touch.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            check(uiAutomation.injectInputEvent(touch, true)); touch.recycle()
                        }
                        return
                    }
                    SystemClock.sleep(100)
                }; error("Expected menu action missing")
            }
            stage = "open_media"
            main { continueCard().performLongClick() }; dialogItem("Open Media")
            await { settled() && main { texts().any { it.text.contains("Play") } } }
            check(catalogs.get() == before + 1)
            main { activity.onBackPressed() }
            await { main { children() == homeChildren } }
            check(catalogs.get() == before + 1); report.putBoolean("open_media_details_and_back", true)
            stage = "remove_in_place"
            val card = main { continueCard() }
            main { card.performLongClick() }; dialogItem("Remove from Continue Watching")
            await { settled() && main { card.visibility == View.GONE } }
            check(main { children() == homeChildren && field("generation").getInt(activity) == homeGeneration })
            check(removals.get() == 1); report.putBoolean("remove_without_home_refresh", true)
            stage = "keyboard_search"
            main {
                MainActivity::class.java.getDeclaredMethod("search", String::class.java).apply { isAccessible = true }.invoke(activity, "Big Buck Bunny")
                val dialog = field("searchDialog").get(activity) as android.app.Dialog
                val edit = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                edit.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
            }
            await { settled() && main { texts().any { it.text.toString().startsWith("Results for") } } }
            report.putBoolean("keyboard_search_submits", true)
            stage = "hardware_enter"
            val searchGeneration = main { field("generation").getInt(activity) }
            main {
                MainActivity::class.java.getDeclaredMethod("search", String::class.java).apply { isAccessible = true }.invoke(activity, "Big Buck Bunny")
                val dialog = field("searchDialog").get(activity) as android.app.Dialog
                val edit = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                check(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Voice") })
                edit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                edit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            await { settled() && main { field("generation").getInt(activity) != searchGeneration && texts().any { it.text.toString().startsWith("Results for") } } }
            report.putBoolean("hardware_enter_submits", true)
            stage = "voice_result"
            main {
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                    .invoke(activity, 701, android.app.Activity.RESULT_OK, Intent().putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS, arrayListOf("Public voice sample")))
            }
            await { settled() && main { texts().any { it.text.toString().contains("Public voice sample") } } }
            report.putBoolean("voice_result_searches", true)
            stage = "cached_playback"
            val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
            val links = LastPlayedLinks(store)
            links.remove(identity)
            val firstPlayer = startActivitySync(Intent(targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
                .putExtra("identity", identity.toJson().toString()).putExtra("key", "public-navigation-check")
                .putExtra("title", "Public cache check").putExtra("playWhenReady", false).putExtra("playback_account", "direct")) as PlayerActivity
            await { main { InAppPlaybackSession.playerOrNull?.playbackState == androidx.media3.common.Player.STATE_READY } && links.find(identity) != null }
            // Finish only the fullscreen host: the same session can now be browsed in the mini player.
            main { InAppPlaybackSession.playerOrNull?.volume = 0f; firstPlayer.finish() }
            SystemClock.sleep(500)
            check(main { InAppPlaybackSession.hasActive() })
            check(links.find(identity)?.key == "public-navigation-check")
            main { InAppPlaybackSession.stop() }
            SystemClock.sleep(500); report.putBoolean("successful_session_caches_link_for_mini_player", true)
            fun resume() = main { MainActivity::class.java.getDeclaredMethod("continuePlayback", PlaybackIdentity::class.java).apply { isAccessible = true }.invoke(activity, identity) }
            resume()
            val player = waitForMonitorWithTimeout(monitor, 15000) as PlayerActivity
            await { main { InAppPlaybackSession.playerOrNull?.playbackState == androidx.media3.common.Player.STATE_READY } }
            check(sources.get() == 0)
            main { InAppPlaybackSession.playerOrNull?.volume = 0f; player.finish(); InAppPlaybackSession.stop() }
            SystemClock.sleep(1000); report.putBoolean("cached_link_bypasses_search", true)
            stage = "expired_link_fallback"
            links.save(identity, "https://storage.googleapis.com/shaka-demo-assets/pov-no-such-public-test.mpd", "public-navigation-check", null)
            resume()
            await { sources.get() == 1 && settled() && main { texts().any { it.text.toString().contains("Public sample 1080p") } } }
            check(links.find(identity) == null); removeMonitor(monitor)
            report.putBoolean("expired_link_searches_fresh", true)
            stage = "all_tabs_return_to_top"
            main { field("anime").set(activity, AnimeCatalogApi({ JSONObject("""{"data":[]}""") }, { _, _, _ -> null })) }
            for (tab in listOf("movie", "series", "anime", "library", "wetrakr", "settings")) {
                main {
                    when (tab) {
                        "movie", "series" -> MainActivity::class.java.getDeclaredMethod("catalogPage", String::class.java, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, tab, "", 0)
                        "anime" -> MainActivity::class.java.getDeclaredMethod("animePage", String::class.java, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, "series", "", 0)
                        "library" -> call("selectedCloud")
                        "wetrakr" -> call("weTrakrPage")
                        else -> call("settings")
                    }
                }
                await { settled() }
                main {
                    val focus = field("focusController").get(activity) as TvFocusController
                    focus.reset(); check(focus.first())
                    repeat(80) { focus.move(View.FOCUS_DOWN) }
                    repeat(80) { focus.move(View.FOCUS_UP) }
                    check((field("scroller").get(activity) as ScrollView).scrollY == 0)
                }
            }
            report.putBoolean("movies_shows_anime_library_wetrakr_settings_return_to_top", true)
            stage = "all_theme_shelf_headers"
            for (theme in ThemeSpec.entries) {
                main { field("theme").set(activity, theme); call("shell"); call("home") }
                awaitHome()
                main {
                    views(body()).first { it.tag == "shelf:New Movies" }.requestFocus()
                    val focus = field("focusController").get(activity) as TvFocusController
                    val first = views(body()).first { (it.tag as? String)?.startsWith("row:New Movies:") == true }
                    focus.move(View.FOCUS_DOWN); check(activity.currentFocus === first)
                    focus.move(View.FOCUS_UP); check(activity.currentFocus?.tag == "shelf:New Movies")
                    focus.move(View.FOCUS_DOWN); focus.move(View.FOCUS_DOWN); check(activity.currentFocus?.tag == "shelf:New Shows")
                }
            }
            report.putBoolean("all_five_themes_header_and_first_poster_navigation", true)
            if (liveDiscovery) {
                stage = "live_read_only_discovery"
                val shelves = CatalogApi().homeShelves()
                check(shelves.map { it.title } == shelfNames)
                check(shelves.none { it.unavailable } && shelves.all { it.items.isNotEmpty() })
                shelves.forEachIndexed { index, shelf -> report.putInt("live_cinemeta_shelf_${index + 1}", shelf.items.size) }
                report.putBoolean("live_cinemeta_six_catalogs", true)
            }
            passed = true
        } catch (error: Throwable) { report.putString("stage", stage); report.putString("error_type", error.javaClass.simpleName); report.putString("failure_at", error.stackTrace.firstOrNull { it.className.contains("HomeNavigation") }?.let { "${it.methodName}:${it.lineNumber}" }) }
        finally {
            main { InAppPlaybackSession.stop(); previousTracker?.let { trackerField.set(InAppPlaybackSession, it) }; app?.finish() }
            savedKeys.forEach { (key, saved) -> if (saved.first) store.put(key, saved.second) else store.remove(key) }
            val edit = preferences.edit().clear()
            savedPlayback.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value); is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
            } }; check(edit.commit())
            report.putBoolean("passed", passed); report.putBoolean("real_tracking_writes", false); finish(if (passed) -1 else 0, report)
        }
    }
    private fun Int?.orZero() = this ?: 0
}
