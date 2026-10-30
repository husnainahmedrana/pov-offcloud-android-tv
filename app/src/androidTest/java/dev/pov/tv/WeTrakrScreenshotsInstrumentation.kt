package dev.pov.tv

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Public screenshot fixtures: no real account data or API registration is included. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class WeTrakrScreenshotsInstrumentation : Instrumentation() {
    private fun <T> main(task: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(task)) }; return result.get().getOrThrow()
    }
    private fun views(root: View): List<View> = buildList { add(root); if (root is ViewGroup) for (i in 0 until root.childCount) addAll(views(root.getChildAt(i))) }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle(); var passed = false; var stage = "setup"; var activity: MainActivity? = null
        val backups = listOf("pov_appearance", "pov_subtitles").associateWith { targetContext.getSharedPreferences(it, 0).all.toMap() }
        val store = SecureStore(targetContext); val hadOptIn = store.has("wetrakr.home_liked_opt_in"); val originalOptIn = store.get("wetrakr.home_liked_opt_in")
        try {
            fun media(type: String, id: String, title: String, poster: String, backdrop: String) = JSONObject().put("type", type).put("title", title)
                .put("ids", JSONObject().put("imdb", id)).put("poster_path", poster).put("backdrop_path", backdrop)
            val knight = media("movie", "tt0468569", "The Dark Knight", "/qJ2tW6WMUDux911r6m7haRef0WH.jpg", "/9FE5eD92WfVCiivM9Pq9GVSrlWk.jpg")
            val inception = media("movie", "tt1375666", "Inception", "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg", "/s3TBrRGB1iav7gFOCNx3H31MoES.jpg")
            val breaking = media("show", "tt0903747", "Breaking Bad", "/3xnWaLQjelJDDF7LT1WBo6f4BRe.jpg", "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg")
            val movies = JSONArray().put(knight).put(inception); val shows = JSONArray().put(breaking)
            val credentials = mutableMapOf("client_id" to "fixture-client-public-demo", "access_token" to "fixture-access", "refresh_token" to "fixture-refresh",
                "expires_at" to (System.currentTimeMillis() + 600000).toString(), "account_revision" to "fixture-public-screenshots")
            val client = WeTrakrApi(object : WeTrakrCredentials {
                override fun get(key: String) = credentials[key].orEmpty()
                override fun put(values: Map<String, String>) { credentials.putAll(values) }
            }, { path, method, _, _ ->
                check(method == "GET")
                when {
                    path.startsWith("/sync/tracking/planning/movies") -> movies.toString()
                    path.startsWith("/sync/tracking/planning/shows") -> shows.toString()
                    path.startsWith("/sync/likes/lists") -> """[{"list":{"id":125,"name":"Sample favourites"}}]"""
                    path.startsWith("/lists/125/items/all") -> movies.toString()
                    path.startsWith("/sync/tracking/playing/movies") -> JSONArray().put(JSONObject(inception.toString()).put("playback", JSONObject().put("progress_percent", 42))).toString()
                    path.startsWith("/sync/tracking/playing/episodes") -> "[]"
                    path.startsWith("/sync/tracking/watching/shows") -> JSONArray().put(JSONObject(breaking.toString()).put("next_watching_episode", JSONObject().put("season_number", 1).put("number", 3))).toString()
                    else -> error("Unexpected public screenshot request")
                }
            })
            fun catalogMedia(item: JSONObject) = JSONObject().put("id", item.getJSONObject("ids").getString("imdb")).put("name", item.getString("title"))
                .put("poster", "https://image.tmdb.org/t/p/w500" + item.getString("poster_path"))
                .put("background", "https://image.tmdb.org/t/p/w1280" + item.getString("backdrop_path"))
            val catalog = CatalogApi { address -> JSONObject().put("metas", if (address.contains("/movie/")) JSONArray().put(catalogMedia(knight)).put(catalogMedia(inception)) else JSONArray().put(catalogMedia(breaking))) }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val app = activity
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(app)
            val names = listOf("theme", "publicPreview", "weTrakrApi", "catalog")
            val original = main { names.associateWith { field(it).get(app) } }
            fun await(test: () -> Boolean) {
                val until = SystemClock.elapsedRealtime() + 30000
                while (SystemClock.elapsedRealtime() < until) { if (test()) return; SystemClock.sleep(100) }
                error("Screenshot content not ready")
            }
            fun tagged(tag: String) = views(app.window.decorView).firstOrNull { it.tag == tag }
            fun capture(name: String) {
                await { main { views(app.window.decorView).filterIsInstance<ImageView>().count { it.isShown && it.drawable is BitmapDrawable } >= 3 } }
                SystemClock.sleep(1200)
                val bitmap = uiAutomation.takeScreenshot()
                File(targetContext.externalCacheDir, "public-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun scrollTo(view: View, offset: Int = 0) = main {
                val body = field("body").get(app) as ViewGroup
                val position = Rect(0, 0, view.width, view.height); body.offsetDescendantRectToMyCoords(view, position)
                (field("scroller").get(app) as ScrollView).scrollTo(0, (position.top - offset).coerceAtLeast(0))
            }
            try {
                store.remove("wetrakr.home_liked_opt_in")
                main { ThemePreferences(targetContext).select(ThemeSpec.FLIX) }
                main { field("theme").set(app, ThemeSpec.FLIX); field("publicPreview").set(app, false); field("weTrakrApi").set(app, client); field("catalog").set(app, catalog); call("shell"); call("weTrakrPage") }
                stage = "library"
                await { main { tagged("wetrakr-section-liked")?.let { views(it).filterIsInstance<TextView>().any { text -> text.text.contains("Sample favourites") } } == true } }
                capture("wetrakr-library")
                stage = "liked_lists"; scrollTo(main { checkNotNull(tagged("wetrakr-section-liked")) }, 18); capture("wetrakr-liked-lists")
                stage = "home"; main { call("home") }
                await { main { tagged("wetrakr-home-continue") != null } }
                capture("home")
                stage = "continue_watching"; scrollTo(main { checkNotNull(tagged("wetrakr-home-continue")) }, 15); capture("continue-watching")
                main { (field("scroller").get(app) as ScrollView).scrollTo(0, 0) }; capture("home")
                stage = "settings"; main { call("settings") }
                val toggle = main { views(app.window.decorView).filterIsInstance<TextView>().first { it.text.toString().startsWith("WeTrakr scrobbling") } }
                main { toggle.requestFocus() }; scrollTo(toggle, 90); SystemClock.sleep(1000)
                val bitmap = uiAutomation.takeScreenshot()
                File(targetContext.externalCacheDir, "public-wetrakr-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                fun snap(name: String) {
                    SystemClock.sleep(1200)
                    val shot = checkNotNull(uiAutomation.takeScreenshot())
                    File(targetContext.externalCacheDir, "public-$name.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
                stage = "appearance"; main { call("appearance") }; snap("appearance")
                stage = "settings_overview"; main { call("settings"); (field("scroller").get(app) as ScrollView).scrollTo(0, 0) }; snap("settings")
                stage = "priority"; main { call("debridPriority") }; snap("debrid-priority"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                stage = "language"; main { MainActivity::class.java.getDeclaredMethod("subtitleLanguage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(app, false) }; snap("subtitle-language"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                stage = "position"
                val positionDialog = main { SubtitlePositionPicker.show(app, liveVideo = false, changed = {}) }
                snap("subtitle-position"); main { positionDialog.dismiss() }
                stage = "sources"
                val sources = listOf(
                    SourceItem("Example.Movie.2160p.DV.HDR10Plus.BluRay.REMUX.HEVC.mkv", "Public preview", directUrl = "https://example.invalid/one", qualityHeight = 2160, sizeBytes = 10000000000L),
                    SourceItem("Example.Movie.2160p.DV.WEB-DL.HEVC.mkv", "Public preview", directUrl = "https://example.invalid/two", qualityHeight = 2160, sizeBytes = 2500000000L),
                    SourceItem("Example.Movie.1080p.BluRay.H264.mkv", "Public preview", directUrl = "https://example.invalid/three", qualityHeight = 1080, sizeBytes = 4000000000L),
                    SourceItem("Example.Movie.1080p.WEB-DL.H264.mkv", "Public preview", directUrl = "https://example.invalid/four", qualityHeight = 1080, sizeBytes = 1000000000L),
                    SourceItem("Example.Movie.720p.WEBRip.H264.mkv", "Public preview", directUrl = "https://example.invalid/five", qualityHeight = 720, sizeBytes = 700000000L))
                main { field("publicPreview").set(app, true); MainActivity::class.java.getDeclaredMethod("sourceResults", PlaybackIdentity::class.java, List::class.java).apply { isAccessible = true }.invoke(app, PlaybackIdentity("tt1234567", "movie", "Source sorting preview"), sources) }; snap("source-badges")
                stage = "updates"
                val updates = startActivitySync(Intent(targetContext, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(5000); snap("updates"); main { updates.finish() }
                stage = "player"
                val playerActivity = startActivitySync(Intent(targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
                    .putExtra("key", "").putExtra("title", "Public subtitle preview").putExtra("playWhenReady", false)) as PlayerActivity
                try {
                    await { main { InAppPlaybackSession.playerOrNull?.playbackState == androidx.media3.common.Player.STATE_READY } }
                    val playerView = main { playerActivity.findViewById<androidx.media3.ui.PlayerView>(R.id.pov_player) }
                    main { playerView.controllerAutoShow = false; InAppPlaybackSession.playerOrNull!!.apply { volume = 0f; seekTo(8000); play() }; playerView.hideController() }
                    SystemClock.sleep(6000)
                    main { InAppPlaybackSession.playerOrNull!!.pause(); playerView.hideController() }
                    val preferences = SubtitlePreferences(targetContext)
                    fun render(name: String, transparent: Boolean, bold: Boolean, size: Int) {
                        main {
                            preferences.transparentBackground = transparent; preferences.boldText = bold; preferences.sizePercent = size; preferences.positionPercent = -1
                            preferences.applyTo(playerView)
                            playerView.hideController()
                            playerView.subtitleView!!.setCues(listOf(androidx.media3.common.text.Cue.Builder().setText("Subtitle style preview\nThe quick brown fox jumps over the lazy dog.").build()))
                        }
                        snap(name)
                    }
                    render("subtitles-original", false, false, 100)
                    render("subtitles-transparent-bold", true, true, 100)
                    render("subtitles-small", true, true, 80)
                    render("subtitles-medium", true, true, 100)
                    render("subtitles-large", true, true, 130)
                    stage = "seek"
                    main { playerView.subtitleView!!.setCues(emptyList()); playerView.hideController(); playerActivity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) }
                    snap("seek-preview")
                    main { playerActivity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) }
                } finally { main { playerActivity.finish(); InAppPlaybackSession.stop() } }
                passed = true
            } finally { main { names.forEach { field(it).set(app, original[it]) } } }
        } catch (error: Throwable) { report.putString("stage", stage); report.putString("error_type", error.javaClass.simpleName) }
        finally {
            if (hadOptIn) store.put("wetrakr.home_liked_opt_in", originalOptIn) else store.remove("wetrakr.home_liked_opt_in")
            main {
                backups.forEach { (name, values) ->
                    val edit = targetContext.getSharedPreferences(name, 0).edit().clear()
                    values.forEach { (key, value) -> when (value) { is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value) } }
                    check(edit.commit())
                }
                activity?.finish()
            }; report.putBoolean("passed", passed); report.putBoolean("public_fixtures_only", true); finish(if (passed) -1 else 0, report)
        }
    }
}
