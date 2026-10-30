package dev.pov.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import androidx.media3.ui.PlayerView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.pov.tv.data.*
import kotlin.math.roundToInt
import java.util.Calendar
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var torbox: TorBoxApi
    private lateinit var weTrakrApi: WeTrakrApi
    private lateinit var simklApi: SimklApi
    private lateinit var weTrakrOutbox: PlaybackStore
    private lateinit var simklOutbox: PlaybackStore
    /** The tracker chosen in Settings (WeTrakr or Simkl). Every tracking feature below goes through it. */
    private val wetrakr: Tracker get() = if (simklChosen) simklApi else weTrakrApi
    private val wetrakrOutbox: PlaybackStore get() = if (simklChosen) simklOutbox else weTrakrOutbox
    /** Read once (encrypted settings decrypt on every read) and updated when the service changes. */
    private var simklChosen = false
    private fun trackerId() = if (simklChosen) Trackers.SIMKL else Trackers.WETRAKR
    /** The account's watched history behind the ✓ badges on cards and episodes. */
    private var watched = WatchedState.EMPTY
    private val watchedCache by lazy { WatchedCache(java.io.File(filesDir, "watched.json")) }
    @Volatile private var watchedLoading = false
    /** Re-reads the Continue Watching row on Home in place; set while that row exists. */
    private var refreshContinue: (() -> Unit)? = null
    /** The player was opened from here, so returning to this screen refreshes Continue Watching. */
    private var playerShown = false
    private var watchedCheckedAt = 0L
    private lateinit var resolver: SourceResolver
    private val catalog = CatalogApi()
    private lateinit var history: PlaybackStore
    private lateinit var body: LinearLayout
    private lateinit var themePreferences: ThemePreferences
    private var theme = ThemeSpec.FLIX
    private lateinit var shellRoot: FrameLayout
    private lateinit var heroBackdrop: FrameLayout
    private lateinit var backdropArt: ImageView
    private lateinit var scroller: ScrollView
    private val navigationViews = mutableMapOf<String, View>()
    private var destination = "home"
    private var isHome = false
    // Debug capture uses public discovery only; no account state or viewing history is shown.
    private var publicPreview = false
    private lateinit var anime: AnimeCatalogApi
    private val worker = Executors.newFixedThreadPool(4)
    // Artwork decodes at background priority so the UI thread always wins the CPU while scrolling.
    private val backgroundThreads = java.util.concurrent.ThreadFactory { work ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }
    }
    /** Artwork for the page in front of the viewer loads first, top to bottom; older pages wait. */
    private class ImageTask(val page: Int, val order: Long, val work: Runnable) : Runnable, Comparable<ImageTask> {
        override fun run() = work.run()
        override fun compareTo(other: ImageTask) = if (page != other.page) other.page.compareTo(page) else order.compareTo(other.order)
    }
    private var imageOrder = 0L
    // Artwork servers answer slowly but in parallel, so a screen of posters loads several at a time.
    private val images = java.util.concurrent.ThreadPoolExecutor(8, 8, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.PriorityBlockingQueue<Runnable>(), backgroundThreads)
    // Feature artwork never waits behind a page full of shelf thumbnails.
    private val featureImages = Executors.newFixedThreadPool(2, backgroundThreads)
    private val metadataWorker = Executors.newSingleThreadExecutor()
    private val cloudArtwork = mutableMapOf<String, MediaItemData?>()
    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceIn(18 * 1024 * 1024, 64 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var authorization: DeviceAuthorizationDialog? = null
    private var apiKeyDialog: ApiKeyDialog? = null
    @Volatile private var generation = 0
    private lateinit var focusController: TvFocusController
    private val contentFocusChanged = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        if (::focusController.isInitialized) focusController.revealCurrent()
        if (flix && android.os.SystemClock.uptimeMillis() < menuHoldUntil && destination == "home" && navigationViews.values.none { it.hasFocus() }) body.post { openMenu() }
        else if (flix) setRailExpanded(navigationViews.values.any { it.hasFocus() })
    }
    private data class RetainedPage(val children: List<View>, val padding: IntArray, val home: Boolean, val generation: Int,
        val heroVisibility: Int, val heroHeight: Int, val heroArt: android.graphics.drawable.Drawable?, val heroAddress: String?, val revision: String)
    private data class NavigationSnapshot(val screen: () -> Unit, val focus: TvFocusSnapshot, val destination: String, val retained: RetainedPage?)
    private var pageSequence = 0
    private val pendingLoads = mutableMapOf<Int, Int>()
    private var cachedPlayback: PlaybackIdentity? = null
    private var searchDialog: android.app.Dialog? = null
    private var pendingFocus: TvFocusSnapshot? = null
    private var wantsContentFocus = false
    private var resumed = false
    private var miniPlayer: PlayerView? = null
    private var miniFrame: FrameLayout? = null
    private var miniLabel: TextView? = null
    private var miniFocus: MiniPlayerFocusRouter? = null
    internal var currentPlaybackOptionsDialog: AlertDialog? = null
        private set
    private val playbackChanged: () -> Unit = { runOnUiThread { if (resumed && !isDestroyed) updateMiniPlayer() } }
    private val authWorker = Executors.newSingleThreadExecutor()
    private val selectedSeasons = mutableMapOf<String, Int>()
    private val catalogOffsets = mutableMapOf<String, ArrayDeque<Int>>()
    private var resolvingGeneration: Int? = null
    private var launchingPlayer = false
    /** Release text and size of the link about to play; the chosen file's name is added once it is known. */
    private var playingSourceText = ""
    private var playingMedia: Pair<String, Long?>? = null
    private fun mediaFile(name: String, size: Long?) {
        playingMedia = listOf(name, playingSourceText).filter { it.isNotBlank() }.distinct().joinToString(" · ") to size?.takeIf { it > 0 }
    }
    private val backStack = ArrayDeque<NavigationSnapshot>()
    private var current: () -> Unit = { home() }
    private val remoteProgress = mutableMapOf<String, Double>()
    private val bg get() = theme.palette.background
    private val panel get() = theme.palette.panel
    private val muted get() = theme.palette.muted
    private val accent get() = theme.palette.accent
    private val ink get() = theme.palette.text
    private val flix get() = theme == ThemeSpec.FLIX
    private var rail: LinearLayout? = null
    private var railExpanded = false
    private var railAnimator: ValueAnimator? = null
    private val railLabels = mutableListOf<View>()
    private var featureShown: MediaItemData? = null
    private val featureHandler = Handler(Looper.getMainLooper())
    private val rotationHandler = Handler(Looper.getMainLooper())
    private var heroRotation: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName.endsWith(".preview")) {
            // Preview-only test switches: start with an empty artwork cache, and choose card image size.
            if (intent.getBooleanExtra("clear_artwork_cache", false)) java.io.File(cacheDir, "artwork-http").deleteRecursively()
        }
        ArtworkClient.init(this)
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val sourcePreview = debug && intent.getBooleanExtra("source_choice_preview", false)
        publicPreview = debug && (intent.getBooleanExtra("public_preview", false) || sourcePreview)
        if (DeviceLayout.tv) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        else { window.statusBarColor = 0xFF141414.toInt(); window.navigationBarColor = PHONE_BAR_COLOR }
        store = SecureStore(this); torbox = TorBoxApi(store); weTrakrApi = WeTrakrApi(store); simklApi = SimklApi(store)
        weTrakrOutbox = PlaybackStore(this, Trackers.outboxKey(Trackers.WETRAKR)); simklOutbox = PlaybackStore(this, Trackers.outboxKey(Trackers.SIMKL))
        simklChosen = Trackers.selected(store) == Trackers.SIMKL
        resolver = SourceResolver(store, torbox); history = PlaybackStore(this)
        themePreferences = ThemePreferences(this); theme = themePreferences.selected()
        // Retire the old tracker locally; never replay its queued updates after this upgrade.
        if (store.get("tracking.wetrakr_only") != "true") {
            listOf("trakt.client_id", "trakt.redirect_uri", "trakt.access_token", "trakt.refresh_token", "trakt.expires_at", "trakt.account_revision", "trakt.refresh_pending").forEach(store::remove)
            store.remove("tracking.library"); history.clearPending(); store.put("tracking.wetrakr_only", "true")
        }
        anime = AnimeCatalogApi()
        InAppPlaybackSession.initialize(this)
        InAppPlaybackSession.addListener(playbackChanged)
        if (savedInstanceState == null && !publicPreview && !sourcePreview) showSplash()
        if (!publicPreview) refreshWatched()
        shell(); if (sourcePreview) sourceChoiceAudit() else home(); if (!publicPreview) { retrySync(); checkStartupUpdate() }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(contentFocusChanged)
    }

    override fun onResume() {
        super.onResume(); resumed = true; launchingPlayer = false
        // Back from approving a sign-in code in the browser: check for it straight away.
        authorization?.takeIf { it.isShowing }?.pollNow()
        if (playerShown) { playerShown = false; scheduleContinueRefresh() }
        if (!publicPreview && ::store.isInitialized && android.os.SystemClock.elapsedRealtime() - watchedCheckedAt > 60_000) refreshWatched()
        if (::shellRoot.isInitialized) updateMiniPlayer()
        if (BackHoldGesture.settingsRequested && ::shellRoot.isInitialized) { BackHoldGesture.settingsRequested = false; openSettingsAnywhere() }
    }
    override fun onPause() {
        // Keep the outgoing target until the incoming fullscreen view can attach first.
        resumed = false; miniFrame?.setTag(R.id.pov_mini_shown, false)
        super.onPause()
    }
    override fun onStop() {
        miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        super.onStop()
    }
    override fun onDestroy() {
        window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(contentFocusChanged)
        currentPlaybackOptionsDialog?.dismiss(); currentPlaybackOptionsDialog = null
        searchDialog?.dismiss(); authorization?.dismiss(); apiKeyDialog?.dismiss(); miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        InAppPlaybackSession.removeListener(playbackChanged)
        rotationHandler.removeCallbacksAndMessages(null); featureHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow(); metadataWorker.shutdownNow(); images.shutdownNow(); featureImages.shutdownNow(); authWorker.shutdownNow(); bitmapCache.evictAll()
        super.onDestroy()
    }
    @Deprecated("Android TV Back handling") override fun onBackPressed() {
        if (DeviceLayout.phone && backStack.isEmpty() && destination != "home") { openDestination(destinations().first { it.key == "home" }); return }
        // Flix: content → menu → exit; other top-level pages return straight to Home.
        if (flix && DeviceLayout.tv && backStack.isEmpty()) {
            val inMenu = navigationViews.values.any { it.hasFocus() }
            if (destination != "home") { openHomeMenu(); return }
            if (!inMenu && openMenu()) return
        }
        if (backStack.isNotEmpty()) {
            val saved = backStack.removeLast(); current = saved.screen; destination = saved.destination
            pendingFocus = saved.focus
            val cached = saved.retained
            if (cached != null && cached.revision == pageRevision()) {
                restorePage(cached)
                wantsContentFocus = true; focusAfterRender(generation)
            } else current()
            refreshNavigation()
        } else AlertDialog.Builder(this).setTitle("Leave POV?")
            .setPositiveButton(if (InAppPlaybackSession.hasActive()) "Stop and leave" else "Leave") { _, _ -> InAppPlaybackSession.stop(); finish() }
            .setNegativeButton("Stay", null).show()
    }
    private var splash: View? = null
    private var splashShownAt = 0L
    /**
     * Launch splash: the POV logo, continuing the system launch screen, held until Home's shelves are on
     * screen. It sits on the window itself, above every page, so theme rebuilds never cover it.
     */
    private fun showSplash() {
        val root = window.decorView as? FrameLayout ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xFF141414.toInt()); isClickable = true; elevation = 100f }
        val size = (resources.displayMetrics.widthPixels * if (DeviceLayout.phone) 0.3f else 0.11f).roundToInt()
        // A neon glow in the logo's own cyan and magenta, breathing behind it.
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = size * 1.1f
                colors = intArrayOf(0x66C23CFF, 0x3322D3EE, 0x00141414)
            }
            alpha = 0f
        }
        overlay.addView(glow, FrameLayout.LayoutParams(size * 3, size * 3, Gravity.CENTER))
        // The logo sits in a clipped frame so a light sweep can glide across it.
        val badge = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.06f)
            }
        }
        badge.addView(ImageView(this).apply { setImageResource(R.drawable.pov_icon); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "POV" }, FrameLayout.LayoutParams(-1, -1))
        val shine = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF))
            rotation = 20f; translationX = -size * 1.2f
        }
        badge.addView(shine, FrameLayout.LayoutParams(size / 3, size * 2, Gravity.CENTER))
        overlay.addView(badge, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        splash = overlay; splashShownAt = android.os.SystemClock.uptimeMillis()

        val running = mutableListOf<android.animation.Animator>()
        overlay.tag = running
        fun loop(animator: ValueAnimator) = animator.apply { repeatCount = ValueAnimator.INFINITE; start() }.also(running::add)
        // Spring in: from small and slightly tilted to upright, overshooting a touch.
        badge.alpha = 0f; badge.scaleX = 0.55f; badge.scaleY = 0.55f; badge.rotation = -10f
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(650)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).withEndAction {
                if (splash !== overlay) return@withEndAction
                loop(ValueAnimator.ofFloat(1f, 1.05f).apply {
                    duration = 1_100; repeatMode = ValueAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { badge.scaleX = it.animatedValue as Float; badge.scaleY = badge.scaleX }
                })
                loop(ValueAnimator.ofFloat(-size * 1.2f, size * 1.2f).apply {
                    duration = 1_400; startDelay = 200; repeatMode = ValueAnimator.RESTART
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { shine.translationX = it.animatedValue as Float }
                })
            }.start()
        glow.animate().alpha(1f).setDuration(700).withEndAction {
            if (splash !== overlay) return@withEndAction
            loop(ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_300; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { val f = it.animatedValue as Float; glow.scaleX = 0.9f + f * 0.25f; glow.scaleY = glow.scaleX; glow.alpha = 0.55f + f * 0.45f }
            })
        }.start()
        // Normally Home's first shelves dismiss it; this only guards against a load that never returns.
        overlay.postDelayed({ dismissSplash() }, 10_000)
    }

    private fun dismissSplash() {
        val overlay = splash ?: return
        // Never flash: keep the logo up long enough to finish its entrance.
        val wait = 1_200 - (android.os.SystemClock.uptimeMillis() - splashShownAt)
        if (wait > 0) { overlay.postDelayed({ dismissSplash() }, wait); return }
        splash = null
        @Suppress("UNCHECKED_CAST") (overlay.tag as? List<android.animation.Animator>)?.forEach { it.cancel() }
        // Exit: the logo pushes forward as the splash dissolves into Home.
        (overlay as? ViewGroup)?.let { group -> (0 until group.childCount).map(group::getChildAt).forEach {
            it.animate().scaleX(it.scaleX * 1.18f).scaleY(it.scaleY * 1.18f).setDuration(380).start()
        } }
        overlay.animate().alpha(0f).setDuration(380).withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
    }

    private var lastRepeatAt = 0L
    private val backHold = BackHoldGesture { openSettingsAnywhere() }
    /** Hold Back for one second: Settings, from whatever page is open. */
    private fun openSettingsAnywhere() {
        searchDialog?.dismiss()
        retainHome()
        destination = "settings"; backStack.clear(); current = { settings() }
        settings(); refreshNavigation()
        if (flix) body.post { if (!focusController.first()) wantsContentFocus = true }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (backHold.intercept(event)) return true
        // Holding the D-pad on Home moves at a readable pace instead of racing ahead of the scroll.
        if (flix && isHome && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && ::body.isInitialized && body.hasFocus() && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val now = android.os.SystemClock.uptimeMillis()
            val gap = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 260 else 140
            if (now - lastRepeatAt < gap) return true
            lastRepeatAt = now
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && InAppPlaybackSession.hasActive()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playbackOptions()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::focusController.isInitialized) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> null
            }
            if (direction != null && miniFocus?.route(direction, currentFocus) == true) return true
            if (flix && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
                // The menu stops at its first and last entries instead of leaving for the content.
                val menu = navigationViews.values.filter { it.isShown }.sortedBy { it.top }
                val index = menu.indexOfFirst { it.hasFocus() }
                if (index >= 0) {
                    val next = menu.getOrNull(index + if (direction == View.FOCUS_DOWN) 1 else -1) ?: return true
                    next.requestFocus(); return true
                }
            }
            // A shelf ends at its last card ("See all") rather than wrapping elsewhere.
            if (flix && direction == View.FOCUS_RIGHT && body.hasFocus() && focusController.next(direction) == null) return true
            if (body.hasFocus() && direction != null && focusController.next(direction) == null) {
                val leaving = (theme.layout.navigation == NavigationPlacement.TOP_BAR && direction == View.FOCUS_UP) ||
                    (theme.layout.navigation == NavigationPlacement.LEFT_RAIL && direction == View.FOCUS_LEFT)
                if (leaving && (if (flix) openMenu() else (navigationViews["nowplaying"] ?: navigationViews[destination])?.requestFocus() == true)) {
                    if (direction == View.FOCUS_UP) scroller.scrollTo(0, 0)
                    return true
                }
            }
            if (body.hasFocus() && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                return focusController.move(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP)
            val entering = (theme.layout.navigation == NavigationPlacement.LEFT_RAIL && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ||
                (theme.layout.navigation == NavigationPlacement.TOP_BAR && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            if (entering && navigationViews.values.any { it.hasFocus() } && focusController.first()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private data class Destination(val key: String, val label: String, val screen: () -> Unit)
    /** On TV, Now playing heads the menu so the video is one press away; the phone's tab bar keeps it last. */
    private fun destinations(): List<Destination> {
        val pages = listOf(
            Destination("home", "Home") { home() }, Destination("movie", "Movies") { catalogPage("movie") },
            Destination("series", "Shows") { catalogPage("series") }, Destination("anime", "Anime") { animePage() },
            Destination("library", "Library") { selectedCloud() }, Destination("search", "Search") { search() },
            Destination("wetrakr", wetrakr.name) { weTrakrPage() }, Destination("settings", "Settings") { settings() })
        if (publicPreview || !InAppPlaybackSession.hasActive()) return pages
        val playing = Destination("nowplaying", "Now playing") { returnToVideo() }
        return if (DeviceLayout.tv) listOf(playing) + pages else pages + playing
    }

    /** Status bar height on the phone (0 on TV), added to each page's top padding. */
    private var systemTop = 0
    private var statusScrim: View? = null

    private fun shell() {
        miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        miniPlayer = null; miniFrame = null; miniLabel = null; miniFocus = null
        navigationViews.clear(); rail = null; railExpanded = false; railLabels.clear(); notice = null
        shellRoot = FrameLayout(this).apply { setBackgroundColor(bg); clipChildren = false }
        // Flix draws the page colour once from the window, and the backdrop as one pre-shaded image.
        if (flix) { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg)); shellRoot.background = null }
        heroBackdrop = FrameLayout(this).apply { visibility = View.GONE }
        backdropArt = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroBackdrop.addView(backdropArt, FrameLayout.LayoutParams(-1, -1))
        if (!flix) {
            heroBackdrop.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(bg, withAlpha(bg, 180), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
            heroBackdrop.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(bg, Color.TRANSPARENT, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        }
        // The flix layout keeps a full-screen living backdrop behind every row.
        shellRoot.addView(heroBackdrop, FrameLayout.LayoutParams(-1, if (flix) -1 else dp(325)))
        scroller = TvScrollView(this) { !(flix && isHome) }.apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        body = CullingLinearLayout(this, resources.displayMetrics.heightPixels / 2).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        scroller.addView(body)
        focusController = TvFocusController(body, scroller, ::placeFlixRow) {
            val rect = android.graphics.Rect()
            miniFrame?.takeIf { it.isShown && it.getGlobalVisibleRect(rect) }?.let { rect }
        }
        scroller.setOnScrollChangeListener { _, _, y, _, _ ->
            // The feature copy stays pinned while rows slide beneath it.
            statusScrim?.alpha = (y / dp(40).toFloat()).coerceIn(0f, 1f)
            // On the phone the banner and its copy scroll away with the page.
            if (flix && DeviceLayout.tv) featureHero()?.translationY = y.toFloat() else heroBackdrop.translationY = -y.toFloat()
            body.invalidate()
        }
        val layout = theme.layout
        shellRoot.addView(scroller, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(layout.railWidthDp + layout.contentInsetDp)
            rightMargin = dp(layout.contentInsetDp)
            topMargin = dp(layout.headerHeightDp)
            if (layout.navigation == NavigationPlacement.BOTTOM_BAR) bottomMargin = dp(BOTTOM_BAR_HEIGHT)
        })
        if (layout.navigation == NavigationPlacement.BOTTOM_BAR) {
            val bar = LinearLayout(this).apply { setBackgroundColor(PHONE_BAR_COLOR); elevation = dp(4).toFloat() }
            shellRoot.addView(bar, FrameLayout.LayoutParams(-1, dp(BOTTOM_BAR_HEIGHT), Gravity.BOTTOM))
            destinations().forEach { target -> bar.addView(bottomNavigation(target), LinearLayout.LayoutParams(0, -1, 1f)) }
        } else if (layout.navigation == NavigationPlacement.LEFT_RAIL) {
            val rail = column().apply {
                setPadding(dp(12), dp(22), dp(1), dp(13))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg, withAlpha(bg, 235)))
            }
            if (flix) {
                // Hidden until Back or Left from the first column; it then slides in over the content.
                rail.setPadding(dp(14), dp(20), dp(8), dp(13)); rail.background = railBackground(false); this.rail = rail
                rail.visibility = View.VISIBLE; rail.clipChildren = true
            }
            shellRoot.addView(rail, FrameLayout.LayoutParams(dp(layout.railWidthDp), -1))
            if (flix) rail.addView(logo(), margin(40, 40, bottom = 34).apply { leftMargin = dp(10) })
            else rail.addView(logo(), margin(61, 58, bottom = 24).apply { leftMargin = dp(10) })
            destinations().forEach { target -> rail.addView(navigation(target, false), margin(-1, 35, bottom = 4)) }
            rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        } else {
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(layout.contentInsetDp), dp(5), dp(16), dp(5))
            }
            shellRoot.addView(header, FrameLayout.LayoutParams(-1, dp(layout.headerHeightDp)))
            header.addView(logo(), margin(45, 45, right = 22))
            destinations().forEach { target -> header.addView(navigation(target, true), LinearLayout.LayoutParams(0, dp(38), 1f)) }
        }
        if (DeviceLayout.phone) shellRoot.setOnApplyWindowInsetsListener { root, insets ->
            // Android 15 draws apps edge to edge: Home's banner may sit under the status bar, but page
            // titles move below it, and the tab bar, mini player and notices stay above the gesture bar.
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            root.setPadding(0, 0, 0, bottom)
            statusScrim?.let { scrim -> if (scrim.layoutParams.height != top) scrim.layoutParams = scrim.layoutParams.apply { height = top } }
            if (top != systemTop) {
                if (!isHome) body.setPadding(body.paddingLeft, body.paddingTop + top - systemTop, body.paddingRight, body.paddingBottom)
                systemTop = top
            }
            insets
        }
        statusScrim = if (DeviceLayout.phone) View(this).apply { setBackgroundColor(bg); alpha = 0f; elevation = dp(5).toFloat() }
            .also { shellRoot.addView(it, FrameLayout.LayoutParams(-1, systemTop, Gravity.TOP)) } else null
        setContentView(shellRoot)
        refreshNavigation()
        if (resumed) updateMiniPlayer()
    }

    private fun logo() = ImageView(this).apply {
        setImageResource(R.drawable.pov_icon); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "POV"
    }

    private fun navigation(target: Destination, top: Boolean): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; contentDescription = target.label
            tag = "nav:${target.key}"
        }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (!top) {
            line.addView(NavigationIconView(this, target.key, muted), margin(19, 19, right = 12))
            line.setPadding(dp(11), 0, 0, 0)
        } else line.gravity = Gravity.CENTER
        val label = text(target.label, if (top) 12 else 12, muted)
        if (flix && !top) {
            label.visibility = if (railExpanded) View.VISIBLE else View.GONE; label.maxLines = 1; railLabels += label
        }
        if (target.key == "wetrakr" && wetrakr is WeTrakrApi && !(flix && !top)) {
            if (!top && line.childCount > 0) line.removeViewAt(0)
            line.addView(ImageView(this).apply {
                setImageResource(R.drawable.wetrakr_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "WeTrakr"
            }, LinearLayout.LayoutParams(if (top) dp(85) else -1, dp(26)))
        } else line.addView(label, LinearLayout.LayoutParams(-2, -2))
        frame.addView(line, FrameLayout.LayoutParams(-1, -1))
        val indicator = View(this).apply { tag = "indicator"; background = rounded(accent, 2) }
        frame.addView(indicator, if (top) FrameLayout.LayoutParams(dp(31), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            else FrameLayout.LayoutParams(dp(3), dp(29), Gravity.START or Gravity.CENTER_VERTICAL))
        fun paint(focused: Boolean) {
            val selected = destination == target.key
            val color = if (selected || focused) ink else muted
            label.setTextColor(color); label.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
            for (index in 0 until line.childCount) (line.getChildAt(index) as? NavigationIconView)?.tint = color
            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (flix) {
                frame.background = rounded(if (focused) theme.palette.navSelected else Color.TRANSPARENT, 4)
                frame.foreground = null
                return
            }
            frame.background = rounded(if (focused || (!top && selected)) theme.palette.navSelected else Color.TRANSPARENT, 7)
            frame.foreground = if (focused) FocusRingDrawable(accent, dp(7).toFloat(), true, dp(1).toFloat()) else null
        }
        frame.setOnFocusChangeListener { _, focused -> paint(focused) }
        frame.setOnClickListener { openDestination(target) }
        navigationViews[target.key] = frame
        paint(false)
        return frame
    }

    private fun openDestination(target: Destination) {
        if (target.key == "search" || target.key == "nowplaying") target.screen()
        else {
            if (target.key != "home") retainHome()
            destination = target.key; backStack.clear(); current = target.screen; current(); refreshNavigation()
            // The chosen page takes focus and the menu tucks away; pages still loading claim it when ready.
            if (flix && DeviceLayout.tv) body.post { if (!focusController.first()) wantsContentFocus = true }
        }
    }

    /** Phone navigation: an icon over a short label, the current tab in white. */
    private fun bottomNavigation(target: Destination): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            tag = "nav:${target.key}"; contentDescription = target.label
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null)
        }
        val icon = NavigationIconView(this, target.key, muted)
        cell.addView(icon, margin(21, 21))
        val label = text(if (target.key == "nowplaying") "Playing" else target.label, 9, muted).apply { maxLines = 1; gravity = Gravity.CENTER }
        cell.addView(label, margin(-2, -2, top = 3))
        fun paint() {
            val color = if (destination == target.key) ink else muted
            label.setTextColor(color); icon.tint = color
            label.typeface = Typeface.create("sans-serif", if (destination == target.key) Typeface.BOLD else Typeface.NORMAL)
        }
        // refreshNavigation() repaints every tab through this listener.
        cell.setOnFocusChangeListener { _, _ -> paint() }
        cell.setOnClickListener { openDestination(target) }
        navigationViews[target.key] = cell
        paint()
        return cell
    }

    private fun refreshNavigation() {
        // Rebuilding a nav cell's state does not steal focus from remote navigation.
        navigationViews.values.forEach { view -> view.onFocusChangeListener?.onFocusChange(view, view.hasFocus()) }
    }

    private var retainedHome: RetainedPage? = null
    private var retainedHomeAt = 0L
    /** Keeps the built Home page (from screen or from the back stack) when the menu leads elsewhere. */
    private fun retainHome() {
        val page = if (isHome && pendingLoads[generation].orZero() == 0) retainPage() else backStack.firstOrNull { it.retained?.home == true }?.retained
        if (page != null) { retainedHome = page; retainedHomeAt = android.os.SystemClock.elapsedRealtime() }
    }
    private fun retainPage() = RetainedPage(
        (0 until body.childCount).map(body::getChildAt), intArrayOf(body.paddingLeft, body.paddingTop, body.paddingRight, body.paddingBottom),
        isHome, generation, heroBackdrop.visibility, heroBackdrop.layoutParams.height, backdropArt.drawable, backdropArt.tag as? String, pageRevision())
    private fun restorePage(cached: RetainedPage) {
        generation = cached.generation; resolvingGeneration = null; isHome = cached.home; flixScrollTarget = -1
        focusController.reset(); body.removeAllViews()
        body.animate().cancel(); body.alpha = 1f; body.translationY = 0f
        body.setPadding(cached.padding[0], cached.padding[1], cached.padding[2], cached.padding[3])
        cached.children.forEach { (it.parent as? ViewGroup)?.removeView(it); body.addView(it) }
        heroBackdrop.animate().cancel(); heroBackdrop.alpha = 1f; heroBackdrop.scaleX = 1f; heroBackdrop.scaleY = 1f
        heroBackdrop.visibility = cached.heroVisibility; heroBackdrop.layoutParams.height = cached.heroHeight
        placeBackdrop(home = cached.home)
        backdropArt.tag = cached.heroAddress; backdropArt.setImageDrawable(cached.heroArt)
        backdropArt.setTag(R.id.pov_backdrop_key, cached.heroAddress?.let { if (cached.home) "backdrop-corner:$it" else "backdrop:$it" })
        if (cached.heroArt == null) cached.heroAddress?.let { image(backdropArt, it) }
        heroBackdrop.requestLayout(); featureShown = null; backdropArt.alpha = 1f
    }

    private fun navigate(screen: () -> Unit) {
        val retained = if (pendingLoads[generation].orZero() == 0) retainPage() else null
        backStack.addLast(NavigationSnapshot(current, focusController.capture(), destination, retained))
        while (backStack.count { it.retained != null } > 4) {
            val oldest = backStack.indexOfFirst { it.retained != null }; backStack[oldest] = backStack[oldest].copy(retained = null)
        }
        pendingFocus = null; current = screen; screen()
    }

    private fun Int?.orZero() = this ?: 0
    private fun pageRevision() = java.security.MessageDigest.getInstance("SHA-256").digest(
        listOf(theme.id, wetrakr.accountRevision(), store.get("offcloud.key"), store.get("torbox.token"), store.get("wetrakr.home_liked_opt_in"), store.get("sources.endpoint"), store.get(SourceProviders.KEY), store.get(TMDB_KEY), store.get(TMDB_REGION), store.get(RESUME_CARDS)).joinToString("\u0000").toByteArray()
    ).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun focusAfterRender(id: Int) {
        body.post {
            if (id != generation || isDestroyed) return@post
            val restore = pendingFocus
            if (restore != null) {
                if (focusController.restore(restore) || focusController.restore(null)) { pendingFocus = null; wantsContentFocus = false }
            } else if (wantsContentFocus && !focusController.hasVisibleLeafFocus()) {
                if (focusController.restore(null)) wantsContentFocus = false
            }
        }
    }
    private fun page(title: String, subtitle: String = ""): Int {
        generation = ++pageSequence; resolvingGeneration = null; isHome = false; wantsContentFocus = true; flixScrollTarget = -1
        featureHandler.removeCallbacksAndMessages(null); featureShown = null; backdropArt.alpha = 1f
        bottomAligner?.let(body::removeOnLayoutChangeListener); bottomAligner = null
        for (index in 0 until body.childCount) body.getChildAt(index).animate().cancel()
        heroBackdrop.animate().cancel(); heroBackdrop.alpha = 1f; heroBackdrop.scaleX = 1f; heroBackdrop.scaleY = 1f
        focusController.reset(); body.removeAllViews(); heroBackdrop.visibility = View.GONE
        body.animate().cancel(); body.alpha = 1f; body.translationY = 0f
        body.setPadding(0, systemTop + dp(when (theme.layout.navigation) { NavigationPlacement.LEFT_RAIL -> 63; NavigationPlacement.BOTTOM_BAR -> 16; else -> 15 }), 0, contentBottomPadding())
        scroller.scrollTo(0, 0); heroBackdrop.translationY = 0f
        if (title.isNotBlank()) body.addView(text(title, if (DeviceLayout.phone) 24 else 29, ink, true), margin(-1, -2, bottom = 5))
        if (subtitle.isNotBlank()) body.addView(text(subtitle, 12, muted), margin(-1, -2, bottom = 18))
        return generation
    }

    private fun home() {
        current = { home() }
        destination = "home"; refreshNavigation()
        val kept = retainedHome; retainedHome = null
        if (kept != null && kept.revision == pageRevision() && android.os.SystemClock.elapsedRealtime() - retainedHomeAt < HOME_KEEP_MS) {
            // Coming back through the menu shows the same Home at once, from the top.
            restorePage(kept)
            scroller.scrollTo(0, 0)
            if (flix) {
                featureHero()?.let { hero -> hero.translationY = 0f; hero.findViewWithTag<View>("feature-actions")?.alpha = 1f }
                for (index in 0 until body.childCount) body.getChildAt(index).let { it.animate().cancel(); it.alpha = 1f }
            }
            if (homeMenuFocus) { homeMenuFocus = false; wantsContentFocus = false; openMenu() }
            else { wantsContentFocus = true; focusAfterRender(generation) }
            return
        }
        val id = page(""); isHome = true; body.setPadding(0, 0, 0, contentBottomPadding())
        val remoteHome = !publicPreview && wetrakr.connected()
        val recent = if (publicPreview || remoteHome) emptyList() else history.continueWatching()
        // Something in progress: the feature trims so the first screen ends on a complete row.
        val compact = flix && if (remoteHome) !cachedContinueWatching().isNullOrEmpty() else recent.isNotEmpty()
        fun render(shelves: List<CatalogApi.HomeShelf>) {
            val movies = shelves.first { it.title == "Popular Movies" }.items
            val series = shelves.first { it.title == "Popular Shows" }.items
            val rotation = if (flix) (0 until 10).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) } else emptyList()
            (movies.firstOrNull() ?: shelves.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull())?.let { if (flix) streamingHero(it, true, rotation, compact) else hero(it) }
            if (remoteHome) homeContinueWatching(id) else if (recent.isNotEmpty()) resumeRow(recent)
            if (flix && !compact) {
                rankedRow("Top 10 Movies", movies)
                rankedRow("Top 10 Shows", series)
            }
            var topTenPending = flix && compact
            shelves.forEach { shelf ->
                if (shelf.items.isNotEmpty()) {
                    mediaRow(shelf.title, shelf.items)
                    // With Continue Watching on the first screen, the tall Top 10 shelves follow the first regular row.
                    if (topTenPending) { topTenPending = false; rankedRow("Top 10 Movies", movies); rankedRow("Top 10 Shows", series) }
                } else {
                    body.addView(button("${shelf.title}  ›") { navigate { shelfGrid(shelf.title, shelf.items) } }
                        .apply { tag = "shelf:${shelf.title}" }, margin(-1, 40, top = 10, bottom = 7))
                    body.addView(text(if (shelf.unavailable) "This catalog is temporarily unavailable." else "No titles returned for this catalog.", 13, muted))
                    if (shelf.unavailable) body.addView(button("Retry ${shelf.title}") { home() }, margin(240, 38, bottom = 10))
                }
            }
            if (!publicPreview && wetrakr.connected() && store.get("wetrakr.home_liked_opt_in") == "true") homeLikedLists(id)
            // Credits live in Settings → About; Home ends at its last shelf. The feature's Play starts focused.
            if (!flix) homeCredit()
            body.post { dismissSplash() }
            if (flix) {
                if (homeMenuFocus) { homeMenuFocus = false; wantsContentFocus = false; if (navigationViews.values.none { it.hasFocus() }) openMenu() } else focusAfterRender(id)
            }
        }
        // Home opens with the rows saved from the last visit (like Netflix and YouTube), then refreshes them quietly.
        val revision = shelfRevision()
        val saved = if (publicPreview || skipHomeRefresh) null else shelfCache.load(revision)
        val refreshed = skipHomeRefresh; skipHomeRefresh = false
        if (saved != null) {
            render(saved); focusAfterRender(id)
            worker.execute {
                val fresh = try { homeShelves() } catch (_: Exception) { return@execute }
                shelfCache.save(revision, fresh)
                runOnUiThread {
                    // New rows replace the saved ones only while the viewer is still at the top of Home; otherwise
                    // they wait for the next visit, so nothing moves under a browsing viewer.
                    if (id == generation && isHome && !isFinishing && !isDestroyed && scroller.scrollY == 0 &&
                        !HomeShelfCache.sameRows(saved, fresh)) { pendingShelves = fresh; skipHomeRefresh = true; home() }
                }
            }
        } else {
            val ready = pendingShelves.takeIf { refreshed }; pendingShelves = null
            if (ready != null) { render(ready); focusAfterRender(id) }
            else read(id, { homeShelves().also { if (!publicPreview) shelfCache.save(revision, it) } }) { shelves -> render(shelves) }
        }
    }

    private val shelfCache by lazy { HomeShelfCache(java.io.File(filesDir, "home_shelves.json")) }
    private var skipHomeRefresh = false
    private var pendingShelves: List<CatalogApi.HomeShelf>? = null
    /** Saved rows belong to one catalog setup: changing the TMDB key, country or theme starts fresh. */
    private fun shelfRevision() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(listOf(SHELF_SET_VERSION, theme.id, store.get(TMDB_KEY), tmdbRegion()).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun cachedContinueWatching(): List<Pair<PlaybackIdentity, Double>>? = try {
        org.json.JSONObject(store.get(CONTINUE_CACHE)).takeIf { it.optString("revision") == wetrakr.accountRevision() }?.optJSONArray("items")?.let { items ->
            (0 until items.length()).map { index -> items.getJSONObject(index).let { PlaybackIdentity.fromJson(it.getJSONObject("id")) to it.getDouble("percent") } }
        }
    } catch (_: Exception) { null }

    /** TMDB shelves when the viewer has added a key; Cinemeta otherwise, or if TMDB cannot be reached. */
    private fun homeShelves(): List<CatalogApi.HomeShelf> {
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        if (key.isNotBlank()) {
            val shelves = TmdbCatalog(this, key, tmdbRegion()).homeShelves()
            if (shelves.any { !it.unavailable && it.items.isNotEmpty() }) return shelves
            runOnUiThread { toast("TMDB is unavailable. Showing Cinemeta catalogs instead.") }
        }
        return catalog.homeShelves()
    }
    private fun tmdbRegion() = store.get(TMDB_REGION).ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }
    private fun countryName(code: String) = java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { code }

    private fun homeContinueWatching(id: Int) {
        val host = column().apply { tag = "wetrakr-home-continue" }
        body.addView(host, margin(-1, -2, top = 10))
        host.addView(text("Continue Watching · ${wetrakr.name}", if (flix) theme.layout.sectionTitleSizeSp else 20, ink, true), margin(-1, -2, bottom = if (flix) 6 else 8))
        val row = if (flix) horizontalRow(host) else LinearLayout(this).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false }
        if (!flix) {
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; isFocusable = false }
            scroll.addView(row); host.addView(scroll)
        }
        fun fill(progress: List<Pair<PlaybackIdentity, Double>>) {
            row.removeAllViews(); host.findViewWithTag<View>("wetrakr-empty")?.let(host::removeView)
            // WeTrakr is authoritative here, including progress made on other devices.
            if (progress.isEmpty()) host.addView(text("Nothing in progress on ${wetrakr.name}.", 13, muted).apply { tag = "wetrakr-empty" })
            progress.forEach { (identity, percent) -> remoteProgress[identityKey(identity)] = percent }
            // A tracker can hold hundreds of paused titles; Home shows the most recent ones.
            progress.take(CONTINUE_LIMIT).forEach { (identity, percent) ->
                val caption = listOf(if (identity.type == "series") "S${identity.season} E${identity.episode}" else "", "${percent.toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")
                val item = MediaItemData(identity.imdbId, identity.type, identity.title)
                val open: () -> Unit = { continuePlayback(identity) }
                row.addView((if (resumeCards()) resumeCard(item, percent / 100, episodeLine(identity), "${percent.toInt()}% watched", open) else tile(item, percent / 100, caption, open))
                    .apply { continueOptions(this, "", identity, identity.title) }, continueSlot())
            }
        }
        // The last list is kept encrypted with the account revision, so the row shows instantly on launch.
        val revision = wetrakr.accountRevision()
        refreshContinue = {
            val tracker = wetrakr
            worker.execute {
                val fresh = try { tracker.refreshCaches(); tracker.continueWatching() } catch (_: Exception) { null }
                runOnUiThread {
                    if (fresh == null || isDestroyed || tracker.accountRevision() != revision || !row.isAttachedToWindow) return@runOnUiThread
                    store.put(CONTINUE_CACHE, org.json.JSONObject().put("revision", revision).put("items", org.json.JSONArray(fresh.map { (identity, percent) ->
                        org.json.JSONObject().put("id", identity.toJson()).put("percent", percent) })).toString())
                    if (!row.hasFocus()) fill(fresh)
                }
            }
        }
        val cached = cachedContinueWatching()
        cached?.let(::fill)
        read(id, { wetrakr.continueWatching() }) { progress ->
            store.put(CONTINUE_CACHE, org.json.JSONObject().put("revision", revision).put("items", org.json.JSONArray(progress.map { (identity, percent) ->
                org.json.JSONObject().put("id", identity.toJson()).put("percent", percent) })).toString())
            // Refreshing never pulls the row out from under a viewer who is browsing it.
            if (progress != cached && !row.hasFocus()) fill(progress)
        }
    }

    private fun homeLikedLists(id: Int) {
        if (store.get("wetrakr.home_liked_opt_in") != "true") return
        likedLists(id, true)
    }

    private fun likedLists(id: Int, home: Boolean) {
        val host = column().apply { tag = if (home) "wetrakr-home-liked" else "wetrakr-section-liked" }
        body.addView(host, margin(-1, -2, top = 10))
        if (flix) { flixLikedLists(id, host); return }
        read(id, { wetrakr.likedLists() }) { lists ->
            if (lists.isEmpty() && !home) host.addView(text("No liked lists on ${wetrakr.name} yet.", 13, muted))
            if (lists.isNotEmpty()) {
                host.addView(text("Liked lists · ${wetrakr.name}", 20, ink, true), margin(-1, -2, bottom = 8))
                lists.forEach { (listId, title) ->
                    host.addView(button("$title  ›") {
                        navigate { val pageId = page(title, "Liked list · WeTrakr"); read(pageId, { wetrakr.likedListItems(listId) }) { mediaGrid(it) } }
                    }, margin(-1, 40, bottom = 4))
                    val row = LinearLayout(this).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false }
                    host.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; addView(row) })
                    read(id, { wetrakr.likedListPreview(listId) }) { items ->
                        items.forEach { item -> row.addView(card(item), margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
                    }
                }
            }
        }
    }

    /**
     * Flix: every liked list becomes an ordinary shelf (title, cards, "See all" for the whole list). Each shelf is
     * its own page section so it tucks away under the feature like the others; slots keep the lists' order.
     */
    private fun flixLikedLists(id: Int, anchor: LinearLayout) {
        read(id, { wetrakr.likedLists() }) { lists ->
            var index = body.indexOfChild(anchor)
            lists.forEach { (listId, title) ->
                val slot = column().apply { tag = "wetrakr-liked:$listId" }
                body.addView(slot, ++index, margin(-1, -2))
                read(id, { wetrakr.likedListPreview(listId) }) { items ->
                    mediaRow(title, items, slot) {
                        navigate { val pageId = page(title, "Liked list · WeTrakr"); read(pageId, { wetrakr.likedListItems(listId) }) { mediaGrid(it) } }
                    }
                }
            }
        }
    }

    private fun hero(item: MediaItemData, showAction: Boolean = true) = hero(item, showAction, 0)
    private fun hero(item: MediaItemData, showAction: Boolean, synopsisLines: Int) {
        if (flix) { streamingHero(item, showAction, synopsisLines = synopsisLines); return }
        val layout = theme.layout
        val split = layout.hero == HeroComposition.SPLIT
        val full = layout.hero == HeroComposition.FULL_BLEED
        val height = if (!showAction) 262 else layout.heroHeightDp
        val frame = FrameLayout(this).apply { clipChildren = false; tag = "featured-hero" }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val address = item.background.ifBlank { item.poster }
        if (full) {
            heroBackdrop.layoutParams.height = dp(height + layout.headerHeightDp + 22) + body.paddingTop
            heroBackdrop.requestLayout()
            heroBackdrop.visibility = View.VISIBLE
            image(backdropArt, address)
        } else {
            val artFrame = FrameLayout(this).apply { background = rounded(panel, layout.cornerRadiusDp); clipToOutline = true }
            artFrame.addView(art, FrameLayout.LayoutParams(-1, -1)); image(art, address)
            if (!split) artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, withAlpha(Color.BLACK, 130), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(artFrame, FrameLayout.LayoutParams(if (split) dp(577) else -1, -1, Gravity.END))
        }
        val copy = column()
        val copyWidth = if (split) 311 else 410
        val title = text(item.title.uppercase(), layout.titleSizeSp, ink).apply {
            typeface = Typeface.create(layout.titleTypeface, Typeface.NORMAL)
            letterSpacing = layout.titleLetterSpacing; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            val measured = paint.measureText(text.toString()) + (text.length - 1).coerceAtLeast(0) * letterSpacing * textSize
            val ratio = (dp(copyWidth).toFloat() / measured.coerceAtLeast(1f)).coerceIn(0.58f, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * ratio)
        }
        if (split && showAction) copy.addView(text(greeting(), 16, ink), margin(-1, -2, bottom = 6))
        copy.addView(title, margin(-1, -2))
        copy.addView(text(listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString("  ·  "), 13, muted), margin(-1, -2, top = 5))
        val description = text(item.description, 13, muted).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
        }
        if (item.description.isNotBlank()) copy.addView(description, margin(if (split) -1 else 345, -2, top = 10))
        val left = 0
        val copyTop = if (!showAction) 15 else 8
        val actionBottom = if (split) 10 else 16
        val availableCopy = dp(if (showAction) height - actionBottom - 38 - copyTop - 8 else height - copyTop - 10)
        repeat(10) {
            copy.measure(View.MeasureSpec.makeMeasureSpec(dp(copyWidth), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (copy.measuredHeight > availableCopy) {
                if (description.maxLines > 1) description.maxLines--
                else title.setTextSize(TypedValue.COMPLEX_UNIT_PX, (title.textSize * 0.9f).coerceAtLeast(dp(18).toFloat()))
            }
        }
        frame.addView(copy, FrameLayout.LayoutParams(dp(copyWidth), -2).apply { leftMargin = dp(left); topMargin = dp(copyTop) })
        if (showAction) {
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val saved = if (publicPreview) null else history.continueWatching().firstOrNull { it.identity?.imdbId == item.imdbId }
            actions.addView(primaryButton(if (saved != null) "▶  Play" else "▶  Explore") {
                if (saved?.identity != null) navigate { showSources(saved.identity) } else navigate { details(item) }
            }, margin(160, 38, right = 17))
            if (!split) actions.addView(button("＋  Add to Library") {
                val tracker = weTrakrClient()
                if (tracker.connected()) load(generation, { tracker.setWatchlist(PlaybackIdentity(item.imdbId, item.type, item.title), true) }) { toast("Added to your ${tracker.name} watchlist") }
                else { toast("Connect a tracker to save titles to your library"); navigate { weTrakrPage() } }
            }.apply { background = rounded(withAlpha(panel, 90), 24) }, margin(159, 38))
            frame.addView(actions, FrameLayout.LayoutParams(dp(copyWidth), dp(38), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(left); bottomMargin = dp(actionBottom)
            })
        }
        body.addView(frame, margin(-1, height, bottom = if (split) 10 else 0))
    }

    private var homeMenuFocus = false
    private var menuHoldUntil = 0L
    private fun openHomeMenu() {
        if (retainedHome == null) retainHome()
        homeMenuFocus = true; destination = "home"; backStack.clear(); current = { home() }
        home(); refreshNavigation(); openMenu()
        // Swapping pages lets Android restore "default focus" to the first control it finds; the menu keeps it briefly.
        menuHoldUntil = android.os.SystemClock.uptimeMillis() + 1_200
    }

    /** Android refuses focus to zero-width views, so the hidden menu opens before taking focus. */
    private fun openMenu(): Boolean {
        val view = rail ?: return false
        // While a video plays, the menu opens on Now playing so it is one press away.
        val target = navigationViews["nowplaying"] ?: navigationViews[destination] ?: navigationViews["home"] ?: return false
        setRailExpanded(true, animate = false)
        fun attempt() { if (!target.requestFocus()) setRailExpanded(false) }
        // Focus only once the widened menu has been laid out; until then its items have no size.
        if (!view.isLayoutRequested && view.width > 0) view.post(::attempt)
        else view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this); v.post(::attempt)
            }
        })
        return true
    }

    private var bottomAligner: View.OnLayoutChangeListener? = null

    /**
     * Details open with their parts rising into place one after another: title, buttons, season tabs, episodes.
     * Each part moves on its own (never the whole page), briefly, so it stays smooth on TV hardware.
     */
    private fun riseIn() {
        val lift = dp(24).toFloat()
        for (index in 0 until minOf(body.childCount, 6)) {
            val child = body.getChildAt(index)
            child.animate().cancel(); child.alpha = 0f; child.translationY = lift
            child.animate().alpha(1f).translationY(0f).setStartDelay(index * 40L).setDuration(260).setInterpolator(EASE_OUT).start()
        }
    }
    /**
     * Movie details sit at the foot of the screen so the backdrop stays open above them. Re-applied on
     * layout, because the title logo arriving changes the content height.
     */
    private fun alignToBottom(id: Int, reveal: () -> Unit = {}) {
        // The details stay hidden until they sit at the foot of the screen, so they never drop into place.
        var placed = false
        fun reveal() {
            if (placed || id != generation) return
            placed = true; body.alpha = 1f; reveal()
        }
        body.animate().cancel(); body.alpha = 0f; body.translationY = 0f
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (id != generation || scroller.height == 0) return@OnLayoutChangeListener
            var content = 0
            for (index in 0 until body.childCount) {
                val child = body.getChildAt(index); val params = child.layoutParams as ViewGroup.MarginLayoutParams
                if (child.visibility != View.GONE) content += child.height + params.topMargin + params.bottomMargin
            }
            val top = (scroller.height - content - body.paddingBottom - dp(10)).coerceAtLeast(dp(20))
            if (kotlin.math.abs(top - body.paddingTop) > 2) body.post {
                if (id != generation) return@post
                body.setPadding(body.paddingLeft, top, body.paddingRight, body.paddingBottom)
                if (!placed) body.post(::reveal)
            } else body.post(::reveal)
        }
        bottomAligner?.let(body::removeOnLayoutChangeListener)
        bottomAligner = listener; body.addOnLayoutChangeListener(listener); body.requestLayout()
        // Never leave the page hidden if layout settles without another pass.
        body.postDelayed(::reveal, 1_200)
    }

    private fun streamingHero(item: MediaItemData, showAction: Boolean, rotation: List<MediaItemData> = emptyList(), compact: Boolean = false, synopsisLines: Int = 0) {
        val layout = theme.layout
        val height = if (!showAction) 250 else if (compact) COMPACT_HERO_HEIGHT else layout.heroHeightDp
        val frame = (if (showAction && DeviceLayout.tv) LayoutBoundaryFrame(this) else FrameLayout(this)).apply { clipChildren = false; tag = "featured-hero"; setTag(R.id.pov_feature_item, item) }
        placeBackdrop(home = showAction)
        heroBackdrop.visibility = View.VISIBLE
        image(backdropArt, backdropFor(item), feature = true)
        // Shadowed display text is rasterized into one layer, redrawn only when the feature changes.
        val copy = column().apply { tag = "feature-copy"; setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        // The phone sizes the logo to its own shape, so a wide logo leaves no empty band beneath it.
        if (DeviceLayout.phone) copy.addView(ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE; maxWidth = dp(240); maxHeight = dp(64)
        }, margin(-2, -2))
        else copy.addView(FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }, margin(350, if (compact) 72 else 88))
        copy.addView(text("", layout.titleSizeSp, ink, true).apply {
            tag = "feature-title"; typeface = Typeface.create(layout.titleTypeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0xB0000000.toInt())
        }, margin(-1, -2))
        copy.addView(text("", 12, Color.WHITE, true).apply { tag = "feature-meta" }, margin(-1, -2, top = 10))
        copy.addView(text("", 13, 0xFFE6E6E6.toInt()).apply {
            tag = "feature-description"; setLineSpacing(dp(2).toFloat(), 1f)
            if (showAction) { maxLines = if (compact && DeviceLayout.tv) 2 else 3; ellipsize = TextUtils.TruncateAt.END }
            else if (synopsisLines > 0) { maxLines = synopsisLines; ellipsize = TextUtils.TruncateAt.END }
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0xB0000000.toInt())
        }, margin(-1, -2, top = 8))
        // A shortened synopsis keeps the rest one press away.
        if (!showAction && synopsisLines > 0 && item.description.length > SYNOPSIS_MORE_CHARS) copy.addView(button("More") {
            synopsisDialog(item.title, item.description)
        }.apply { gravity = Gravity.CENTER }, margin(90, 34, top = 10))
        if (DeviceLayout.phone) frame.addView(copy, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(PHONE_COPY_TOP) })
        else frame.addView(copy, FrameLayout.LayoutParams(dp(if (showAction) 420 else 560), -2).apply { topMargin = dp(if (!showAction) 12 else if (compact) 24 else 30) })
        if (showAction) {
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "feature-actions"; clipChildren = false }
            // Actions follow whichever title the rotating feature currently shows.
            fun shown() = frame.getTag(R.id.pov_feature_item) as? MediaItemData ?: item
            actions.addView(primaryButton(playLabel(item)) {
                val current = shown()
                val saved = savedFor(current)
                when {
                    // Only Continue Watching reuses the saved link; Play always finds fresh links (and still resumes).
                    saved?.identity != null -> navigate { showSources(saved.identity) }
                    current.type == "movie" -> navigate { showSources(PlaybackIdentity(current.imdbId, current.type, current.title)) }
                    else -> navigate { details(current) }
                }
            }, margin(if (DeviceLayout.phone) 108 else 122, 38, right = if (DeviceLayout.phone) 8 else 12))
            actions.addView(button("ⓘ  More Info") { val current = shown(); navigate { details(current) } }.apply { gravity = Gravity.CENTER },
                margin(if (DeviceLayout.phone) 124 else 136, 38, right = if (DeviceLayout.phone) 8 else 12))
            actions.addView(button("＋  My List") {
                val item = shown()
                val tracker = weTrakrClient()
                if (tracker.connected()) load(generation, { tracker.setWatchlist(PlaybackIdentity(item.imdbId, item.type, item.title), true) }) { toast("Added to your ${tracker.name} watchlist") }
                else { toast("Connect a tracker to save titles to your library"); navigate { weTrakrPage() } }
            }.apply { gravity = Gravity.CENTER }, margin(if (DeviceLayout.phone) 108 else 122, 38))
            if (DeviceLayout.phone) copy.addView(actions, margin(-1, 38, top = 14))
            else frame.addView(actions, FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(26) })
        }
        // Details pages grow to fit the complete synopsis, as does the phone's Home feature.
        body.addView(frame, margin(-1, if (showAction && DeviceLayout.tv) height else -2).apply { if (!showAction || DeviceLayout.phone) bottomMargin = dp(14) })
        renderFeature(frame, item)
        if (!showAction) {
            val meta = frame.findViewWithTag<TextView>("feature-meta")
            meta.visibility = View.GONE
            copy.addView(metaChips(item), copy.indexOfChild(meta), margin(-1, -2, top = 12))
        }
        if (showAction && rotation.size > 1) rotateFeature(frame, rotation)
    }

    private fun savedFor(item: MediaItemData) = if (publicPreview) null else history.continueWatching().firstOrNull { it.identity?.imdbId == item.imdbId }
    /** Outside Continue Watching the action is always Play: a fresh link search that still resumes the saved position. */
    private fun playLabel(@Suppress("UNUSED_PARAMETER") item: MediaItemData) = "▶  Play"

    /** Cycles the billboard through the Top 10 titles every five seconds while no shelf is in use. */
    private fun rotateFeature(frame: FrameLayout, rotation: List<MediaItemData>) {
        heroRotation?.let(rotationHandler::removeCallbacks)
        var index = rotation.indexOfFirst { it.imdbId == (frame.getTag(R.id.pov_feature_item) as? MediaItemData)?.imdbId }.coerceAtLeast(0)
        val step = object : Runnable {
            override fun run() {
                if (heroRotation !== this || isDestroyed) return
                rotationHandler.postDelayed(this, FEATURE_INTERVAL_MS)
                val focus = currentFocus
                val inShelves = focus != null && within(focus, body) && !within(focus, frame)
                if (!resumed || !flix || featureHero() !== frame || inShelves) return
                index = (index + 1) % rotation.size
                val next = rotation[index]
                rotation[(index + 1) % rotation.size].let { prefetch(backdropFor(it), backdrop = true); prefetch(logoFor(it)) }
                frame.setTag(R.id.pov_feature_item, next)
                (frame.findViewWithTag<LinearLayout>("feature-actions")?.getChildAt(0) as? TextView)?.text = playLabel(next)
                val copy = frame.findViewWithTag<View>("feature-copy")
                copy.animate().alpha(0f).setDuration(180).withEndAction {
                    showFeature(next); copy.animate().alpha(1f).setDuration(260).start()
                }.start()
            }
        }
        heroRotation = step
        rotation.getOrNull(1)?.let { prefetch(backdropFor(it), backdrop = true); prefetch(logoFor(it)) }
        rotationHandler.postDelayed(step, FEATURE_INTERVAL_MS)
    }

    /** Details badges: an IMDb-style rating, year, runtime and type chips, then genre pills. */
    private fun metaChips(item: MediaItemData): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun chip(label: String, fill: Int, ink: Int, outline: Int = Color.TRANSPARENT) = text(label, 11, ink, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(9), dp(4), dp(9), dp(4)); background = rounded(fill, 4, outline)
        }
        item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { row.addView(chip("IMDb  $it", 0xFFF5C518.toInt(), Color.BLACK), margin(-2, -2, right = 8)) }
        listOf(item.year, item.runtime, if (item.type == "series") "Series" else "Movie").filter(String::isNotBlank).forEach {
            row.addView(chip(it, Color.TRANSPARENT, ink, withAlpha(Color.WHITE, 90)), margin(-2, -2, right = 8))
        }
        item.genres.take(if (DeviceLayout.phone) 1 else 3).forEach { row.addView(chip(it, withAlpha(Color.WHITE, 30), ink), margin(-2, -2, right = 8)) }
        return row
    }

    /** Where Play should start on a show: the episode in progress, else the first released episode of the chosen season. */
    private fun playTarget(result: MediaItemData): Pair<PlaybackIdentity, Boolean>? {
        if (result.type != "series") return null
        savedFor(result)?.identity?.takeIf { it.season > 0 || it.episode > 0 }?.let { return it to true }
        val season = EpisodePresentation.selectedSeason(result.episodes, selectedSeasons[result.imdbId]) ?: return null
        val first = result.episodes.filter { it.season == season }.sortedBy { it.episode }.firstOrNull { !EpisodePresentation.future(it.released) } ?: return null
        return PlaybackIdentity(result.imdbId, "series", result.title, first.season, first.episode) to false
    }

    /**
     * Home's backdrop sits in the top-right 70% of the screen, its baked-in shading fading into the page,
     * leaving the left and lower area darker for the copy and rows; details pages keep it full-screen.
     */
    private fun placeBackdrop(home: Boolean) {
        if (!flix) return
        val params = heroBackdrop.layoutParams as FrameLayout.LayoutParams
        if (DeviceLayout.phone) {
            val height = resources.displayMetrics.widthPixels * 9 / 16
            if (params.width != -1 || params.height != height) {
                params.width = -1; params.height = height; params.gravity = Gravity.TOP; heroBackdrop.layoutParams = params
            }
            return
        }
        val width = if (home) (resources.displayMetrics.widthPixels * HOME_BACKDROP_SCALE).toInt() else -1
        val height = if (home) width * 9 / 16 else -1
        val growsFromCorner = !home && params.width > 0
        // Opened from a page without a backdrop (Movies, Shows, search, lists): fade in while settling from a
        // slight zoom, so every details page arrives the way it does from Home.
        if (!home && !growsFromCorner && heroBackdrop.visibility != View.VISIBLE) {
            heroBackdrop.animate().cancel()
            heroBackdrop.pivotX = resources.displayMetrics.widthPixels / 2f; heroBackdrop.pivotY = 0f
            heroBackdrop.alpha = 0f; heroBackdrop.scaleX = 1.06f; heroBackdrop.scaleY = 1.06f
            heroBackdrop.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(EASE_OUT).start()
        }
        if (params.width != width || params.height != height) {
            val wasCorner = params.width > 0
            params.width = width; params.height = height; params.gravity = Gravity.TOP or Gravity.END
            heroBackdrop.layoutParams = params
            // Grow from Home's corner to full screen (or settle back), scaling about the top-right corner
            // where the two layouts meet, instead of snapping between sizes.
            if (wasCorner != home) {
                val screen = resources.displayMetrics.widthPixels.toFloat()
                heroBackdrop.animate().cancel()
                heroBackdrop.pivotX = if (home) width.toFloat() else screen; heroBackdrop.pivotY = 0f
                val from = if (home) 1f / HOME_BACKDROP_SCALE else HOME_BACKDROP_SCALE
                heroBackdrop.scaleX = from; heroBackdrop.scaleY = from
                heroBackdrop.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(EASE_OUT).start()
            }
        }
    }

    private fun featureHero(): FrameLayout? = if (isHome) body.findViewWithTag("featured-hero") else null
    // TMDB backdrops use the 780 px file the cards already load, so opening a title reuses the card's image.
    private fun backdropFor(item: MediaItemData) = item.background.replace("/t/p/w1280/", "/t/p/w780/").ifBlank {
        if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/background/medium/${item.imdbId}/img" else item.poster
    }
    private fun logoFor(item: MediaItemData) =
        if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/logo/medium/${item.imdbId}/img" else ""

    /** Writes a title into the pinned feature copy, preferring its artwork logo once it arrives. */
    private fun renderFeature(frame: View, item: MediaItemData) {
        val copy = frame.findViewWithTag<LinearLayout>("feature-copy")
        // The image loader owns the logo's view tag, so the logo is found by position.
        val logo = copy.getChildAt(0) as ImageView
        val title = frame.findViewWithTag<TextView>("feature-title")
        title.text = item.title
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(when {
            item.title.length > 30 -> if (DeviceLayout.phone) 21 else 28
            item.title.length > 16 -> if (DeviceLayout.phone) 25 else 36
            else -> theme.layout.titleSizeSp }).toFloat())
        logo.setTag(R.id.pov_feature_item, item)
        // The logo's space is held empty while it loads; plain text appears only if there is no logo,
        // so the title never flashes as text and then swaps to artwork.
        fun useText() { if (logo.getTag(R.id.pov_feature_item) == item && logo.visibility != View.VISIBLE) { logo.visibility = View.GONE; title.visibility = View.VISIBLE } }
        logo.visibility = View.INVISIBLE; title.visibility = View.GONE
        logo.removeCallbacks(logo.getTag(R.id.pov_logo_fallback) as? Runnable)
        val fallback = Runnable(::useText).also { logo.setTag(R.id.pov_logo_fallback, it) }
        image(logo, logoFor(item), feature = true) { loaded ->
            if (logo.getTag(R.id.pov_feature_item) != item) return@image
            logo.removeCallbacks(fallback)
            if (loaded) { logo.visibility = View.VISIBLE; title.visibility = View.GONE } else useText()
        }
        if (logo.visibility != View.VISIBLE && title.visibility != View.VISIBLE) logo.postDelayed(fallback, 1_500)
        val rating = item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }.orEmpty()
        frame.findViewWithTag<TextView>("feature-meta").text = listOf(rating, item.year, item.genres.take(2).joinToString(" · "), item.runtime)
            .filter(String::isNotBlank).joinToString("   ")
        frame.findViewWithTag<TextView>("feature-description").apply { text = item.description; visibility = if (item.description.isBlank()) View.GONE else View.VISIBLE }
        // A longer description may now reach the shelf above the focused one.
        if (isHome && flixScrollTarget > 0) frame.post { if (isHome && flixScrollTarget > 0) fadeShelvesAbove(flixScrollTarget + featureCopyBottom()) }
    }

    private fun scheduleFeature(item: MediaItemData) {
        prefetchDetails(item)
        if (!flix || !isHome) return
        featureHandler.removeCallbacksAndMessages(null)
        featureHandler.postDelayed({ showFeature(item) }, 280)
    }

    private fun showFeature(item: MediaItemData) {
        if (item == featureShown || isDestroyed) return
        featureShown = item
        featureHero()?.let { renderFeature(it, item) }
        val address = backdropFor(item)
        if (address.isBlank() || backdropArt.tag == address) return
        // The previous artwork stays until the next arrives, then dissolves in.
        fun reveal(loaded: Boolean) {
            if (!loaded || featureShown != item) return
            backdropArt.animate().cancel(); backdropArt.alpha = 0.35f
            backdropArt.animate().alpha(1f).setDuration(300).start()
        }
        image(backdropArt, address, keep = true, feature = true) { loaded ->
            if (loaded) reveal(true)
            else if (featureShown == item && item.poster.startsWith("https://")) image(backdropArt, item.poster, keep = true, feature = true, loaded = ::reveal)
        }
    }

    /** Flix home: focused shelves rise to a fixed line beneath the pinned feature copy. */
    private fun placeFlixRow(view: View): Boolean {
        if (!flix || !isHome) return false
        val hero = featureHero() ?: return false
        val actions = hero.findViewWithTag<View>("feature-actions")
        if (within(view, hero)) {
            actions?.animate()?.alpha(1f)?.setDuration(160)?.start()
            flixScrollTarget = -1
            fadeShelvesAbove(Int.MIN_VALUE)
            scroller.smoothScrollTo(0, 0)
            featureHandler.removeCallbacksAndMessages(null)
            (hero.getTag(R.id.pov_feature_item) as? MediaItemData)?.let(::showFeature)
            return true
        }
        var strip: View? = view
        while (strip != null && strip !is HorizontalScrollView) strip = strip.parent as? View
        if (strip !is HorizontalScrollView) return false
        val row = strip.getChildAt(0) as? ViewGroup ?: return false
        var card = view
        while (card.parent !== row) card = card.parent as? View ?: return false
        // Rows read left to right from the focused title.
        strip.smoothScrollTo((card.left - row.paddingLeft).coerceAtLeast(0), 0)
        val parent = strip.parent as ViewGroup
        val header = parent.getChildAt(parent.indexOfChild(strip) - 1)?.takeIf { it !is HorizontalScrollView }
        val top = topInBody(header ?: strip)
        actions?.animate()?.alpha(0f)?.setDuration(160)?.start()
        // The last shelves cannot rise all the way to the line, so hide only what would really sit under the feature copy.
        val target = (top - dp(FLIX_ROW_LINE)).coerceIn(0, (body.height - scroller.height).coerceAtLeast(0))
        flixScrollTarget = target
        fadeShelvesAbove(target + featureCopyBottom())
        scroller.smoothScrollTo(0, target)
        return true
    }

    private var flixScrollTarget = -1

    /** Where the pinned feature text ends on screen: a short title leaves room for the shelf above the last one. */
    private fun featureCopyBottom(): Int {
        val copy = featureHero()?.findViewWithTag<View>("feature-copy") ?: return dp(FLIX_ROW_LINE)
        val here = IntArray(2); val origin = IntArray(2)
        copy.getLocationOnScreen(here); scroller.getLocationOnScreen(origin)
        return (here[1] - origin[1] + copy.height + dp(8)).coerceAtMost(dp(FLIX_ROW_LINE))
    }

    /** Hides every shelf that starts above [cutoff] (body coordinates), i.e. that would overlap the pinned feature copy. */
    private fun fadeShelvesAbove(cutoff: Int) {
        for (index in 0 until body.childCount) {
            val child = body.getChildAt(index)
            if (child.tag == "featured-hero") continue
            val target = if (child.top < cutoff) 0f else 1f
            if (child.alpha != target) child.animate().alpha(target).setDuration(160).withLayer().start()
        }
    }
    private fun within(view: View, parent: View): Boolean {
        var node: View? = view
        while (node != null) { if (node === parent) return true; node = node.parent as? View }
        return false
    }
    private fun topInBody(view: View): Int {
        var y = 0; var node: View? = view
        while (node != null && node !== body) { y += node.top; node = node.parent as? View }
        return y
    }

    private fun railBackground(expanded: Boolean) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        if (expanded) intArrayOf(Color.BLACK, withAlpha(Color.BLACK, 235), withAlpha(Color.BLACK, 0)) else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT))

    private fun setRailExpanded(expanded: Boolean, animate: Boolean = true) {
        val view = rail ?: return
        if (railExpanded == expanded) return
        railExpanded = expanded
        railLabels.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
        view.background = railBackground(expanded)
        railAnimator?.cancel(); railAnimator = null
        if (!animate) { view.layoutParams = view.layoutParams.apply { width = if (expanded) dp(270) else 0 }; return }
        railAnimator = ValueAnimator.ofInt(view.layoutParams.width, if (expanded) dp(270) else 0).apply {
            duration = 170
            addUpdateListener { view.layoutParams = view.layoutParams.apply { width = it.animatedValue as Int } }
        }.also { it.start() }
    }

    private fun rankedRow(title: String, items: List<MediaItemData>) {
        if (items.isEmpty()) return
        body.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
        val row = horizontalRow()
        val (wide, narrow, tall) = if (DeviceLayout.phone) Triple(150, 128, 108) else Triple(200, 170, 144)
        items.take(10).forEachIndexed { index, item -> row.addView(rankTile(item, index + 1), margin(if (index == 9) wide else narrow, tall, right = 4)) }
    }

    private fun rankTile(item: MediaItemData, rank: Int): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; clipChildren = false
            tag = "top10:${item.type}:${item.imdbId}"; contentDescription = "Number $rank, ${item.title}"
        }
        val posterWidth = dp(if (DeviceLayout.phone) 72 else 96)
        frame.addView(RankNumberView(this, rank.toString(), bg, 0xFF8C8C8C.toInt()).apply { posterInset = posterWidth - dp(16).toFloat() }, FrameLayout.LayoutParams(-1, -1))
        val poster = FrameLayout(this).apply { background = rounded(panel, 4); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        poster.addView(art, FrameLayout.LayoutParams(-1, -1))
        image(art, thumbnail(item.poster.ifBlank { if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/poster/medium/${item.imdbId}/img" else "" }))
        frame.addView(poster, FrameLayout.LayoutParams(posterWidth, -1, Gravity.END))
        watchedBadge(if (item.type == "movie") "movie" else "series", item.imdbId)?.let {
            poster.addView(it, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
        }
        frame.setOnFocusChangeListener { view, focused ->
            poster.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null
            view.animate().scaleX(if (focused) 1.07f else 1f).scaleY(if (focused) 1.07f else 1f).setDuration(140).start()
            if (focused) scheduleFeature(item)
        }
        frame.setOnClickListener { navigate { details(item) } }
        return frame.pressable(4)
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning."; in 12..17 -> "Good afternoon."; else -> "Good evening."
    }

    private fun continuePlayback(identity: PlaybackIdentity) {
        val link = LastPlayedLinks(store).find(identity)
        if (link == null) { navigate { showSources(identity) }; return }
        cachedPlayback = identity
        // The saved file name and size give the resumed video its badges.
        playingMedia = link.name to link.size
        play(link.url, identity.title, identity, link.key, link.service, resumeImmediately = true, headers = link.headers)
    }
    private fun retainedRoots(): List<View> = listOf(body) + backStack.flatMap { it.retained?.children.orEmpty() }
    private fun hideContinue(marker: String) {
        fun hide(root: View) {
            if (root.getTag(R.id.pov_continue_identity) == marker) root.visibility = View.GONE
            if (root is ViewGroup) (0 until root.childCount).map(root::getChildAt).forEach(::hide)
        }
        retainedRoots().forEach(::hide)
    }
    private fun removeContinueViews(view: View) {
        val parent = view.parent as? ViewGroup
        val index = parent?.indexOfChild(view) ?: 0
        hideContinue(view.getTag(R.id.pov_continue_identity).toString())
        // Rows run newest first: focus moves to the newer card on the left, or the next one when this was first.
        parent?.let { group -> (index - 1 downTo 0).map(group::getChildAt).firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }?.requestFocus()
            ?: (index until group.childCount).map(group::getChildAt).firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }?.requestFocus() }
        if (!focusController.hasVisibleLeafFocus()) focusController.first()
    }
    private fun continueOptions(view: View, key: String, identity: PlaybackIdentity?, title: String) {
        view.setTag(R.id.pov_continue_identity, identity?.let(::identityKey) ?: key)
        view.setOnLongClickListener {
            val account = wetrakr.accountRevision()
            AlertDialog.Builder(this).setTitle(title)
                .setItems(arrayOf("Open Media", "Remove from Continue Watching")) { _, choice ->
                    if (choice == 0) {
                        if (identity != null) {
                            if (identity.type == "series") selectedSeasons[identity.imdbId] = identity.season
                            navigate { detailsFor(MediaItemData(identity.imdbId, identity.type, identity.title), identity.takeIf { it.type == "series" }) }
                        } else navigate { selectedCloud() }
                        return@setItems
                    }
                    if (identity != null && wetrakr.connected()) {
                        load(generation, {
                            wetrakr.removeContinueWatching(identity, account)
                            wetrakrOutbox.discard(identity)
                            history.hide(key, identity)
                        }) { remoteProgress.remove(identityKey(identity)); toast("Removed from ${wetrakr.name} Continue Watching"); removeContinueViews(view) }
                    } else { history.hide(key, identity); toast("Removed from Continue Watching on this TV"); removeContinueViews(view) }
                }.setNegativeButton("Cancel", null).show()
            true
        }
    }

    private fun checkStartupUpdate() {
        if (store.get("updates.startup") == "false") return  // on unless the viewer turns it off
        worker.execute {
            val latest = try { GitHubUpdates(this).latest() } catch (_: Exception) { null }
            if (latest != null) runOnUiThread {
                if (resumed && !isDestroyed && !isFinishing && !InAppPlaybackSession.hasActive()) {
                    AlertDialog.Builder(this).setTitle("Update available · ${latest.version}")
                        .setMessage("A new published release of POV Rajput is available.")
                        .setPositiveButton("View update") { _, _ -> startActivity(Intent(this, UpdateActivity::class.java)) }
                        .setNegativeButton("Later", null).show()
                }
            }
        }
    }

    private fun resumeRow(recent: List<PlaybackStore.Recent>) {
        section("Continue Watching")
        val row = horizontalRow()
        refreshContinue = { if (row.isAttachedToWindow && !row.hasFocus()) { row.removeAllViews(); fillResumeRow(row, history.continueWatching()) } }
        fillResumeRow(row, recent)
    }

    private fun fillResumeRow(row: LinearLayout, recent: List<PlaybackStore.Recent>) {
        recent.take(12).forEach { saved ->
            val identity = saved.identity
            val item = MediaItemData(identity?.imdbId.orEmpty(), identity?.type ?: "movie", saved.title,
                background = identity?.imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty())
            val remaining = listOf(identity?.takeIf { it.type == "series" }?.let { "S${it.season} E${it.episode}" }.orEmpty(),
                "${((saved.duration - saved.position).coerceAtLeast(0) / 60000)}m left").filter(String::isNotBlank).joinToString(" · ")
            val action: () -> Unit = {
                resumeSaved(saved)
            }
            val progress = saved.position.toDouble() / saved.duration.coerceAtLeast(1)
            val timeLeft = "${((saved.duration - saved.position).coerceAtLeast(0) / 60000)}m left"
            row.addView((if (resumeCards()) resumeCard(item, progress, identity?.let(::episodeLine) ?: "Cloud video", timeLeft, action) else tile(item, progress, remaining, action))
                .apply { tag = "resume:${saved.key}"; continueOptions(this, saved.key, saved.identity, saved.title) }, continueSlot())
        }
    }


    private fun resumeSaved(saved: PlaybackStore.Recent) {
        if (saved.key.startsWith("cloud:")) {
            val parts = saved.key.split(':')
            val folder = parts.getOrNull(2)?.toLongOrNull(); val file = parts.getOrNull(3)?.toLongOrNull()
            val kind = parts.getOrNull(1)
            if (parts.size == 4 && kind in setOf("torrents", "usenet", "webdl") && folder != null && file != null) {
                read(generation, { torbox.streamUrl(kind!!, folder, file) }) { url -> play(url, saved.title, saved.identity, saved.key, "torbox") }
                return
            }
        }
        saved.identity?.let { identity -> continuePlayback(identity) } ?: navigate { selectedCloud() }
    }

    private fun returnToVideo() {
        if (!InAppPlaybackSession.hasActive()) return
        if (PlayerActivity.openCurrent(this)) playerShown = true
    }

    private fun playbackOptions() {
        if (!InAppPlaybackSession.hasActive() || currentPlaybackOptionsDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this).setTitle("Now playing · ${InAppPlaybackSession.title}")
            .setItems(arrayOf("Return to video", "Stop playback")) { _, choice ->
                if (choice == 0) returnToVideo() else { InAppPlaybackSession.stop(); scheduleContinueRefresh() }
            }.setNegativeButton("Keep browsing", null).create()
        dialog.setOnShowListener { dialog.window?.decorView?.tag = "pov_mini_playback_options" }
        currentPlaybackOptionsDialog = dialog
        dialog.setOnDismissListener { if (currentPlaybackOptionsDialog === dialog) currentPlaybackOptionsDialog = null }
        dialog.show()
    }

    private fun updateMiniPlayer() {
        val active = !publicPreview && InAppPlaybackSession.hasActive()
        updatePlaybackNavigation(active)
        val bottomPadding = contentBottomPadding()
        if (body.paddingBottom != bottomPadding) body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, bottomPadding)
        if (!active) {
            miniPlayer?.let(InAppPlaybackSession::detachPlayerView); miniFrame?.visibility = View.GONE; miniFrame?.setTag(R.id.pov_mini_shown, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        val exo = InAppPlaybackSession.playerOrNull
        if (exo?.playWhenReady == true && exo.playerError == null && exo.playbackState != androidx.media3.common.Player.STATE_ENDED)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (miniFrame == null) {
            val frame = FrameLayout(this).apply {
                background = rounded(Color.BLACK, 8); clipToOutline = true; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                tag = "return-to-video"; contentDescription = "Now playing. Select to return to video. Hold Select or press Menu for playback options."
            }
            val video = PlayerView(this).apply {
                useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable = false; isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            frame.addView(video, FrameLayout.LayoutParams(-1, -1))
            val caption = column().apply {
                setBackgroundColor(0xB0000000.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val label = text("", 11, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            caption.addView(label)
            caption.addView(text(if (DeviceLayout.phone) "Tap to return · Hold for options" else "Select to return · Hold Select for options", 9, 0xFFD3DFED.toInt()).apply { maxLines = 1 })
            frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            frame.setOnFocusChangeListener { view, focused ->
                if (focused) { pendingFocus = null; wantsContentFocus = false }
                // Flix: a white ring and a neutral caption, matching the app's other focused controls.
                view.foreground = if (!focused) null else if (flix) crispOutline(8) else FocusRingDrawable(accent, dp(8).toFloat(), true, dp(2).toFloat())
                caption.setBackgroundColor(if (focused) (if (flix) 0xE6000000.toInt() else 0xF0091824.toInt()) else 0xB0000000.toInt())
            }
            frame.setOnClickListener { returnToVideo() }
            frame.setOnLongClickListener { playbackOptions(); true }
            if (DeviceLayout.phone) shellRoot.addView(frame, FrameLayout.LayoutParams(dp(176), dp(99), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(10); bottomMargin = dp(BOTTOM_BAR_HEIGHT + 8)
            }) else shellRoot.addView(frame, FrameLayout.LayoutParams(dp(247), dp(145), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20); bottomMargin = dp(15)
            })
            miniFrame = frame; miniPlayer = video; miniLabel = label
            miniFocus = MiniPlayerFocusRouter(body, frame)
            frame.post { focusController.revealCurrent() }
        }
        miniFrame?.let { frame ->
            if (frame.visibility != View.VISIBLE || frame.getTag(R.id.pov_mini_shown) != true) {
                // Arriving from the full player: the mini player grows into its corner instead of appearing.
                frame.setTag(R.id.pov_mini_shown, true); frame.visibility = View.VISIBLE
                frame.pivotX = frame.layoutParams.width.toFloat(); frame.pivotY = frame.layoutParams.height.toFloat()
                frame.alpha = 0f; frame.scaleX = 0.6f; frame.scaleY = 0.6f
                frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EASE_OUT).start()
            }
        }
        val status = when {
            exo?.playerError != null -> "Playback stopped"
            exo?.playbackState == androidx.media3.common.Player.STATE_ENDED -> "Finished"
            exo?.isPlaying == true -> "Playing"
            exo?.playWhenReady == true && exo.playbackState == androidx.media3.common.Player.STATE_BUFFERING -> "Loading"
            else -> "Paused"
        }
        miniLabel?.text = "$status · ${InAppPlaybackSession.title}"
        miniPlayer?.let(InAppPlaybackSession::attachPlayerView)
    }

    private fun updatePlaybackNavigation(active: Boolean) {
        val existing = navigationViews["nowplaying"]
        if (active == (existing != null)) return
        if (!active) {
            val restoreFocus = existing?.hasFocus() == true
            (existing?.parent as? ViewGroup)?.removeView(existing)
            navigationViews.remove("nowplaying")
            if (restoreFocus) navigationViews[destination]?.requestFocus()
            return
        }
        val host = navigationViews["home"]?.parent as? LinearLayout ?: return
        val navigationIndexes = (0 until host.childCount).filter { (host.getChildAt(it).tag as? String)?.startsWith("nav:") == true }
        if (navigationIndexes.isEmpty()) return
        val top = theme.layout.navigation == NavigationPlacement.TOP_BAR
        val bottom = theme.layout.navigation == NavigationPlacement.BOTTOM_BAR
        val target = Destination("nowplaying", "Now playing") { returnToVideo() }
        val cell = if (bottom) bottomNavigation(target) else navigation(target, top)
        val params = if (bottom) LinearLayout.LayoutParams(0, -1, 1f) else if (top) LinearLayout.LayoutParams(0, dp(38), 1f) else margin(-1, 35, bottom = 4)
        // TV: above Home; phone: the last tab.
        host.addView(cell, if (DeviceLayout.tv) navigationIndexes.first() else navigationIndexes.last() + 1, params)
    }

    private fun contentBottomPadding() = dp(if (!publicPreview && InAppPlaybackSession.hasActive()) (if (DeviceLayout.phone) 120 else 184) else 24)

    private fun homeCredit() {
        body.addView(text("POV Rajput  ·  Android TV port: Mark Mateo  ·  Original POV: kodifitzwell", 10, muted), margin(-1, -2, top = 20, bottom = 6))
    }

    private fun animePage(type: String = "series", query: String = "", skip: Int = 0) {
        current = { animePage(type, query, skip) }
        destination = "anime"; refreshNavigation()
        val id = page(if (query.isBlank()) "Anime" else "Anime · $query", "Discover anime series, films, and new worlds.")
        if (DeviceLayout.phone) {
            // Phone: search first, then three short tabs that fit the width, so nothing hides off-screen.
            body.addView(button("⌕   Search anime") { searchAnime(type) }, margin(-1, 42, bottom = 10))
            phoneTabs(listOf("series" to "Series", "movie" to "Movies", "special" to "Specials").map { (key, title) ->
                (if (type == key) "●  $title" else title) to { animePage(key) }
            })
        } else {
            val tabs = horizontalRow()
            // Search leads the row, wider than the category tabs.
            tabs.addView(button("⌕   Search anime") { searchAnime(type) }, margin(230, 40, right = 16))
            listOf("series" to "Series & seasons", "movie" to "Anime movies", "special" to "Specials & OVAs").forEach { (key, title) ->
                tabs.addView(button(if (type == key) "●  $title" else title) { animePage(key) }, margin(153, 40, right = 8))
            }
        }
        read(id, { if (query.isBlank()) anime.popular(type, skip) else anime.search(query, type, skip) }) { result ->
            if (result.items.isEmpty()) body.addView(text("No anime found. Try a different title or category.", 14, muted), margin(-1, -2, top = 20))
            val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
            val count = if (DeviceLayout.phone) 2 else (width / (cardWidth() + theme.layout.cardGapDp)).coerceAtLeast(1)
            // Flix columns stretch to the full content width, as the Movies and Shows grids do.
            val tileWidth = if (flix) (width - 6 - (count - 1) * theme.layout.cardGapDp) / count else cardWidth()
            val tileHeight = if (flix) tileWidth * 9 / 16 else theme.layout.cardHeightDp
            result.items.chunked(count).forEach { group ->
                val row = LinearLayout(this).apply { tag = "media-grid-row" }; body.addView(row, margin(-1, -2, top = 14))
                group.forEach { title ->
                    val media = MediaItemData("", title.type, title.title, title.year, title.poster, title.background, title.description, title.genres)
                    row.addView(tile(media, caption = animeFormat(title)) { navigate { animeDetails(title) } }.apply { tag = "anime:${title.kitsuId}" }, margin(tileWidth, tileHeight, right = theme.layout.cardGapDp))
                }
            }
            val paging = horizontalRow()
            if (skip > 0) paging.addView(button("Previous page") { animePage(type, query, (skip - 20).coerceAtLeast(0)) }, margin(150, 40, top = 16, right = 10))
            if (result.hasMore) paging.addView(button("More anime  ›") { animePage(type, query, result.nextOffset) }, margin(150, 40, top = 16))
            body.addView(text("Anime catalog: Kitsu. Episode lists and titles are confirmed before playback.", 10, muted), margin(-1, -2, top = 20))
        }
    }

    private fun searchAnime(type: String = "series") {
        val input = TvEditText(this).apply { hint = "Anime title"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Search anime").setView(input)
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf(String::isNotBlank)?.let { query -> navigate { animePage(type, query) } } }
            .setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun animeFormat(title: AnimeTitle): String = when (title.category) {
        "movie" -> "Anime movie"
        "special" -> if (title.subtype.equals("OVA", true)) "OVA / extra" else "Special / story chapter"
        else -> "Series / season"
    }

    private fun animeDetails(title: AnimeTitle) {
        current = { animeDetails(title) }
        val id = page(title.title, animeFormat(title))
        if (flix) {
            // The card's own picture shows at once (never the previous title's), then the sharp backdrop
            // fades in while the IMDb match is still being looked up.
            val card = MediaItemData("", title.type, title.title, title.year, title.poster, title.background, title.description, title.genres)
            placeBackdrop(home = false); heroBackdrop.visibility = View.VISIBLE
            backdropStandIn(card); image(backdropArt, backdropFor(card), feature = true)
        }
        body.addView(button("Choose a matching title manually") { matchAnime(title) }, margin(280, 40, bottom = 10))
        fun render(result: AnimeEpisodeCatalog) {
            // Play sits right under the synopsis, then one row of related entries, then the episodes.
            body.removeAllViews(); hero(result.media, false, synopsisLines = if (DeviceLayout.phone) 4 else 3)
            mediaActions(result.media)
            if (!flix) body.addView(text(animeFormat(title) + " · " + result.notice, 12, muted), margin(-1, -2, top = 9, bottom = 12))
            val relatedHost = column(); body.addView(relatedHost, margin(-1, -2, top = 10))
            relatedHost.addView(text("Finding related seasons and films…", 12, muted))
            loadRelatedAnime(title, relatedHost, id)
            if (title.type == "series") animeEpisodePicker(result)
            body.addView(button("Choose a matching title manually") { matchAnime(title) }, margin(280, 40, top = 15))
        }
        val cached = anime.cachedDetails(title)
        if (cached != null) { render(cached); focusAfterRender(id) } else read(id, { anime.details(title) }) { render(it) }
    }

    private fun loadRelatedAnime(title: AnimeTitle, host: LinearLayout, id: Int) {
        worker.execute {
            try {
                val related = anime.related(title)
                runOnUiThread {
                    if (id == generation && !isDestroyed) {
                        host.removeAllViews()
                        // Three wide buttons: the row scrolls sideways on a phone.
                        val row = horizontalRow(host)
                        fun add(short: String, label: String, choices: List<AnimeTitle>, key: String) {
                            row.addView(button("$short (${choices.size})") {
                                if (choices.isEmpty()) toast("No catalog-linked $label are available")
                                else AlertDialog.Builder(this).setTitle(label).setItems(choices.map { it.title }.toTypedArray()) { _, index ->
                                    navigate { animeDetails(choices[index]) }
                                }.setNegativeButton("Cancel", null).show()
                            }.apply { tag = "related:$key"; gravity = Gravity.CENTER }, margin(150, 38, right = 8))
                        }
                        add("Seasons", "Seasons & story parts", related.seasons, "seasons")
                        add("Movies", "Anime movies", related.movies, "movies")
                        add("Specials", "Specials & OVAs", related.specials, "specials")
                        if (related.otherSeries.isNotEmpty()) add("Spin-offs", "Spin-off series", related.otherSeries, "spinoffs")
                        if (related.truncated) host.addView(text("More related entries may be available through Anime search.", 11, muted), margin(-1, -2, top = 6))
                        focusAfterRender(id)
                    }
                }
            } catch (_: Exception) { runOnUiThread {
                if (id == generation && !isDestroyed) {
                    host.removeAllViews(); host.addView(button("Retry related seasons and movies") { loadRelatedAnime(title, host, id) }, margin(310, 40))
                    focusAfterRender(id)
                }
            } }
        }
    }

    private fun animeEpisodePicker(result: AnimeEpisodeCatalog) {
        if (result.groups.isEmpty()) {
            body.addView(text("No verified episodes are available for this selected entry. A film has its own movie action; episode numbers are never substituted.", 14, muted), margin(-1, -2, top = 14)); return
        }
        val key = "anime:${result.anime.kitsuId}"
        val groupIndex = selectedSeasons[key]?.coerceIn(0, result.groups.lastIndex) ?: 0
        val selector = button("") { }
        val host = column()
        fun render(index: Int) {
            selectedSeasons[key] = index
            val group = result.groups[index]
            selector.text = "${group.title} · ${group.episodes.size} episodes  ▾"
            selector.contentDescription = selector.text; selector.tag = "anime-group:$key"
            host.removeAllViews()
            host.addView(text("${result.anime.title} · ${group.title}", 17, ink, true), margin(-1, -2, top = 15, bottom = 9))
            group.episodes.forEach { choice ->
                val identity = choice.identity
                val extra = buildList {
                    if (choice.catalogEpisode != identity.episode) add("Part episode ${choice.catalogEpisode}")
                    choice.absoluteEpisode?.let { add("Overall episode $it") }
                    if (choice.released.isNotBlank()) add(choice.released.take(10))
                }.joinToString(" · ")
                // Flix shows each anime episode with its still, like regular series.
                if (flix) host.addView(episodeRow(identity, EpisodeData(identity.season, identity.episode, choice.title, choice.released, choice.thumbnail),
                    result.media, "Episode ${choice.catalogEpisode}", extra), margin(-1, if (DeviceLayout.phone) -2 else 108, bottom = 8))
                else host.addView(episodeButton(identity, choice.title, choice.released, extra), margin(-1, 62, bottom = 7))
            }
            focusAfterRender(generation)
        }
        selector.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Episodes in this entry").setSingleChoiceItems(
                result.groups.map { "${it.title} · ${it.episodes.size} episodes" }.toTypedArray(), selectedSeasons[key] ?: 0
            ) { dialog, index -> dialog.dismiss(); render(index); selector.requestFocus() }.setNegativeButton("Cancel", null).show()
        }
        body.addView(selector, margin(if (DeviceLayout.phone) -1 else 380, 42, top = 10)); body.addView(host)
        render(groupIndex)
    }

    private fun matchAnime(title: AnimeTitle) {
        val input = TvEditText(this).apply { setText(title.title); hint = "Title to search"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Find the correct ${if (title.type == "movie") "film" else "show"}")
            .setMessage("Choose the matching catalog title. For sequels and split seasons, check the show's season and episode list before playing.")
            .setView(input).setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) load(generation, { catalog.search(query, title.type) }) { matches ->
                    if (matches.isEmpty()) toast("No match found. Try the English or original title.")
                    else AlertDialog.Builder(this).setTitle("Confirm the correct title")
                        .setItems(matches.map { "${it.title} · ${it.year}" }.toTypedArray()) { _, index -> navigate { details(matches[index]) } }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun catalogPage(type: String, query: String = "", skip: Int = 0) {
        current = { catalogPage(type, query, skip) }
        destination = type; refreshNavigation()
        if (skip == 0 && query.isBlank()) catalogOffsets[type]?.clear()
        val id = page(if (query.isBlank()) if (type == "movie") "Movies" else "TV shows" else "Results for “$query”", "Find something worth your evening.")
        val actions = horizontalRow()
        actions.addView(button("Search movies & TV") { search() }, margin(190, 38, right = 8))
        if (skip > 0) actions.addView(button("Previous page") { catalogPage(type, query, catalogOffsets[type]?.removeLastOrNull() ?: 0) }, margin(140, 38))
        read(id, { if (query.isBlank()) catalog.popular(type, skip) else catalog.search(query, type) }) { items ->
            mediaGrid(items)
            if (query.isBlank() && items.isNotEmpty()) body.addView(button("More titles  ›") { catalogOffsets.getOrPut(type) { ArrayDeque() }.addLast(skip); catalogPage(type, skip = skip + items.size) }, margin(180, 42, top = 20))
        }
    }

    private fun search(initialQuery: String = "") {
        // A full-screen search overlay: one large field, then Search, Voice search and Cancel.
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val input = TvEditText(this).apply {
            hint = "Movie or TV show"; setText(initialQuery); setSelection(text.length); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18).toFloat()); setTextColor(ink); setHintTextColor(muted)
            background = null; setPadding(dp(10), 0, dp(10), 0); includeFontPadding = false
        }
        fun submit() {
            val query = input.text.toString().trim().take(200)
            if (query.isEmpty()) return
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            dialog.dismiss(); navigate { searchResults(query) }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(withAlpha(bg, 242)) }
        val card = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(text("Search", 30, ink, true), margin(-2, -2))
        card.addView(text("Find movies and TV shows by title", 13, muted), margin(-2, -2, top = 6, bottom = 22))
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0)
            background = rounded(withAlpha(Color.WHITE, 26), 28, withAlpha(Color.WHITE, 40))
        }
        field.addView(NavigationIconView(this, "search", ink), margin(22, 22))
        field.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        input.setOnFocusChangeListener { _, focused ->
            field.background = rounded(withAlpha(Color.WHITE, if (focused) 38 else 26), 28, if (focused) Color.WHITE else withAlpha(Color.WHITE, 40))
        }
        card.addView(field, margin(-1, 56))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false }
        val searchAction = primaryButton("⌕  Search") { submit() }.apply { contentDescription = "Search" }
        actions.addView(searchAction, margin(if (DeviceLayout.phone) 110 else 150, 40, right = if (DeviceLayout.phone) 8 else 12))
        input.onBack = { hideKeyboard(input); searchAction.requestFocus() }
        actions.addView(button(if (DeviceLayout.phone) "🎤  Voice" else "🎤  Voice search") { dialog.dismiss(); voiceSearch() }.apply { gravity = Gravity.CENTER; tag = "search-voice" },
            margin(if (DeviceLayout.phone) 110 else 170, 40, right = if (DeviceLayout.phone) 8 else 12))
        actions.addView(button("Cancel") { dialog.dismiss() }.apply { gravity = Gravity.CENTER }, margin(if (DeviceLayout.phone) 90 else 110, 40))
        card.addView(actions, margin(-2, -2, top = 22))
        if (DeviceLayout.tv) card.addView(text("Press OK to type · the keyboard's Search key searches straight away", 11, muted), margin(-2, -2, top = 18))
        // The phone keeps the field near the top, clear of the on-screen keyboard.
        if (DeviceLayout.phone) root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(70); leftMargin = dp(16); rightMargin = dp(16) })
        else root.addView(card, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER).apply { bottomMargin = dp(60) })
        dialog.setContentView(root)
        input.setOnEditorActionListener { _, action, event ->
            if (action in setOf(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH, android.view.inputmethod.EditorInfo.IME_ACTION_DONE, android.view.inputmethod.EditorInfo.IME_ACTION_GO)) { submit(); true }
            else if (event?.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (event?.action == KeyEvent.ACTION_UP) submit()
                true
            } else false
        }
        input.setOnKeyListener { _, key, event ->
            // TV: the field starts selected with the keyboard closed; OK opens it.
            if (DeviceLayout.tv && key == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_UP) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)
                return@setOnKeyListener true
            }
            if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) submit()
                true
            } else false
        }
        if (DeviceLayout.phone) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        else { input.showSoftInputOnFocus = false; dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) }
        searchDialog = dialog; dialog.show(); input.requestFocus()
    }
    /**
     * Voice search listens in the app with the device's speech service (Google on Android TV), showing a mic
     * and the words as they are heard; the system voice screen is only a fallback when that service is missing.
     */
    private fun voiceSearch() {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) listen()
            else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        systemVoiceSearch()
    }

    @Deprecated("Permission result compatibility for Android TV")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) listen()
        else { toast("Microphone access is off. Using the TV's voice search instead."); systemVoiceSearch() }
    }

    private fun listen() {
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(withAlpha(bg, 240)) }
        val column = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val micSize = dp(64)
        val micFrame = FrameLayout(this)
        val pulse = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(accent, 70)) } }
        val mic = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) } }
        mic.addView(MicIconView(this), FrameLayout.LayoutParams(micSize / 2, micSize / 2, Gravity.CENTER))
        micFrame.addView(pulse, FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER))
        micFrame.addView(mic, FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER))
        column.addView(micFrame, margin(110, 110))
        val status = text("Listening…", 15, muted, true)
        val heard = text("Say a movie or show", 30, ink, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        column.addView(status, margin(-2, -2, top = 16))
        column.addView(heard, margin(560, -2, top = 12))
        val retry = primaryButton("🎤  Try again") { }.apply { visibility = View.GONE }
        column.addView(retry, margin(170, 40, top = 22))
        root.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        dialog.setContentView(root)
        // The pulse follows the speaker's volume.
        val idle = ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pulse.scaleX = it.animatedValue as Float; pulse.scaleY = pulse.scaleX }
        }
        fun start() {
            status.text = "Listening…"; heard.text = "Say a movie or show"; retry.visibility = View.GONE; idle.start()
            recognizer.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName))
        }
        retry.setOnClickListener { start() }
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() { status.text = "Listening…" }
            override fun onRmsChanged(rmsdB: Float) {
                val level = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.45f
                idle.pause(); pulse.scaleX = level; pulse.scaleY = level
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { status.text = "Searching…"; idle.resume() }
            override fun onError(error: Int) {
                idle.cancel(); pulse.scaleX = 1f; pulse.scaleY = 1f
                status.text = if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Didn't catch that" else "Voice search is unavailable right now"
                heard.text = "Press OK to try again"; retry.visibility = View.VISIBLE; retry.requestFocus()
            }
            override fun onResults(results: Bundle?) {
                val query = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().take(200)
                if (query.isBlank()) { onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH); return }
                heard.text = query; dialog.dismiss(); navigate { searchResults(query) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf(String::isNotBlank)?.let { heard.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        dialog.setOnDismissListener { idle.cancel(); recognizer.cancel(); recognizer.destroy() }
        dialog.show(); start()
    }

    private fun systemVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search movies & TV shows")
        try { @Suppress("DEPRECATION") startActivityForResult(intent, VOICE_REQUEST) }
        catch (_: android.content.ActivityNotFoundException) { toast("Voice search is unavailable on this TV. Use the keyboard."); search() }
    }
    @Deprecated("Activity result compatibility for Android TV")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST) {
            val query = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty().take(200)
            if (resultCode == RESULT_OK && query.isNotBlank()) navigate { searchResults(query) } else search()
        } else if (requestCode == PLAYBACK_REQUEST) {
            // A finished title reaches the service's history a few seconds after the player closes.
            if (!publicPreview) window.decorView.postDelayed({ if (!isDestroyed) refreshWatched() }, 8_000)
            val identity = cachedPlayback ?: runCatching { data?.getStringExtra("cached_identity")?.let { PlaybackIdentity.fromJson(org.json.JSONObject(it)) } }.getOrNull()
            cachedPlayback = null; launchingPlayer = false
            if (resultCode == RESULT_FIRST_USER && identity != null) {
                LastPlayedLinks(store).remove(identity); toast("Last stream unavailable. Finding fresh sources…"); navigate { showSources(identity) }
            }
        }
    }
    private companion object { const val VOICE_REQUEST = 701; const val PLAYBACK_REQUEST = 702; const val MIC_REQUEST = 703; const val FLIX_ROW_LINE = 236; const val FEATURE_INTERVAL_MS = 5_000L; const val AUTOPLAY_FIRST = "sources.autoplay_first"; const val AUTOPLAY_MAX_GB = "sources.autoplay_max_gb"; const val AUTOPLAY_MAX_GB_EPISODE = "sources.autoplay_max_gb_episode"; const val AUTOPLAY_MAX_MBPS = "sources.autoplay_max_mbps"; const val AUTOPLAY_LIMIT_ON = "sources.autoplay_limit_on"; const val BACKDROP_WIDTH = 1280; const val CONTINUE_CACHE = "wetrakr.continue_cache"
        const val TMDB_KEY = "tmdb.key"; const val TMDB_REGION = "tmdb.region"
        const val HOME_KEEP_MS = 15 * 60_000L
        const val HOME_BACKDROP_SCALE = 0.72f
        const val COMPACT_HERO_HEIGHT = 236
        val EASE_OUT = android.view.animation.DecelerateInterpolator(1.6f)
        const val DETAILS_KEEP_MS = 10 * 60_000L
        /** Bump when the set of Home rows changes, so rows saved under the old set are not shown. */
        const val SHELF_SET_VERSION = "2"
        const val RESUME_CARD_WIDTH = 300; const val RESUME_CARDS = "home.resume_cards"
        const val SYNOPSIS_MORE_CHARS = 220; const val CONTINUE_LIMIT = 30
        const val PHONE_INLINE_VALUE = 18; const val BOTTOM_BAR_HEIGHT = 56; const val PHONE_BAR_COLOR = 0xFF0B0B0B.toInt()
        /** Where the phone's feature copy starts: over the foot of the 225-unit banner. */
        const val PHONE_COPY_TOP = 150 }

    private fun searchResults(query: String, filter: String = "all", cached: CatalogApi.SearchResults? = null) {
        current = { searchResults(query, filter, cached) }
        destination = "search"; refreshNavigation()
        val id = page("Results for “$query”", "Movies and TV shows in one search.")
        val actions = horizontalRow()
        for ((value, label) in listOf("all" to "All", "movie" to "Movies", "series" to "TV shows")) {
            actions.addView(button(if (value == filter) "✓ $label" else label) { searchResults(query, value, cached) }, margin(130, 38, right = 8))
        }
        actions.addView(button("New search") { search(query) }, margin(150, 38))
        fun render(result: CatalogApi.SearchResults) {
            // Rebind filters to these results so switching categories does not repeat network requests.
            current = { searchResults(query, filter, result) }
            for (index in 0..2) actions.getChildAt(index).setOnClickListener {
                searchResults(query, listOf("all", "movie", "series")[index], result)
            }
            if (result.unavailableTypes.isNotEmpty()) {
                val missing = result.unavailableTypes.joinToString(" and ") { if (it == "movie") "Movie" else "TV show" }
                body.addView(text("$missing results are temporarily unavailable.", 13, muted), margin(-1, -2, top = 12))
                body.addView(button("Retry search") { searchResults(query, filter) }, margin(150, 38))
            }
            mediaGrid(result.items.filter { filter == "all" || it.type == filter }, showType = true)
        }
        if (cached == null) read(id, { catalog.searchAll(query) }, ::render) else { render(cached); focusAfterRender(id) }
    }

    private fun details(item: MediaItemData) = detailsFor(item, null)
    private fun detailsFor(item: MediaItemData, episode: PlaybackIdentity?) {
        current = { detailsFor(item, episode) }
        fun render(id: Int, result: MediaItemData) {
            body.removeAllViews(); hero(result, false); mediaActions(result, episode)
            if (result.type == "series") { seasonPicker(result); if (flix) body.post { if (id == generation) riseIn() } }
            else if (flix && DeviceLayout.tv) alignToBottom(id, reveal = ::riseIn)
            else if (flix) body.post { if (id == generation) riseIn() }
        }
        // Details fetched while the poster had focus (or opened recently) show at once.
        cachedDetails(item)?.let { result -> val id = page(""); backdropStandIn(item, result); render(id, result); focusAfterRender(id); return }
        // Otherwise the backdrop carries over straight away and the details rise in when they arrive,
        // with no "Loading" page in between.
        val id = page("")
        if (flix) { placeBackdrop(home = false); heroBackdrop.visibility = View.VISIBLE; backdropStandIn(item); image(backdropArt, backdropFor(item), feature = true) }
        load(id, { fetchDetails(item) }, readOnly = true, quiet = flix) { result -> render(id, result) }
    }

    private class CachedDetails(val result: MediaItemData, val at: Long)
    private val detailsCache = LruCache<String, CachedDetails>(60)
    private val detailsInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val prefetchHandler = Handler(Looper.getMainLooper())
    private fun detailsKey(item: MediaItemData) = "${item.type}:${item.imdbId}"
    private fun cachedDetails(item: MediaItemData): MediaItemData? = synchronized(detailsCache) {
        detailsCache.get(detailsKey(item))?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < DETAILS_KEEP_MS }?.result
    }
    private fun fetchDetails(item: MediaItemData): MediaItemData = catalog.details(item).also { result ->
        synchronized(detailsCache) { detailsCache.put(detailsKey(item), CachedDetails(result, android.os.SystemClock.elapsedRealtime())) }
    }

    /** Downloads and shades a details backdrop ahead of time, so the page opens with its picture ready. */
    private fun preloadBackdrop(address: String) {
        if (!flix || !address.startsWith("https://")) return
        val key = "backdrop:$address"
        if (bitmapCache.get(key) != null || !backdropsInFlight.add(key)) return
        featureImages.execute {
            try { fetchBitmap(address, BACKDROP_WIDTH)?.let { shadedBackdrop(it, false) }?.let { bitmapCache.put(key, it) } }
            catch (_: Exception) { /* The page loads it again when opened. */ } finally { backdropsInFlight.remove(key) }
        }
    }
    private val backdropsInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Until the sharp backdrop is ready, the card's own picture (already loaded) fills the screen, dimmed,
     * and the backdrop fades in over it; the page never opens on an empty background.
     */
    private fun backdropStandIn(item: MediaItemData, result: MediaItemData = item) {
        if (!flix || bitmapCache.get("backdrop:${backdropFor(result)}") != null) return
        val card = listOf(item.background, backdropFor(item)).filter(String::isNotBlank).map(::thumbnail)
            .firstNotNullOfOrNull { bitmapCache.get(it) }
        backdropArt.animate().cancel(); backdropArt.setTag(R.id.pov_backdrop_key, null)
        // Without this title's own picture, the page opens dark rather than on the previous title's art.
        if (card != null) { backdropArt.setImageBitmap(card); backdropArt.alpha = 0.55f }
        else { backdropArt.setImageDrawable(null); backdropArt.tag = null; backdropArt.alpha = 0f }
    }

    /** A short rest on a poster fetches its details in the background, so opening it is usually instant. */
    private fun prefetchDetails(item: MediaItemData) {
        prefetchHandler.removeCallbacksAndMessages(null)
        if (!item.imdbId.matches(Regex("tt\\d+")) || cachedDetails(item) != null) return
        prefetchHandler.postDelayed({
            val key = detailsKey(item)
            if (cachedDetails(item) != null || !detailsInFlight.add(key)) return@postDelayed
            metadataWorker.execute {
                try { preloadBackdrop(backdropFor(fetchDetails(item))) }
                catch (_: Exception) { /* Opening it fetches again. */ } finally { detailsInFlight.remove(key) }
            }
        }, 300)
    }

    private fun mediaActions(result: MediaItemData, episode: PlaybackIdentity? = null) {
        val identity = PlaybackIdentity(result.imdbId, result.type, result.title)
        val actions = horizontalRow()
        // Flix: one white Play (Resume or the right episode for shows), then compact icon actions.
        fun play(label: String, width: Int, action: () -> Unit) = actions.addView(
            if (flix) primaryButton(label, action) else button(label, action).apply { gravity = Gravity.CENTER }, margin(width, 44, right = 12))
        if (result.type == "movie") play("▶  Play", 130) { navigate { showSources(savedFor(result)?.identity ?: identity) } }
        if (episode != null) play("▶  Play S${episode.season} E${episode.episode}", 180) { navigate { showSources(episode) } }
        else if (flix) playTarget(result)?.let { (target, _) ->
            play("▶  Play S${target.season} E${target.episode}", 200) {
                navigate { showSources(target) }
            }
        }
        // The flix details page already shows the full synopsis.
        if (result.description.isNotBlank() && !flix) actions.addView(button("Synopsis") {
            synopsisDialog(result.title, result.description)
        }, margin(110, 44, right = 8))
        val tracker = weTrakrClient()
        if (tracker.connected()) {
            actions.addView(button(if (flix) "＋  Watchlist" else "Add to watchlist") { load(generation, { tracker.setWatchlist(identity, true) }) { toast("Added to your ${tracker.name} watchlist") } }.apply { if (flix) gravity = Gravity.CENTER }, margin(155, 44, right = 8))
            actions.addView(button(if (flix) "－  Watchlist" else "Remove from watchlist") { confirm("Remove ${result.title} from your ${tracker.name} watchlist?") { load(generation, { tracker.setWatchlist(identity, false) }) { removeFromListViews("watchlist:${identity.type}", identity); toast("Removed from your ${tracker.name} watchlist") } } }.apply { if (flix) gravity = Gravity.CENTER }, margin(if (flix) 155 else 190, 44, right = 8))
            if (tracker.supportsLists) actions.addView(button(if (flix) "☰  Remove from playlist" else "Remove from playlist") {
                read(generation, { tracker.lists() }) { lists ->
                    if (lists.isEmpty()) toast("No personal playlists on ${tracker.name}") else AlertDialog.Builder(this).setTitle("Choose your playlist")
                        .setItems(lists.map { it.second }.toTypedArray()) { _, index ->
                            confirm("Remove ${result.title} from ${lists[index].second}?") {
                                load(generation, { tracker.removeListItem(lists[index].first, identity) }) { removeFromListViews("playlist:${lists[index].first}", identity); toast("Removed from ${lists[index].second}") }
                            }
                        }.setNegativeButton("Cancel", null).show()
                }
            }, margin(190, 44, right = 8))
            if (result.type == "movie" || episode != null) actions.addView(button(if (flix) "✓  Watched" else "Mark watched") { confirm("Mark ${result.title}${episode?.let { " S${it.season} E${it.episode}" }.orEmpty()} as watched?") { load(generation, { tracker.markWatched(episode ?: identity) }) { hideContinue(identityKey(episode ?: identity)); markedWatched(episode ?: identity); toast("${tracker.name} history updated") } } }, margin(150, 44))
        }
    }

    private fun seasonPicker(result: MediaItemData) {
        val seasons = EpisodePresentation.seasons(result.episodes)
        if (seasons.isEmpty()) { body.addView(text("Episode information is currently unavailable.", 14, muted), margin(-1, -2, top = 18)); return }
        val selector = button("") { }
        val host = column()
        val tabs = mutableListOf<Pair<Int, TextView>>()
        var shown: Int? = null
        fun render(season: Int) {
            val previous = shown; shown = season
            selectedSeasons[result.imdbId] = season
            val label = if (season == 0) "Specials & extras" else "Season $season"
            val episodes = result.episodes.filter { it.season == season }.sortedBy { it.episode }
            selector.text = "$label · ${episodes.size} episodes  ▾"; selector.contentDescription = selector.text; selector.tag = "season:${result.imdbId}"
            host.removeAllViews()
            if (flix) tabs.forEach { (value, tab) -> paintSeasonTab(tab, value == season, tab.hasFocus()) }
            else host.addView(text(label, 18, ink, true), margin(-1, -2, top = 16, bottom = 10))
            episodes.forEach { episode ->
                val identity = PlaybackIdentity(result.imdbId, "series", result.title, episode.season, episode.episode)
                if (flix) host.addView(episodeRow(identity, episode, result), margin(-1, -2, bottom = 8))
                else host.addView(episodeButton(identity, episode.title, episode.released, episode.released.take(10)), margin(-1, 62, bottom = 7))
            }
            // A later season slides in from the right, an earlier one from the left.
            if (flix && previous != null) {
                host.animate().cancel(); host.alpha = 0f; host.translationX = dp(if (season > previous) 36 else -36).toFloat()
                host.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(EASE_OUT).start()
            }
            focusAfterRender(generation)
        }
        selector.setOnClickListener {
            val selected = selectedSeasons[result.imdbId]
            AlertDialog.Builder(this).setTitle("Choose a season").setSingleChoiceItems(seasons.map { season ->
                if (season == 0) "Specials & extras" else "Season $season"
            }.toTypedArray(), seasons.indexOf(selected).coerceAtLeast(0)) { dialog, index ->
                dialog.dismiss(); render(seasons[index]); selector.requestFocus()
            }.setNegativeButton("Cancel", null).show()
        }
        if (flix) {
            // Seasons sit in a row of tabs instead of a pop-up chooser.
            body.addView(text("Episodes", 20, ink, true), margin(-1, -2, top = 18, bottom = 4))
            val row = horizontalRow()
            seasons.forEach { season ->
                val tab = text(if (season == 0) "Specials" else "Season $season", 13, ink, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0); isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                    tag = "season:${result.imdbId}:$season"; contentDescription = text
                }
                tab.setOnFocusChangeListener { view, focused -> paintSeasonTab(tab, selectedSeasons[result.imdbId] == season, focused)
                    view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start() }
                tab.setOnClickListener { if (selectedSeasons[result.imdbId] != season) render(season) }
                tabs += season to tab
                row.addView(tab, margin(-2, 36, right = 8))
            }
            body.addView(host, margin(-1, -2, top = 8))
            tabs.firstOrNull()?.second?.let { first ->
                first.id = View.generateViewId()
                (0 until body.childCount).map(body::getChildAt).filterIsInstance<ViewGroup>()
                    .firstOrNull { row -> (0 until row.childCount).any { (row.getChildAt(it).tag as? String)?.startsWith("primary:") == true } }
                    ?.let { actions -> (actions.childCount - 1 downTo 0).map(actions::getChildAt).firstOrNull { it.isFocusable }?.nextFocusRightId = first.id }
            }
        } else { body.addView(selector, margin(370, 44, top = 15)); body.addView(host) }
        render(EpisodePresentation.selectedSeason(result.episodes, selectedSeasons[result.imdbId])!!)
    }

    private fun paintSeasonTab(tab: TextView, selected: Boolean, focused: Boolean) {
        tab.background = rounded(if (selected) Color.WHITE else withAlpha(Color.WHITE, if (focused) 70 else 24), 18)
        tab.setTextColor(if (selected) Color.BLACK else ink)
        tab.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(18).toFloat(), true, dp(1).toFloat()) else null
    }

    /** Flix episode list: a still from the episode beside its number, title and air date. */
    private fun episodeRow(identity: PlaybackIdentity, episode: EpisodeData, show: MediaItemData, label: String = "", note: String = ""): View {
        val tracker = weTrakrClient()
        val future = EpisodePresentation.future(episode.released)
        val number = label.ifBlank { if (identity.season == 0) "Special ${identity.episode}" else "Episode ${identity.episode}" }
        val detail = if (future) "Not yet released" else note.ifBlank { episode.released.take(10) }
        val row = LinearLayout(this).apply {
            gravity = Gravity.TOP; minimumHeight = dp(if (DeviceLayout.phone) 80 else 107); setPadding(dp(6), dp(6), dp(12), dp(6)); background = rounded(withAlpha(Color.WHITE, 14), 6)
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            tag = "episode:${identity.imdbId}:${identity.season}:${identity.episode}"
            contentDescription = "$number. ${episode.title}. $detail"
        }
        val still = FrameLayout(this).apply { background = rounded(panel, 4); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        still.addView(art, FrameLayout.LayoutParams(-1, -1))
        // Stills use the priority pool so they never queue behind the Home page's thumbnails.
        image(art, episode.thumbnail.ifBlank { backdropFor(show) }, feature = true)
        if (future) still.addView(View(this).apply { setBackgroundColor(0x99000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        watchedBadge("episode", identity.imdbId, identity.season, identity.episode)?.let {
            still.addView(it, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
        }
        if (DeviceLayout.phone) row.addView(still, LinearLayout.LayoutParams(dp(120), dp(68)))
        else row.addView(still, LinearLayout.LayoutParams(dp(168), dp(95)))
        val copy = column().apply { setPadding(dp(if (DeviceLayout.phone) 10 else 14), dp(2), 0, 0) }
        copy.addView(text(listOf(number, detail).filter(String::isNotBlank).joinToString("  ·  "), 11, muted, true))
        copy.addView(text(episode.title, 15, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 4))
        // Every episode's synopsis is visible in the list, as in a TV guide.
        if (episode.overview.isNotBlank() && !future) copy.addView(text(episode.overview, 12, 0xFFD2D2D2.toInt()).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
        }, margin(-1, -2, top = 5))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) withAlpha(Color.WHITE, 46) else withAlpha(Color.WHITE, 14), 6)
            still.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null
        }
        row.setOnClickListener { if (future) toast("This episode has not been released yet") else navigate { showSources(identity) } }
        row.pressable(6)
        if (tracker.connected() && !future) row.setOnLongClickListener {
            confirm("Mark S${identity.season} E${identity.episode} as watched?") { load(generation, { tracker.markWatched(identity) }) { markedWatched(identity); toast("Episode marked watched") } }; true
        }
        return row
    }

    private fun episodeButton(identity: PlaybackIdentity, title: String, released: String, detail: String): View {
        val tracker = weTrakrClient()
        val future = EpisodePresentation.future(released)
        val number = if (identity.season == 0) "Special ${identity.episode}" else "S${identity.season} · E${identity.episode}"
        return button("$number   $title\n${if (future) "Not yet released" else detail}") {
            if (future) toast("This episode has not been released yet") else navigate { showSources(identity) }
        }.apply {
            tag = "episode:${identity.imdbId}:${identity.season}:${identity.episode}"
            contentDescription = "$number. $title. ${if (future) "Not yet released" else detail}"
            if (tracker.connected() && !future) setOnLongClickListener {
                confirm("Mark $number as watched?") { load(generation, { tracker.markWatched(identity) }) { markedWatched(identity); toast("Episode marked watched") } }; true
            }
        }
    }

    private fun showSources(identity: PlaybackIdentity) {
        current = { showSources(identity) }
        val suffix = if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""
        val connected = DebridServices.connected(store)
        val order = connected.joinToString(" → ") { DebridServices.name(it) }
        // Without a debrid service, addons can still supply direct links; torrents are left out.
        val id = page(identity.title + suffix, if (connected.isEmpty()) "Direct links only. Torrent sources need a debrid service." else "Cached source priority: $order.")
        // The title's runtime (for each link's estimated bitrate) loads alongside the source search, which takes far
        // longer, so it is ready when the list appears however the sources were opened.
        val title = MediaItemData(identity.imdbId, identity.type, identity.title)
        val details = if (cachedDetails(title) != null) null else metadataWorker.submit(java.util.concurrent.Callable { runCatching { fetchDetails(title) }.getOrNull() })
        read(id, { resolver.search(identity).also { runCatching { details?.get(1_500, java.util.concurrent.TimeUnit.MILLISECONDS) } } }) { result ->
            sourceResults(identity, result.items, result.unavailableProviders, autoplay = store.get(AUTOPLAY_FIRST) == "true")
        }
    }

    private fun sourceResults(identity: PlaybackIdentity, sources: List<SourceItem>) = sourceResults(identity, sources, emptyList())

    private fun sourceResults(identity: PlaybackIdentity, sources: List<SourceItem>, unavailable: List<String>, autoplay: Boolean = false) {
        val providerRevision = store.get(SourceProviders.KEY) + "\u0000" + store.get("sources.endpoint")
        current = {
            if (providerRevision != store.get(SourceProviders.KEY) + "\u0000" + store.get("sources.endpoint")) showSources(identity)
            else sourceResults(identity, sources, unavailable)
        }
        val connected = DebridServices.connected(store)
        val directOnly = connected.isEmpty()
        val playable = if (directOnly) sources.filter { it.directUrl.isNotEmpty() } else sources
        val checkOnPlay = connected.filter { it in LinkDebrids.CHECK_ON_PLAY }
        val suffix = if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""
        val id = page(identity.title + suffix, if (directOnly) "Direct links only. Torrent sources need a debrid service."
            else "Cached source priority: ${connected.joinToString(" → ") { DebridServices.name(it) }}.")
        val sorting = SourceSortPreferences(this)
        body.addView(button("Sort · ${sorting.label}  ›") {
            SourceSortPreferences.show(this) { sourceResults(identity, sources, unavailable) }
        }, margin(-1, 42, bottom = 8))
        if (unavailable.isNotEmpty()) body.addView(text("Unavailable providers: ${unavailable.joinToString(", ")}. Other providers were searched.", 14, muted), margin(-1, -2, bottom = 8))
        if (sources.isEmpty()) body.addView(text("No sources were returned. Try again later or check the source provider in Settings.", 15, muted))
        else if (playable.isEmpty()) {
            body.addView(text("Only torrent sources were found. Connect a debrid service to play them.", 15, muted))
            body.addView(button("Connect a debrid service in Settings") { navigate { settings() } }, margin(-1, 44, top = 8))
        }
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        // The title's runtime (from details already loaded) turns each file size into an estimated bitrate.
        val minutes = AutoplayLimit.runtimeMinutes(cachedDetails(MediaItemData(identity.imdbId, identity.type, identity.title))?.runtime)
        // Sources taller than this TV can decode (such as 8K on a 4K TV) go last and are never auto-played.
        val limit = DecoderLimits.maxHeight()
        fun tooTall(source: SourceItem) = (source.qualityHeight ?: 0) > limit
        val languages = sorting.languages
        val ordered = playable.distinctBy(TvChoiceKeys::source).let { unique -> languages?.sorted(unique, sorting.order) ?: sorting.order.sorted(unique) }.let { all -> all.filterNot(::tooTall) + all.filter(::tooTall) }
        ordered.forEach { source ->
            val cachedOn = source.cachedServices.map(DebridServices::name)
            val status = when {
                source.directUrl.isNotEmpty() -> "DIRECT STREAM"
                cachedOn.size > 1 -> "CACHED · ${cachedOn.joinToString(" + ")} · Plays via ${cachedOn.first()}"
                cachedOn.isNotEmpty() -> "CACHED · ${cachedOn.first()}"
                checkOnPlay.isNotEmpty() -> "CHECK ON PLAY · ${checkOnPlay.joinToString(" + ") { DebridServices.name(it) }}"
                else -> "TRANSFER MAY BE NEEDED"
            }
            val limitNote = if (tooTall(source)) " · ABOVE THIS TV'S ${DecoderLimits.label(limit)} LIMIT" else ""
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.source(source), source.title, source.detail,
                "$status$limitNote · ${source.provider}", theme.palette, SourceBadges.from(source, languages, minutes)) {
                if (!source.cached && source.directUrl.isEmpty()) {
                    if (checkOnPlay.isNotEmpty()) resolveFirstCached(ordered.dropWhile { it !== source }.filterNot(::tooTall), identity, source)
                    else offerTransfer(source, identity)
                }
                else resolve(source, identity)
            })
        }
        focusAfterRender(id)
        // Only a fresh search auto-plays; returning to this list leaves the choice to the viewer.
        if (autoplay) {
            // Auto-play skips links over the viewer's size limit; links of unknown size stay eligible.
            // Over the bitrate cap when the runtime is known, else over this kind of title's size cap.
            val limited = store.get(AUTOPLAY_LIMIT_ON) == "true"
            val maxBytes = autoplayLimitGb(episode = identity.type == "series") * 1_000_000_000L
            fun tooBig(source: SourceItem) = limited && AutoplayLimit.tooBig(source.sizeBytes, minutes, autoplayMaxMbps(), maxBytes)
            val first = ordered.firstOrNull { (it.cached || it.directUrl.isNotEmpty()) && !tooTall(it) && !tooBig(it) }
            if (first != null) { toast("Playing the top result · ${first.title.take(60)}"); resolve(first, identity) }
            else if (checkOnPlay.isNotEmpty()) resolveFirstCached(ordered.filterNot { tooTall(it) || tooBig(it) }, identity, null)
        }
    }

    /** Offcloud and TorBox can download a link that isn't cached; the other services cannot. */
    private fun offerTransfer(source: SourceItem, identity: PlaybackIdentity) {
        val service = DebridServices.connected(store).firstOrNull { it in DebridServices.TRANSFERS }
        if (service == null) { toast("This link isn't cached on your debrid services. Choose another link."); return }
        confirm("Add this source to your ${DebridServices.name(service)} account? A transfer may need time to finish.") {
            resolve(source.copy(downloadService = service), identity)
        }
    }

    /**
     * Real-Debrid, AllDebrid and Debrid-Link cannot say in advance what is cached, so the first few links are tried
     * in order and the first cached one plays. [picked] is the row the viewer chose, offered for download if none play.
     */
    private fun resolveFirstCached(candidates: List<SourceItem>, identity: PlaybackIdentity, picked: SourceItem?) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected source…"); return }
        playingSourceText = ""; playingMedia = null
        val request = generation; resolvingGeneration = request
        val progress = text("Checking links…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val prepared = resolver.prepareFirstCached(candidates, identity) { number, total, service ->
                    runOnUiThread { if (request == generation) progress.text = "Checking link $number of $total on ${DebridServices.name(service)}…" }
                }
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress)
                        when {
                            prepared == null -> {
                                val tried = minOf(candidates.count { it.infoHash.isNotBlank() }, LinkDebrids.CHECK_LIMIT)
                                toast("None of the first $tried links are cached. Pick another link.")
                                if (picked != null && DebridServices.connected(store).any { it in DebridServices.TRANSFERS }) offerTransfer(picked, identity)
                                focusAfterRender(request)
                            }
                            prepared.files.size == 1 -> playLinkFile(prepared, prepared.files.single())
                            else -> navigate { linkFiles(prepared) }
                        }
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) { resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request) }
            } }
        }
    }

    private fun linkFiles(prepared: PreparedSource.Links) {
        current = { linkFiles(prepared) }
        val identity = prepared.identity
        val name = DebridServices.name(prepared.service)
        val id = page("Choose a $name video", "Choose the correct file for ${identity.title}.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        prepared.files.forEach { file ->
            choices.addChoice(TvChoiceRow(this, "${prepared.service}:${identity.imdbId}:${file.id}", file.name,
                if (file.size > 0) "${file.size / (1024 * 1024)} MB" else "$name video", palette = theme.palette) { playLinkFile(prepared, file) })
        }
        if (prepared.files.isEmpty()) body.addView(text("No eligible video files are available. Go back and choose another source.", 15, muted))
        if (pendingFocus == null) prepared.preferredFileId?.let { pendingFocus = TvFocusSnapshot("${prepared.service}:${identity.imdbId}:$it", 0) }
        focusAfterRender(id)
    }

    private fun playLinkFile(prepared: PreparedSource.Links, file: DebridFile) {
        mediaFile(file.name, file.size)
        val identity = prepared.identity
        val request = generation
        worker.execute {
            try {
                val url = resolver.streamUrl(prepared, file)
                runOnUiThread { if (request == generation && !isFinishing && !isDestroyed)
                    play(url, identity.title, identity, "media:${identity.imdbId}:${identity.season}:${identity.episode}", prepared.service) }
            } catch (error: Exception) { runOnUiThread { if (request == generation && !isFinishing && !isDestroyed) showError(error) } }
        }
    }

    private fun resolve(source: SourceItem, identity: PlaybackIdentity) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected source…"); return }
        playingSourceText = "${source.title} · ${source.detail}"; playingMedia = playingSourceText to source.sizeBytes
        val request = generation; resolvingGeneration = request
        val progress = text("Preparing your video…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val prepared = resolver.prepare(source, identity)
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress)
                        when (prepared) {
                            is PreparedSource.Direct -> play(prepared.url, prepared.identity.title, prepared.identity,
                                "media:${prepared.identity.imdbId}:${prepared.identity.season}:${prepared.identity.episode}",
                                headers = prepared.headers)
                            is PreparedSource.OffcloudFiles -> {
                                if (prepared.files.size == 1) playOffcloudFile(prepared, prepared.files.single())
                                else navigate { offcloudFiles(prepared) }
                            }
                            is PreparedSource.Files -> {
                                val only = prepared.automaticFile
                                if (only != null) playPreparedFile(prepared, only)
                                else navigate { preparedFiles(prepared) }
                            }
                            is PreparedSource.Links -> {
                                if (prepared.files.size == 1) playLinkFile(prepared, prepared.files.single())
                                else navigate { linkFiles(prepared) }
                            }
                        }
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) { resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request) }
            } }
        }
    }

    private fun offcloudFiles(prepared: PreparedSource.OffcloudFiles) {
        current = { offcloudFiles(prepared) }
        val id = page("Choose an Offcloud video", "Choose the correct file for ${prepared.identity.title}.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        val sourceKey = prepared.requestId ?: "cache:${prepared.identity.imdbId}:${prepared.identity.season}:${prepared.identity.episode}"
        prepared.files.forEach { file ->
            val focusKey = "offcloud:$sourceKey:${file.id}"
            choices.addChoice(TvChoiceRow(this, focusKey, file.name,
                if (file.size > 0) "${file.size / (1024 * 1024)} MB" else "Offcloud video",
                palette = theme.palette) { playOffcloudFile(prepared, file) })
        }
        if (pendingFocus == null) prepared.preferredFileId?.let {
            pendingFocus = TvFocusSnapshot("offcloud:$sourceKey:$it", 0)
        }
        focusAfterRender(id)
    }

    private fun playOffcloudFile(prepared: PreparedSource.OffcloudFiles, file: OffcloudFile) {
        if (!OffcloudApi(store).owns(prepared)) {
            toast("Connect Offcloud and select the source again"); return
        }
        val identity = prepared.identity
        mediaFile(file.name, file.size)
        play(file.url, identity.title, identity, "media:${identity.imdbId}:${identity.season}:${identity.episode}", "offcloud")
    }

    private fun preparedFiles(prepared: PreparedSource.Files) {
        current = { preparedFiles(prepared) }
        val identity = prepared.identity
        val suffix = if (identity.type == "series") " · S${identity.season} E${identity.episode}" else ""
        val id = page("Choose a video version", "Choose the correct file for ${identity.title}$suffix. Each row plays that exact file.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        prepared.files.forEach { file ->
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file(prepared.folder.kind, prepared.folder.id, file.id),
                file.name, fileSize(file), palette = theme.palette) { playPreparedFile(prepared, file) })
        }
        if (prepared.files.isEmpty()) body.addView(text("No eligible video files are available. Go back and choose another source.", 15, muted))
        if (pendingFocus == null) prepared.preferredFileId?.let { fileId ->
            pendingFocus = TvFocusSnapshot(TvChoiceKeys.file(prepared.folder.kind, prepared.folder.id, fileId), 0)
        }
        focusAfterRender(id)
    }

    private fun playPreparedFile(prepared: PreparedSource.Files, file: CloudFile) {
        mediaFile(file.name, file.size)
        val identity = prepared.identity
        prepared.accountTag?.let { tag ->
            if (!torbox.ownsTag(tag)) {
                toast("Connect TorBox and select the source again"); return
            }
            val key = "media:${identity.imdbId}:${identity.season}:${identity.episode}"
            prepared.resolvedUrls[file.id]?.let { play(it, identity.title, identity, key, "torbox"); return }
            val hash = prepared.sourceInfoHash ?: run { toast("Select the source again"); return }
            streamFile(prepared.folder, file, identity.title, identity, key) {
                check(torbox.ownsTag(tag)) { "The TorBox account changed. Select the source again." }
                val resolved = torbox.prepareCached(SourceItem(file.name, "TorBox", hash, cached = true), identity, selectedFile = file)
                check(resolved.accountTag == tag && torbox.ownsTag(tag)) { "The TorBox account changed. Select the source again." }
                resolved.resolvedUrls.values.single()
            }
            return
        }
        streamFile(prepared.folder, file, identity.title, identity,
            "media:${identity.imdbId}:${identity.season}:${identity.episode}")
    }

    private fun selectedCloud() {
        val services = DebridServices.connected(store).filter { it in DebridServices.TRANSFERS }
        if (services.size == 1) {
            if (services.first() == "offcloud") offcloudCloud() else cloud("torrents")
            return
        }
        current = { selectedCloud() }; destination = "library"; refreshNavigation()
        page("Cloud library", "Choose a connected service to browse its downloads.")
        if (services.isEmpty()) body.addView(button("Connect a debrid service in Settings") { navigate { settings() } }, margin(-1, 44))
        services.forEach { service ->
            body.addView(button("${DebridServices.name(service)} cloud  ›") {
                navigate { if (service == "offcloud") offcloudCloud() else cloud("torrents") }
            }, margin(-1, 44, bottom = 8))
        }
    }

    private fun offcloudCloud() {
        current = { offcloudCloud() }; destination = "library"; refreshNavigation()
        val id = page("My Offcloud", "Your Offcloud downloads. Open a completed download to choose a video.")
        val api = OffcloudApi(store)
        if (!api.connected()) {
            body.addView(button("Connect Offcloud in Settings") { navigate { settings() } }, margin(260, 44)); return
        }
        body.addView(button("Refresh downloads") { offcloudCloud() }, margin(220, 44, bottom = 10))
        read(id, { api.cloud() }) { downloads ->
            if (downloads.isEmpty()) body.addView(text("No Offcloud downloads yet. Choose a source from a movie or episode.", 15, muted))
            val open: (OffcloudDownload) -> Unit = { download ->
                if (download.status == "downloaded") navigate { offcloudCloudFiles(download) }
                else toast("This download is ${download.status.ifBlank { "not ready" }}. Refresh after Offcloud finishes processing it.")
            }
            if (flix) libraryGrid(downloads.map { download ->
                LibraryEntry("offcloud:${download.id}", download.name, if (download.status == "downloaded") "Ready" else download.status.ifBlank { "Not ready" }) { open(download) }
            }) else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                downloads.forEach { download -> choices.addChoice(cloudRow("offcloud:${download.id}", download.name, download.status) { open(download) }) }
            }
            focusAfterRender(id)
        }
    }

    private fun offcloudCloudFiles(download: OffcloudDownload) {
        current = { offcloudCloudFiles(download) }
        val id = page(download.name, "Choose a video file from Offcloud.")
        val api = OffcloudApi(store)
        read(id, { api.cloudFiles(download) }) { files ->
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            if (files.isEmpty()) body.addView(text("No playable video files in this download.", 15, muted))
            files.forEach { file ->
                choices.addChoice(cloudRow("offcloud:${download.id}:${file.id}", file.name,
                    if (file.size > 0) "${file.size / (1024 * 1024)} MB" else "Offcloud video") {
                    if (api.ownsTag(download.accountTag)) play(file.url, file.name, null, "offcloud:${download.id}:${file.id}", "offcloud")
                    else toast("The Offcloud account changed. Refresh your library.")
                })
            }
            focusAfterRender(id)
        }
    }

    private fun cloud(kind: String) {
        current = { cloud(kind) }
        destination = "library"; refreshNavigation()
        val id = page("My TorBox", "Your library, ready for the big screen.")
        if (!store.has("torbox.token")) { body.addView(button("Connect TorBox") { startTorBox() }, margin(190, 44)); return }
        if (DeviceLayout.phone) phoneTabs(listOf("torrents" to "Torrents", "usenet" to "Usenet", "webdl" to "Web").map { (key, title) -> title to { cloud(key) } })
        else {
            val tabs = horizontalRow()
            listOf("torrents" to "Torrents", "usenet" to "Usenet", "webdl" to "Web downloads").forEach { (key, title) -> tabs.addView(button(title) { cloud(key) }, margin(150, 39, right = 8)) }
        }
        read(id, { torbox.cloud(kind) }) { folders ->
            if (folders.isEmpty()) body.addView(text("Nothing here yet. Your TorBox downloads will appear in this library.", 15, muted))
            if (flix) libraryGrid(folders.map { folder ->
                LibraryEntry(TvChoiceKeys.folder(folder.kind, folder.id), folder.name, if (folder.ready) "Ready" else "Not ready yet") { navigate { cloudFolder(folder) } }
            }) else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                folders.forEach { folder -> choices.addChoice(cloudRow(TvChoiceKeys.folder(folder.kind, folder.id),
                    folder.name, if (folder.ready) "Ready to browse" else "Not ready yet") {
                    navigate { cloudFolder(folder) }
                }) }
            }
        }
    }

    private fun cloudFolder(folder: CloudFolder) {
        current = { cloudFolder(folder) }
        val id = page(folder.name, "Choose a video file.")
        read(id, { torbox.folder(folder.kind, folder.id) }) { result ->
            val files = result.files.filter { Regex("\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mpg|mpeg)$", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            if (files.isEmpty()) body.addView(text("No playable video files are available yet.", 15, muted))
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            files.forEach { file -> choices.addChoice(cloudRow(TvChoiceKeys.file(result.kind, result.id, file.id),
                file.name, fileSize(file)) { cloudChoice(result, file) }) }
        }
    }

    /** Looks up catalog artwork for a download's filename; artwork hints never become playback or tracking identities. */
    private fun cloudArtworkFor(query: CloudMetadata.Query, done: (MediaItemData?) -> Unit) {
        val cacheKey = "${query.type}:${query.title}:${query.year}"
        val request = generation
        metadataWorker.execute {
            if (request != generation || Thread.currentThread().isInterrupted) return@execute
            val found = synchronized(cloudArtwork) {
                if (cloudArtwork.containsKey(cacheKey)) Result.success(cloudArtwork[cacheKey]) else runCatching {
                    val candidates = query.type?.let { catalog.search(query.title, it) } ?: catalog.searchAll(query.title).items
                    CloudMetadata.match(query, candidates)
                }.onSuccess { if (cloudArtwork.size > 200) cloudArtwork.clear(); cloudArtwork[cacheKey] = it }
            }
            runOnUiThread { if (request == generation && !isDestroyed) done(found.getOrNull()) }
        }
    }

    private class LibraryEntry(val key: String, val name: String, val caption: String, val action: () -> Unit)

    /** Flix library: downloads as landscape cards in the same four-column grid as "See all", with matched artwork. */
    private fun libraryGrid(entries: List<LibraryEntry>) {
        val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
        val count = if (DeviceLayout.phone) 2 else 4
        val cardWidth = (width - 6 - (count - 1) * theme.layout.cardGapDp) / count
        entries.chunked(count).forEach { chunk ->
            val row = LinearLayout(this).apply { tag = "media-grid-row"; clipChildren = false }
            body.addView(row, margin(-1, -2, top = 14))
            chunk.forEach { entry ->
                val query = CloudMetadata.query(entry.name)
                val title = query?.let { listOf(it.title, it.year).filter(String::isNotBlank).joinToString(" ") } ?: entry.name
                val card = tile(MediaItemData("", query?.type ?: "movie", title), caption = entry.caption, action = entry.action) as FrameLayout
                card.tag = entry.key; card.contentDescription = "$title, ${entry.caption}"
                row.addView(card, margin(cardWidth, cardWidth * 9 / 16, right = theme.layout.cardGapDp))
                if (query != null) cloudArtworkFor(query) { match ->
                    val address = match?.background?.ifBlank { match.poster }.orEmpty()
                    val art = ((card.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView)
                    if (art != null && card.isAttachedToWindow && address.isNotBlank()) image(art, thumbnail(address))
                }
            }
        }
    }

    private fun cloudRow(key: String, filename: String, detail: String, action: () -> Unit): TvChoiceRow {
        val row = TvChoiceRow(this, key, filename, detail, palette = theme.palette, withPoster = true, action = action)
        val query = CloudMetadata.query(filename) ?: return row
        fun loadPoster() = cloudArtworkFor(query) { found ->
            if (row.isAttachedToWindow) found?.poster?.takeIf(String::isNotBlank)?.let { image(row.poster, it) }
        }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { if (row.poster.drawable == null) loadPoster() }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        return row
    }
    private fun fileSize(file: CloudFile) = String.format(java.util.Locale.getDefault(), "%.2f GB", file.size.toDouble() / 1_000_000_000)

    /** Debug-only, offline fixture uses the same cards/navigation without loading any account or library. */
    private fun sourceChoiceAudit() {
        current = { sourceChoiceAudit() }
        val id = page("Source navigation check", "Seven synthetic source versions. No account or playback request is made.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        repeat(7) { index ->
            val source = SourceItem("Version ${index + 1} · Example Movie 2026 extended edition with a deliberately long title to verify every row remains independently selectable",
                "Public fixture", infoHash = String.format(java.util.Locale.ROOT, "%040x", index + 1), fileIndex = index, cached = true)
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.source(source), source.title,
                "1080p · synthetic file · no network", "READY IN TORBOX · Public fixture", theme.palette) {
                navigate { sourceChoiceAuditFiles(index + 1) }
            })
        }
        focusAfterRender(id)
    }

    private fun sourceChoiceAuditFiles(sourceVersion: Int) {
        current = { sourceChoiceAuditFiles(sourceVersion) }
        val id = page("Choose a video version", "Selected source version $sourceVersion. All filenames below are synthetic.")
        val selection = text("Choose a file version", 13, accent).apply { tag = "fixture:selection" }
        body.addView(selection, margin(-1, -2, bottom = 12))
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        repeat(7) { index ->
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file("torrents", 1234, 101L + index),
                "File version ${index + 1} · Example Movie with a long synthetic filename.1080p.mkv", "${index + 1}.00 GB", palette = theme.palette) {
                selection.text = "Selected file version ${index + 1}"
            })
        }
        focusAfterRender(id)
    }

    private fun cloudChoice(folder: CloudFolder, file: CloudFile) {
        val choices = arrayOf("Play file", "Identify movie for ${wetrakr.name}", "Identify episode for ${wetrakr.name}")
        AlertDialog.Builder(this).setTitle(file.name).setItems(choices) { _, choice ->
            if (choice == 0) playCloud(folder, file, null)
            else identifyCloud(folder, file, if (choice == 1) "movie" else "series")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun identifyCloud(folder: CloudFolder, file: CloudFile, type: String) {
        val input = TvEditText(this).apply { hint = "Search title"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Identify ${if (type == "movie") "movie" else "show"}").setView(input).setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isBlank()) return@setPositiveButton
            load(generation, { catalog.search(query, type) }) { items ->
                if (items.isEmpty()) { toast("No titles found"); return@load }
                AlertDialog.Builder(this).setTitle("Choose the correct title").setItems(items.map { "${it.title} (${it.year})" }.toTypedArray()) { _, index ->
                    val item = items[index]; val identity = PlaybackIdentity(item.imdbId, type, item.title)
                    if (type == "movie") playCloud(folder, file, identity)
                    else load(generation, { catalog.details(item) }) { detail ->
                        chooseCloudEpisode(folder, file, detail)
                    }
                }.setNegativeButton("Cancel", null).show()
            }
        }.setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun chooseCloudEpisode(folder: CloudFolder, file: CloudFile, detail: MediaItemData) {
        val seasons = EpisodePresentation.seasons(detail.episodes)
        if (seasons.isEmpty()) { toast("Episode information unavailable"); return }
        AlertDialog.Builder(this).setTitle("Choose a season for ${detail.title}")
            .setItems(seasons.map { if (it == 0) "Specials & extras" else "Season $it" }.toTypedArray()) { _, index ->
                val season = seasons[index]
                val episodes = detail.episodes.filter { it.season == season }.sortedBy { it.episode }
                AlertDialog.Builder(this).setTitle(if (season == 0) "Choose a special" else "Choose a Season $season episode")
                    .setItems(episodes.map { "${if (season == 0) "Special" else "Episode"} ${it.episode}  ${it.title}" }.toTypedArray()) { _, e ->
                        val episode = episodes[e]
                        if (EpisodePresentation.future(episode.released)) toast("This episode has not been released yet")
                        else playCloud(folder, file, PlaybackIdentity(detail.imdbId, "series", detail.title, season, episode.episode))
                    }.setNeutralButton("Seasons") { _, _ -> chooseCloudEpisode(folder, file, detail) }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun playCloud(folder: CloudFolder, file: CloudFile, identity: PlaybackIdentity?) {
        playingSourceText = ""; mediaFile(file.name, file.size)
        streamFile(folder, file, file.name, identity, "cloud:${folder.kind}:${folder.id}:${file.id}")
    }

    private fun streamFile(folder: CloudFolder, file: CloudFile, title: String, identity: PlaybackIdentity?, key: String, resolveUrl: (() -> String)? = null) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected file…"); return }
        val request = generation; resolvingGeneration = request
        val progress = text("Preparing your video…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val url = resolveUrl?.invoke() ?: torbox.streamUrl(folder.kind, folder.id, file.id)
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress); play(url, title, identity, key, "torbox")
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) {
                    resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request)
                }
            } }
        }
    }

    private fun play(url: String, title: String, identity: PlaybackIdentity?, key: String, debridService: String? = null, resumeImmediately: Boolean = false,
                     headers: Map<String, String> = emptyMap()) {
        if (launchingPlayer) return
        if (Uri.parse(url).scheme !in setOf("https", "http")) { toast("This stream did not return a playable web link."); return }
        launchingPlayer = true; playerShown = true
        val remotePercent = identity?.let { remoteProgress[identityKey(it)] } ?: 0.0
        val start = {
            val intent = Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title)
                .putExtra("identity", identity?.toJson()?.toString()).putExtra("key", key).putExtra("resume", history.resume(key)).putExtra("resume_percent", remotePercent)
                .putExtra("debrid_service", PlaybackDebrid.normalize(debridService)).putExtra("cached_playback", resumeImmediately)
                .putExtra("playback_account", LastPlayedLinks(store).scope(PlaybackDebrid.normalize(debridService)))
                .putExtra("media_name", playingMedia?.first.orEmpty()).putExtra("media_size", playingMedia?.second ?: 0L)
                .putExtra("headers", org.json.JSONObject(headers).toString())
            playingMedia = null; playingSourceText = ""
            @Suppress("DEPRECATION")
            startActivityForResult(intent, PLAYBACK_REQUEST)
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        // Playback always continues where it left off; the timeline can still scrub back to the start.
        start()
    }

    private fun weTrakrPage() {
        current = { weTrakrPage() }
        destination = "wetrakr"; refreshNavigation()
        val tracker = weTrakrClient()
        val id = page("My ${tracker.name}", "Your watchlist. Your progress. Your next episode.")
        if (!tracker.connected()) { body.addView(button("Connect ${tracker.name}") { startWeTrakr() }, margin(210, 44)); return }
        if (flix) { flixWeTrakrPage(id); return }
        // Start with the visual library shelves; progress rows must not push them off-screen.
        read(id, { tracker.watchlist("movie") to tracker.watchlist("series") }) { (movies, series) ->
            mediaRow("Movie watchlist", movies); mediaRow("TV watchlist", series)
            likedLists(id, false)
            if (tracker.supportsLists) body.addView(button("Personal lists") { navigate { weTrakrLists() } }, margin(180, 42, top = 18))
            weTrakrProgress(id)
        }
    }

    /**
     * Flix WeTrakr: the same shelves as Home. Each section has a fixed slot so they keep their order while they
     * load independently. Playing from here always searches fresh links (and resumes the saved position).
     */
    private fun flixWeTrakrPage(id: Int) {
        fun slot() = column().also { body.addView(it, margin(-1, -2)) }
        val progressSlot = slot(); val nextSlot = slot(); val movieSlot = slot(); val showSlot = slot(); val personalSlot = slot()
        val likedAnchor = slot()
        fun heading(host: LinearLayout, title: String) = host.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
        read(id, { wetrakr.continueWatching() }) { progress ->
            if (progress.isEmpty()) return@read
            heading(progressSlot, "Continue Watching")
            val row = horizontalRow(progressSlot)
            progress.forEach { (identity, percent) ->
                remoteProgress[identityKey(identity)] = percent
                val caption = listOf(if (identity.type == "series") "S${identity.season} E${identity.episode}" else "", "${percent.toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")
                val item = MediaItemData(identity.imdbId, identity.type, identity.title)
                val open: () -> Unit = { navigate { showSources(identity) } }
                row.addView((if (resumeCards()) resumeCard(item, percent / 100, episodeLine(identity), "${percent.toInt()}% watched", open) else tile(item, percent / 100, caption, open))
                    .apply { continueOptions(this, "", identity, identity.title) }, continueSlot())
            }
        }
        read(id, { wetrakr.nextEpisodes() }) { next ->
            if (next.isEmpty()) return@read
            heading(nextSlot, "Up Next")
            val row = horizontalRow(nextSlot)
            next.forEach { identity ->
                row.addView(tile(MediaItemData(identity.imdbId, "series", identity.title), caption = "S${identity.season} E${identity.episode}") { navigate { showSources(identity) } },
                    margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp))
            }
        }
        read(id, { wetrakr.watchlist("movie") }) { mediaRow("Movie watchlist", it, movieSlot) }
        read(id, { wetrakr.watchlist("series") }) { mediaRow("TV watchlist", it, showSlot) }
        if (wetrakr.supportsLists) read(id, { wetrakr.lists() }) { lists ->
            lists.forEach { (listId, title) ->
                val listSlot = column().also { personalSlot.addView(it, margin(-1, -2)) }
                read(id, { wetrakr.listItems(listId) }) { items ->
                    listSlot.setTag(R.id.pov_list_scope, "playlist:$listId")
                    mediaRow(title, items, listSlot) { navigate { personalListGrid(listId, title) } }
                }
            }
        }
        flixLikedLists(id, likedAnchor)
    }

    private fun weTrakrProgress(id: Int) {
        val host = column(); body.addView(host, margin(-1, -2, top = 12))
        read(id, { wetrakr.continueWatching() }) { progress ->
            if (progress.isNotEmpty()) {
                host.addView(text("Continue watching", 20, ink, true), margin(-1, -2, bottom = 8))
                progress.forEach { (identity, percent) ->
                    remoteProgress[identityKey(identity)] = percent
                    host.addView(button("${identity.title}${if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""}   ·   ${percent.toInt()}%") {
                        navigate { showSources(identity) }
                    }.apply { continueOptions(this, "", identity, identity.title) }, margin(-1, 44, bottom = 7))
                }
            }
        }
        val nextGroup = column(); body.addView(nextGroup, margin(-1, -2, top = 16))
        read(id, { wetrakr.nextEpisodes() }) { next ->
            if (next.isNotEmpty()) {
                nextGroup.addView(text("Up next", 20, ink, true), margin(-1, -2, bottom = 12))
                next.forEach { identity -> nextGroup.addView(button("${identity.title}   ·   S${identity.season} E${identity.episode}") {
                    navigate { showSources(identity) }
                }, margin(-1, 44, bottom = 7)) }
            }
        }
    }

    private fun weTrakrLists() {
        current = { weTrakrLists() }
        val tracker = weTrakrClient()
        val id = page("Your ${tracker.name} lists")
        read(id, { tracker.lists() }) { lists ->
            if (lists.isEmpty()) body.addView(text("No personal lists yet.", 15, muted))
            lists.forEach { (listId, title) -> body.addView(button(title) {
                navigate { personalListGrid(listId, title) }
            }, margin(-1, 44, bottom = 8)) }
        }
    }

    private fun debridPriority() {
        val order = DebridServices.priority(store)
        AlertDialog.Builder(this).setTitle("Debrid priority · 1 plays first")
            .setItems(order.mapIndexed { index, id -> "${index + 1}. ${DebridServices.name(id)}" }.toTypedArray()) { _, index ->
                val offsets = buildList {
                    if (index > 0) add(-1)
                    if (index < order.lastIndex) add(1)
                }
                AlertDialog.Builder(this).setTitle("${index + 1}. ${DebridServices.name(order[index])}")
                    .setItems(offsets.map { if (it < 0) "Move up" else "Move down" }.toTypedArray()) { _, choice ->
                        DebridServices.move(store, index, offsets[choice])
                        settings(); debridPriority()
                    }.setNegativeButton("Back") { _, _ -> debridPriority() }
                    .setOnCancelListener { debridPriority() }.show()
            }.setPositiveButton("Done", null).show()
    }

    private fun subtitleLanguage(allLanguages: Boolean = false) {
        val preferences = SubtitlePreferences(this)
        val languages = if (allLanguages) SubtitlePreferences.LANGUAGES else
            SubtitlePreferences.COMMON_LANGUAGES.map { code -> SubtitlePreferences.LANGUAGES.first { it.first == code } }
        val labels = languages.map { it.second } + if (allLanguages) emptyList() else listOf("More languages…")
        AlertDialog.Builder(this).setTitle("Preferred subtitle language")
            .setSingleChoiceItems(labels.toTypedArray(), languages.indexOfFirst { it.first == preferences.language }) { dialog, index ->
                dialog.dismiss()
                if (index == languages.size) subtitleLanguage(true) else {
                    preferences.language = languages[index].first
                    InAppPlaybackSession.refreshSubtitleLanguage()
                    refreshSettings()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun audioLanguage(allLanguages: Boolean = false) {
        val preferences = AudioPreferences(this)
        val languages = if (allLanguages) AudioPreferences.LANGUAGES else
            AudioPreferences.COMMON_LANGUAGES.map { code -> AudioPreferences.LANGUAGES.first { it.first == code } }
        val labels = languages.map { it.second } + if (allLanguages) emptyList() else listOf("More languages…")
        AlertDialog.Builder(this).setTitle("Preferred audio language")
            .setSingleChoiceItems(labels.toTypedArray(), languages.indexOfFirst { it.first == preferences.language }) { dialog, index ->
                dialog.dismiss()
                if (index == languages.size) audioLanguage(true) else {
                    preferences.language = languages[index].first
                    InAppPlaybackSession.refreshAudioLanguage()
                    refreshSettings()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun settings() {
        current = { settings() }
        destination = "settings"; refreshNavigation()
        page("Settings", "Make POV yours.")

        val home = settingsGroup("Home & appearance", "home")
        val tmdbKey = store.get(TMDB_KEY)
        settingsRow(home, "TMDB", if (tmdbKey.isBlank()) "Not set" else "Connected",
            "Your own free TMDB key adds TMDB catalogs, and makes artwork load faster: TMDB backdrops are a fraction of the size.",
            status = tmdbKey.isNotBlank()) {
            inputSetting("TMDB API key", "TMDB API key or API Read Access Token", "", secret = true) { key ->
                load(generation, { TmdbCatalog(this, key, tmdbRegion()).validate() }) { store.put(TMDB_KEY, key); toast("TMDB connected. Home now uses TMDB catalogs."); refreshSettings() }
            }
        }
        settingsRow(home, "Cinema country", countryName(tmdbRegion()), "Used for Now Playing in Cinemas.") {
            val countries = java.util.Locale.getISOCountries().map { it to countryName(it) }.sortedBy { it.second }
            AlertDialog.Builder(this).setTitle("Now Playing in Cinemas · country")
                .setSingleChoiceItems(countries.map { it.second }.toTypedArray(), countries.indexOfFirst { it.first == tmdbRegion() }) { dialog, index ->
                    store.put(TMDB_REGION, countries[index].first); dialog.dismiss(); refreshSettings()
                }.setNegativeButton("Cancel", null).show()
        }
        settingsToggle(home, "Resume cards", "Continue Watching as wide cards with the episode, time left and progress beside the artwork.",
            store.get(RESUME_CARDS) != "false") { on -> store.put(RESUME_CARDS, on.toString()) }
        if (tmdbKey.isNotBlank()) settingsRow(home, "Remove TMDB key", danger = true) {
            confirm("Remove your TMDB key? Home will use Cinemeta catalogs.") { store.remove(TMDB_KEY); refreshSettings() }
        }

        val playback = settingsGroup("Playback", "nowplaying")
        settingsRow(playback, "Source sorting", SourceSortPreferences(this).label,
            "Links in your audio language first (then multi-audio), then quality, then file size. Auto-play follows this order.") {
            SourceSortPreferences.show(this) { refreshSettings() }
        }
        settingsToggle(playback, "Auto-play first result", "Play starts the first cached or direct link in your sort order. The full list stays open behind it, so you can pick another link if it fails.",
            store.get(AUTOPLAY_FIRST) == "true") { on -> store.put(AUTOPLAY_FIRST, on.toString()) }
        settingsToggle(playback, "Auto-play limit", "Auto-play skips links above your bitrate limit (file size over the title's runtime). When the runtime is unknown, the movie or episode size limit applies. Links of unknown size can still play, and you can still choose any link yourself.",
            store.get(AUTOPLAY_LIMIT_ON) == "true") { on -> store.put(AUTOPLAY_LIMIT_ON, on.toString()); refreshSettings() }
        if (store.get(AUTOPLAY_LIMIT_ON) == "true") {
            settingsRow(playback, "Highest bitrate", "${autoplayMaxMbps()} Mbps", "For movies and episodes. Pick it from your internet speed, e.g. 25 Mbps on a 40 Mbps connection.") {
                inputSetting("Highest bitrate for Auto-play (Mbps)", "A whole number of Mbps, e.g. 25", autoplayMaxMbps().toString()) { value ->
                    val mbps = value.trim().toIntOrNull()
                    if (mbps == null || mbps !in 1..500) toast("Enter a whole number of Mbps, such as 25")
                    else { store.put(AUTOPLAY_MAX_MBPS, mbps.toString()); toast("Auto-play limited to $mbps Mbps"); refreshSettings() }
                }
            }
            listOf(false to "Movie", true to "Episode").forEach { (episode, kind) ->
                settingsRow(playback, "$kind size fallback", "${autoplayLimitGb(episode)} GB", "Used when the ${kind.lowercase()}'s runtime is unknown.") {
                    inputSetting("Largest $kind file (GB)", "A whole number of GB, e.g. ${if (episode) 5 else 20}", autoplayLimitGb(episode).toString()) { value ->
                        val gb = value.trim().toIntOrNull()
                        if (gb == null || gb !in 1..1000) toast("Enter a whole number of GB, such as ${if (episode) 5 else 20}")
                        else { store.put(if (episode) AUTOPLAY_MAX_GB_EPISODE else AUTOPLAY_MAX_GB, gb.toString()); toast("$kind size fallback set to $gb GB"); refreshSettings() }
                    }
                }
            }
        }
        val readAhead = ReadAheadPreferences(this)
        settingsRow(playback, "Read-ahead", readAhead.mode.label,
            "How far ahead the player downloads, to ride out slow connections. Applies to the next video.") {
            AlertDialog.Builder(this).setTitle("Read-ahead")
                .setSingleChoiceItems(ReadAhead.entries.map { it.label }.toTypedArray(), readAhead.mode.ordinal) { dialog, index ->
                    readAhead.mode = ReadAhead.entries[index]; dialog.dismiss(); refreshSettings()
                }.setNegativeButton("Cancel", null).show()
        }
        val videoPreferences = VideoCompatibilityPreferences(this)
        settingsRow(playback, "Dolby Vision", videoPreferences.mode.label,
            "Applies to the next video. Uses a verified Profile 7/8 HDR10 or HLG base picture when supported. Profile 5 needs compatible Dolby Vision decoding.") {
            AlertDialog.Builder(this).setTitle("Dolby Vision compatibility")
                .setSingleChoiceItems(DolbyVisionMode.entries.map { it.label }.toTypedArray(), videoPreferences.mode.ordinal) { dialog, index ->
                    videoPreferences.mode = DolbyVisionMode.entries[index]; dialog.dismiss(); refreshSettings()
                }.setNegativeButton("Cancel", null).show()
        }

        val captions = settingsGroup("Subtitles & audio", "series")
        val subtitlePreferences = SubtitlePreferences(this)
        settingsRow(captions, "Subtitle style", "${subtitlePreferences.sizeLabel} · text and size") {
            val preferences = SubtitlePreferences(this)
            AlertDialog.Builder(this).setTitle("Subtitle style")
                .setMultiChoiceItems(arrayOf("Transparent background", "Bold text"),
                    booleanArrayOf(preferences.transparentBackground, preferences.boldText)) { _, which, checked ->
                    if (which == 0) preferences.transparentBackground = checked else preferences.boldText = checked
                    InAppPlaybackSession.refreshSubtitleStyle()
                }.setNeutralButton("Size · ${preferences.sizeLabel}") { _, _ ->
                    AlertDialog.Builder(this).setTitle("Subtitle size")
                        .setSingleChoiceItems(SubtitlePreferences.SIZE_LABELS.toTypedArray(), SubtitlePreferences.SIZES.indexOf(preferences.sizePercent)) { dialog, index ->
                            preferences.sizePercent = SubtitlePreferences.SIZES[index]
                            InAppPlaybackSession.refreshSubtitleStyle(); dialog.dismiss(); refreshSettings()
                        }.setPositiveButton("Done", null).show()
                }.setPositiveButton("Done", null).show()
        }
        settingsRow(captions, "Subtitle position", subtitlePreferences.positionLabel) {
            SubtitlePositionPicker.show(this, liveVideo = false,
                changed = { InAppPlaybackSession.refreshSubtitleStyle() }, dismissed = { refreshSettings() })
        }
        settingsRow(captions, "Subtitle language", subtitlePreferences.languageLabel, "Chosen automatically when available. You can pick another track in the player.") { subtitleLanguage() }
        settingsToggle(captions, "English fallback", "If a video has no audio or subtitles in your language, English is chosen instead. English links are also listed before other languages.",
            AudioPreferences(this).englishFallback) { on ->
            AudioPreferences(this).englishFallback = on
            InAppPlaybackSession.refreshAudioLanguage(); InAppPlaybackSession.refreshSubtitleLanguage()
        }
        settingsRow(captions, "Audio language", AudioPreferences(this).languageLabel, "Chosen automatically when available, and links in this language are listed and auto-played first. You can pick another track in the player.") { audioLanguage() }

        val services = settingsGroup("Debrid Services", "library")
        val offcloud = OffcloudApi(store)
        settingsRow(services, "Offcloud", if (offcloud.connected()) "Connected" else "Not connected",
            if (offcloud.connected()) "Select to replace the API key." else "Connect with your Offcloud API key.", status = offcloud.connected()) { connectOffcloud() }
        val torboxConnected = store.has("torbox.token")
        settingsRow(services, "TorBox", if (torboxConnected) "Connected" else "Not connected",
            "Sign in with a code on your phone or computer.", status = torboxConnected) { startTorBox() }
        settingsRow(services, "TorBox API key", "", "Or paste your own TorBox API key instead of signing in.") {
            inputSetting("TorBox API key", "Paste your own TorBox API key", "", true) { key -> load(generation, { torbox.setApiKey(key) }) { toast("TorBox connected"); refreshSettings() } }
        }
        val linkDebrids = LinkDebrids(store)
        LinkDebrids.IDS.forEach { service ->
            val name = DebridServices.name(service); val on = linkDebrids.connected(service)
            val note = if (service in LinkDebrids.CHECK_ON_PLAY) " Links are checked when you play: the first cached one of the top ${LinkDebrids.CHECK_LIMIT} plays." else ""
            settingsRow(services, name, if (on) "Connected" else "Not connected",
                (if (on) "Select to replace the API key." else "Connect with your $name API key.") + note, status = on) {
                apiKeyDialog?.dismiss()
                apiKeyDialog = ApiKeyDialog(this, name, worker,
                    save = { key, cancelled -> linkDebrids.setKey(service, key, cancelled) },
                    onConnected = {
                        toast("$name connected")
                        if (destination == "settings") refreshSettings()
                    }).also { it.show() }
            }
        }
        settingsRow(services, "Debrid priority", DebridServices.priority(store).joinToString(" → ") { DebridServices.name(it) },
            "All connected accounts are checked. Cached copies play from the first available service in this order.") { debridPriority() }
        settingsRow(services, "Source providers", "", "Your HTTPS scraper add-ons, such as Torrentio. Results from enabled providers are merged.") { sourceProviders() }
        if (offcloud.connected()) settingsRow(services, "Disconnect Offcloud", danger = true) {
            confirm("Disconnect Offcloud on this device?") { offcloud.disconnect(); refreshSettings() }
        }
        if (torboxConnected) settingsRow(services, "Disconnect TorBox", danger = true) {
            confirm("Disconnect TorBox on this device?") { torbox.disconnect(); refreshSettings() }
        }
        LinkDebrids.IDS.filter(linkDebrids::connected).forEach { service ->
            val name = DebridServices.name(service)
            settingsRow(services, "Disconnect $name", danger = true) {
                confirm("Disconnect $name on this device?") { linkDebrids.disconnect(service); refreshSettings() }
            }
        }

        val tracking = settingsGroup("Tracking", "wetrakr")
        settingsRow(tracking, "Service", wetrakr.name, "WeTrakr or Simkl: watchlists, Continue Watching, Up Next and progress sync.") { chooseTracker() }
        settingsRow(tracking, "Account", if (wetrakr.connected()) "Connected" else "Not connected",
            if (wetrakr.connected()) "Select to sign in again." else "Sign in with a code to sync watchlists and progress.", status = wetrakr.connected()) { startWeTrakr() }
        settingsToggle(tracking, "Scrobbling", "Automatic playback updates. Your library, Continue Watching and manual actions still work when this is off.",
            wetrakr.scrobblingEnabled(), contentLabel = "${wetrakr.name} scrobbling") { on -> wetrakr.setScrobblingEnabled(on); wetrakrOutbox.clearPending() }
        if (wetrakr.supportsLists) settingsToggle(tracking, "Liked lists on Home", "Shows your liked ${wetrakr.name} lists as rows on Home.",
            store.get("wetrakr.home_liked_opt_in") == "true", contentLabel = "${wetrakr.name} liked lists on Home") { on -> store.put("wetrakr.home_liked_opt_in", on.toString()) }
        if (wetrakr.connected()) settingsRow(tracking, "Disconnect ${wetrakr.name}", danger = true) {
            confirm("Disconnect ${wetrakr.name} on this device?") { wetrakr.disconnect(); wetrakrOutbox.clearPending(); remoteProgress.clear(); refreshWatched(); refreshSettings() }
        }

        val updates = settingsGroup("Updates", "settings")
        settingsRow(updates, "Check for updates", "POV Rajput ${packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removeSuffix("-rajput")}") { startActivity(Intent(this, UpdateActivity::class.java)) }
        settingsToggle(updates, "Check on startup", "Looks for a newer signed release each time the app opens.",
            store.get("updates.startup") != "false", contentLabel = "Check for updates on startup") { on -> store.put("updates.startup", on.toString()) }

        val about = settingsGroup("About", null)
        about.addView(text("POV Rajput fork by Rajput · Android TV port by Mark Mateo · based on the POV Kodi add-on by kodifitzwell (github.com/kodifitzwell/repo). Licensed under GNU GPL v3. WeTrakr tracking and API by alberdev (wetrakr.com). Catalogs: TMDB, Cinemeta and Kitsu. This product uses the TMDB API but is not endorsed or certified by TMDB. Playback: AndroidX Media3.", 12, muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(dp(14), dp(12), dp(14), dp(12))
        }, margin(-1, -2))
        settingsRow(about, "Open-source notices") {
            AlertDialog.Builder(this).setTitle("Open-source notices").setMessage(assets.open("NOTICE.txt").bufferedReader().use { it.readText() }).setPositiveButton("Close", null).show()
        }
        body.addView(View(this), margin(-1, 24))
    }

    /** A titled card that holds one group of settings rows. */
    private fun settingsGroup(title: String, icon: String?): LinearLayout {
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (icon != null) header.addView(NavigationIconView(this, icon, muted), margin(16, 16, right = 8))
        header.addView(text(title.uppercase(), 11, muted, true).apply { letterSpacing = 0.12f })
        body.addView(header, margin(-1, -2, top = if (body.childCount <= 2) 6 else 22, bottom = 8))
        val card = column().apply { background = rounded(if (flix) withAlpha(Color.WHITE, 13) else panel, 12); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        body.addView(card, margin(-1, -2))
        return card
    }

    private fun settingsDivider(card: LinearLayout) {
        if (card.childCount == 0) return
        card.addView(View(this).apply { setBackgroundColor(withAlpha(Color.WHITE, 16)) }, margin(-1, 1).apply { leftMargin = dp(12); rightMargin = dp(12) })
    }

    /** One settings row: label and help text on the left, the current value and a chevron on the right. */
    private fun settingsRow(card: LinearLayout, label: String, value: String = "", description: String = "",
                            status: Boolean? = null, danger: Boolean = false, action: () -> Unit) {
        settingsDivider(card)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "setting:$label"
            contentDescription = if (value.isBlank()) label else "$label · $value"
        }
        val copy = column()
        copy.addView(text(label, 14, if (danger) 0xFFFF6B6B.toInt() else ink, true))
        if (description.isNotBlank()) copy.addView(text(description, 11, muted).apply { setLineSpacing(dp(1).toFloat(), 1f) }, margin(-1, -2, top = 4))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        // A connection value is tinted: green when connected, muted otherwise.
        val tint = when (status) { true -> 0xFF46D369.toInt(); else -> muted }
        if (DeviceLayout.phone && value.length > PHONE_INLINE_VALUE) copy.addView(text(value, 12, tint, true), margin(-1, -2, top = 6))
        else if (value.isNotBlank()) {
            row.addView(text(value, 12, tint, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END }, margin(-2, -2).apply { leftMargin = dp(16) })
        }
        if (!danger) row.addView(text("›", 18, muted, true), margin(-2, -2).apply { leftMargin = dp(10) })
        row.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) rounded(withAlpha(Color.WHITE, if (flix) 34 else 24), 9) else null
            view.foreground = if (focused) FocusRingDrawable(if (flix) Color.WHITE else accent, dp(9).toFloat(), true, dp(1).toFloat()) else null
        }
        row.setOnClickListener { action() }
        card.addView(row.pressable(9), margin(-1, -2))
    }

    private fun settingsToggle(card: LinearLayout, label: String, description: String, initial: Boolean,
                               contentLabel: String = label, changed: (Boolean) -> Unit) {
        settingsDivider(card)
        card.addView(toggleRow(label, description, initial, contentLabel, changed), margin(-1, -2))
    }

    /** Rebuilds Settings after a change, returning focus and scroll to the row the viewer was on. */
    private fun refreshSettings() {
        val index = focusController.focusedIndex(); val scroll = scroller.scrollY
        settings()
        body.post { if (!isDestroyed && destination == "settings") focusController.restoreIndex(index, scroll) }
    }

    /** A settings row with a sliding switch that changes in place; the page never reloads. */
    private fun toggleRow(label: String, description: String, initial: Boolean, contentLabel: String = label, changed: (Boolean) -> Unit): View {
        var on = initial
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "toggle:$label"
        }
        val copy = column()
        val title = text(label, 14, ink, true)
        copy.addView(title)
        copy.addView(text(description, 11, muted).apply { setLineSpacing(dp(1).toFloat(), 1f) }, margin(-1, -2, top = 4))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val state = text("", 12, muted, true).apply { gravity = Gravity.END }
        row.addView(state, margin(40, -2, right = 10))
        val track = FrameLayout(this)
        val knob = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        track.addView(knob, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER_VERTICAL).apply { leftMargin = dp(3) })
        row.addView(track, margin(44, 24))
        fun paint(animate: Boolean) {
            state.text = if (on) "On" else "Off"; state.setTextColor(if (on) ink else muted)
            track.background = rounded(if (on) accent else withAlpha(Color.WHITE, 60), 12)
            val offset = if (on) dp(20).toFloat() else 0f
            if (animate) knob.animate().translationX(offset).setDuration(140).start() else knob.translationX = offset
            row.contentDescription = "$contentLabel · ${if (on) "On" else "Off"}"
        }
        fun focus(focused: Boolean) {
            row.background = if (focused) rounded(withAlpha(Color.WHITE, if (flix) 34 else 24), 9) else null
            row.foreground = if (focused) FocusRingDrawable(if (flix) Color.WHITE else accent, dp(9).toFloat(), true, dp(1).toFloat()) else null
        }
        row.setOnFocusChangeListener { _, focused -> focus(focused) }
        row.setOnClickListener { on = !on; changed(on); paint(true) }
        paint(false); focus(false)
        return row
    }

    private fun sourceProviders() {
        val config = SourceProviders(store)
        val providers = try { config.load() } catch (error: Exception) { toast(uiErrorMessage(error)); return }
        val labels = providers.map { "${it.name} · ${if (it.enabled) "On" else "Off"}  ›" } + "Add source provider…"
        AlertDialog.Builder(this).setTitle("Source providers")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == providers.size) editSourceProvider(null)
                else sourceProviderActions(providers[index])
            }.setNegativeButton("Close", null).show()
    }

    private fun sourceProviderActions(provider: SourceProvider) {
        AlertDialog.Builder(this).setTitle(provider.name)
            .setItems(arrayOf(if (provider.enabled) "Turn off" else "Turn on", "Edit name and address", "Remove")) { _, index ->
                when (index) {
                    0 -> {
                        try {
                            val config = SourceProviders(store)
                            config.save(config.load().map { if (it.id == provider.id) it.copy(enabled = !it.enabled) else it })
                            sourceProviders()
                        } catch (error: Exception) { toast(uiErrorMessage(error)) }
                    }
                    1 -> editSourceProvider(provider)
                    2 -> confirm("Remove ${provider.name} from source providers?") {
                        try {
                            val config = SourceProviders(store); config.save(config.load().filterNot { it.id == provider.id }); sourceProviders()
                        } catch (error: Exception) { toast(uiErrorMessage(error)) }
                    }
                }
            }.setNegativeButton("Back") { _, _ -> sourceProviders() }.show()
    }

    private fun editSourceProvider(provider: SourceProvider?) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val name = TvEditText(this).apply { hint = "Provider name"; setSingleLine(); setText(provider?.name.orEmpty()) }
        val address = TvEditText(this).apply {
            hint = "HTTPS Stremio-compatible add-on URL"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(provider?.address.orEmpty())
        }
        val status = text("", 14, muted)
        content.addView(name, margin(-1, -2)); content.addView(address, margin(-1, -2, top = 8)); content.addView(status, margin(-1, -2, top = 8))
        val dialog = AlertDialog.Builder(this).setTitle(if (provider == null) "Add source provider" else "Edit source provider")
            .setView(content).setPositiveButton("Save", null).setNegativeButton("Cancel") { _, _ -> sourceProviders() }.create()
        dialog.show(); dialog.submitOnEnter(name, address)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            try {
                val config = SourceProviders(store)
                val updated = if (provider == null) SourceProviders.create(name.text.toString(), address.text.toString())
                    else provider.copy(name = SourceProviders.name(name.text.toString()), address = SourceProviders.endpoint(address.text.toString()))
                val existing = config.load()
                config.save(if (provider == null) existing + updated else existing.map { if (it.id == provider.id) updated else it })
                dialog.dismiss(); sourceProviders()
            } catch (error: Exception) { status.text = uiErrorMessage(error) }
        }
    }

    private fun connectOffcloud() {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val input = TvEditText(this).apply {
            hint = "Paste your own Offcloud API key"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val status = text("", 14, muted)
        content.addView(input, margin(-1, -2)); content.addView(status, margin(-1, -2, top = 8))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).setTitle("Offcloud API key").setView(content)
            .setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { cancelled.set(true); input.setText("") }
        dialog.show(); dialog.submitOnEnter(input)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isBlank()) { input.error = "Enter your Offcloud API key"; return@setOnClickListener }
            val request = generation
            val connect = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            connect.isEnabled = false; status.text = "Checking your Offcloud account…"
            worker.execute {
                try {
                    OffcloudApi(store).setApiKey(key) { cancelled.get() }
                    runOnUiThread {
                        if (dialog.isShowing && request == generation && !isFinishing && !isDestroyed) {
                            if (store.get("playback.priority").isBlank() && !store.has("torbox.token")) store.put("playback.service", "offcloud")
                            dialog.dismiss()
                            toast("Offcloud connected"); settings()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread { if (dialog.isShowing) {
                        connect.isEnabled = true; status.text = uiErrorMessage(error)
                    } }
                }
            }
        }
    }

    private fun weTrakrClient() = wetrakr
    /** Sign-in to the chosen tracker with an on-screen code and QR; both apps' public Client IDs are built in. */
    private fun startWeTrakr() {
        val tracker = wetrakr
        authorize(tracker.name, { tracker.startDeviceAuth() }, { tracker.pollDeviceAuth(it) },
            cancel = { tracker.cancelDeviceAuth(it) })
    }

    /** WeTrakr or Simkl. The menu, Home rows and Settings follow the choice; each keeps its own sign-in. */
    private fun chooseTracker() {
        val ids = listOf(Trackers.WETRAKR, Trackers.SIMKL)
        AlertDialog.Builder(this).setTitle("Tracking service")
            .setSingleChoiceItems(arrayOf("WeTrakr", "Simkl"), ids.indexOf(Trackers.selected(store))) { dialog, index ->
                dialog.dismiss()
                if (ids[index] == Trackers.selected(store)) return@setSingleChoiceItems
                Trackers.select(store, ids[index]); simklChosen = ids[index] == Trackers.SIMKL; remoteProgress.clear(); retainedHome = null
                watched = WatchedState.EMPTY; refreshWatched()
                // The menu's tracker entry is renamed, so the shell is rebuilt around Settings,
                // returning to the same row and scroll position.
                val index = focusController.focusedIndex(); val scroll = scroller.scrollY
                destination = "settings"; backStack.clear(); current = { settings() }; shell(); settings()
                body.post { if (!isDestroyed && destination == "settings") focusController.restoreIndex(index, scroll) }
                if (!wetrakr.connected()) startWeTrakr()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startTorBox() { authorize("TorBox", { torbox.startDeviceAuth() }, { torbox.pollDeviceAuth(it) }, cancel = { torbox.cancelDeviceAuth(it) }) }
    private fun authorize(name: String, start: () -> DeviceAuth, poll: (DeviceAuth) -> Boolean,
                          cancel: (DeviceAuth?) -> Unit = {}, configure: (() -> Unit)? = null) {
        if (authorization?.isShowing == true) return
        authorization = DeviceAuthorizationDialog(this, name, authWorker, start, poll, onConnected = {
            if (name == wetrakr.name) { wetrakrOutbox.clearPending(); remoteProgress.clear(); refreshWatched(force = true) }
            toast("$name connected"); current()
        }, onCancel = cancel, configure = configure).also { it.show() }
    }

    private fun retrySync() {
        worker.execute {
            try {
                val revision = wetrakr.accountRevision()
                val scrobbleRevision = wetrakr.scrobbleRevision()
                if (wetrakr.connected() && wetrakr.scrobblingEnabled()) wetrakrOutbox.pending().take(10).forEach { event ->
                    if (event.accountRevision != revision || event.timestamp.isBlank() || event.scrobbleRevision != scrobbleRevision || history.isHidden(identity = event.identity)) wetrakrOutbox.acknowledge(event.identity, event.revision)
                    else try {
                        if (wetrakr.restoreProgress(event.identity, event.percent, event.action, event.timestamp, revision, scrobbleRevision))
                            wetrakrOutbox.acknowledge(event.identity, event.revision)
                    } catch (_: Exception) { /* Retain this account's final state for a future launch. */ }
                }
            } catch (_: Exception) { /* Keep playback usable when tracking is unavailable. */ }
        }
    }

    private fun identityKey(identity: PlaybackIdentity) = "${identity.imdbId}:${identity.season}:${identity.episode}"

    private fun inputSetting(title: String, hintText: String, initial: String, secret: Boolean = false, done: (String) -> Unit) {
        val input = TvEditText(this).apply { hint = hintText; setText(initial); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> val value = input.text.toString().trim(); if (value.isNotBlank()) done(value) }.setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun hideKeyboard(field: View) =
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)

    private fun <T> read(id: Int, work: () -> T, done: (T) -> Unit) = load(id, work, readOnly = true, done = done)

    private fun <T> load(id: Int, work: () -> T, readOnly: Boolean = false, quiet: Boolean = false, done: (T) -> Unit) {
        pendingLoads[id] = pendingLoads[id].orZero() + 1
        val progress = text("Loading…", 13, muted); body.addView(progress, margin(-1, -2, top = 12, bottom = 12))
        // Flix Home loads behind its splash and backdrop; a text label there only flashes in a corner.
        if (quiet || (flix && isHome)) progress.visibility = View.INVISIBLE
        worker.execute {
            if (readOnly && id != generation) { runOnUiThread { pendingLoads[id] = (pendingLoads[id].orZero() - 1).coerceAtLeast(0) }; return@execute }
            try { val result = work(); runOnUiThread { pendingLoads[id] = (pendingLoads[id].orZero() - 1).coerceAtLeast(0); if (id == generation && !isFinishing && !isDestroyed) { body.removeView(progress); done(result); focusAfterRender(id) } } }
            catch (error: Exception) { runOnUiThread { pendingLoads[id] = (pendingLoads[id].orZero() - 1).coerceAtLeast(0); if (id == generation && !isFinishing && !isDestroyed) { body.removeView(progress); showError(error); focusAfterRender(id); dismissSplash() } } }
        }
    }

    private fun showError(error: Exception) {
        val message = uiErrorMessage(error)
        body.addView(text(message, 14, Color.rgb(255, 185, 170)), margin(-1, -2, top = 12, bottom = 10))
        body.addView(button("Try again") { current() }, margin(150, 40, bottom = 12))
    }
    /** Flix option: Continue Watching as wide resume cards (artwork plus an info panel) instead of regular cards. */
    /** Size fallback in GB when the runtime is unknown: 20 for movies (the older single limit), 5 for episodes. */
    private fun autoplayLimitGb(episode: Boolean) = store.get(if (episode) AUTOPLAY_MAX_GB_EPISODE else AUTOPLAY_MAX_GB)
        .toIntOrNull()?.takeIf { it in 1..1000 } ?: if (episode) 5 else 20
    /** Highest estimated bitrate Auto-play may pick, for movies and episodes alike. */
    private fun autoplayMaxMbps() = store.get(AUTOPLAY_MAX_MBPS).toIntOrNull()?.takeIf { it in 1..500 } ?: 25

    private fun resumeCards() = flix && store.get(RESUME_CARDS) != "false"  // on unless the viewer turns it off
    private fun continueSlot() = margin(if (resumeCards()) (if (DeviceLayout.phone) 260 else RESUME_CARD_WIDTH) else cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)
    private fun episodeLine(identity: PlaybackIdentity) = if (identity.type == "series") "S${identity.season} E${identity.episode}" else "Movie"

    /** Same height as every shelf, wider: artwork on the left, then title, episode, time left with a play mark, and progress. */
    private fun resumeCard(item: MediaItemData, progress: Double, detail: String, timeLeft: String, action: () -> Unit): View {
        val radius = dp(6).toFloat()
        val idle = rounded(panel, 6); val lifted = rounded(0xFF3A3A40.toInt(), 6)
        val frame = FrameLayout(this).apply {
            background = idle; clipToOutline = true; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            tag = "media:${item.type}:${item.imdbId.ifBlank { item.title }}"
            contentDescription = listOf(item.title, detail, timeLeft).filter(String::isNotBlank).joinToString(", ")
        }
        val artWidth = dp(if (DeviceLayout.phone) 120 else 170)
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(art, FrameLayout.LayoutParams(artWidth, -1))
        image(art, thumbnail(backdropFor(item)))
        val fade = View(this)
        frame.addView(fade, FrameLayout.LayoutParams(dp(60), -1).apply { leftMargin = artWidth - dp(60) })
        fun paintFade(color: Int) { fade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(withAlpha(color, 0), color)) }
        paintFade(panel)
        val info = column().apply { setPadding(dp(8), dp(12), dp(12), dp(10)) }
        info.addView(text(item.title, 13, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        info.addView(text(detail, 10, muted), margin(-1, -2, top = 3))
        info.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val left = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(text("▶", 7, Color.BLACK, true).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }, margin(16, 16, right = 6))
        left.addView(text(timeLeft, 10, 0xFFDDDDDD.toInt()))
        info.addView(left)
        val track = FrameLayout(this).apply { background = rounded(0xFF4A4A4A.toInt(), 2) }
        track.addView(View(this).apply { background = rounded(theme.palette.progress, 2) }, FrameLayout.LayoutParams(0, -1))
        track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val fill = track.getChildAt(0); val width = (track.width * progress.coerceIn(0.0, 1.0)).roundToInt()
            if (fill.layoutParams.width != width) fill.layoutParams = fill.layoutParams.apply { this.width = width }
        }
        info.addView(track, margin(-1, 3, top = 6))
        frame.addView(info, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = artWidth })
        frame.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) lifted else idle
            paintFade(if (focused) 0xFF3A3A40.toInt() else panel)
            view.foreground = if (focused) FocusRingDrawable(Color.WHITE, radius, true, dp(1).toFloat()) else null
            view.animate().scaleX(if (focused) theme.layout.focusScale else 1f).scaleY(if (focused) theme.layout.focusScale else 1f).setDuration(130).start()
            if (focused) scheduleFeature(item)
        }
        frame.setOnClickListener { action() }
        return frame.pressable(6)
    }

    /**
     * Badges come from the saved history at once; the service is asked for its cheap change stamp, and the full
     * history is downloaded only when that moved (both services ask apps not to refetch whole libraries).
     */
    private fun refreshWatched(force: Boolean = false) {
        watchedCheckedAt = android.os.SystemClock.elapsedRealtime()
        val tracker = wetrakr; val service = trackerId()
        if (!tracker.connected()) { if (watched != WatchedState.EMPTY) { watched = WatchedState.EMPTY; refreshBadges() }; return }
        val revision = tracker.accountRevision()
        val saved = watchedCache.load(service, revision)
        if (saved != null && saved.state != watched) { watched = saved.state; refreshBadges() }
        if (watchedLoading) return
        watchedLoading = true
        worker.execute {
            try {
                val stamp = tracker.watchedStamp()
                if (!force && saved != null && saved.stamp == stamp) return@execute
                val state = tracker.watched()
                watchedCache.save(WatchedCache.Entry(service, revision, stamp, state))
                runOnUiThread { if (!isDestroyed && trackerId() == service && wetrakr.accountRevision() == revision) { watched = state; refreshBadges() } }
            } catch (_: Exception) { /* Badges are a convenience; the next check retries. */ } finally { watchedLoading = false }
        }
    }

    /**
     * After the player closes, Continue Watching is read again: once the pause or stop has had a moment to reach
     * the tracker, and once more in case the service is slow to record it. The row updates in place.
     */
    private fun scheduleContinueRefresh() {
        if (publicPreview || !::body.isInitialized) return
        listOf(2_500L, 9_000L).forEach { delay -> body.postDelayed({ if (!isDestroyed) refreshContinue?.invoke() }, delay) }
    }

    /** A manual mark shows its badge straight away; the next service check confirms it. */
    private fun markedWatched(identity: PlaybackIdentity) { watched = watched.with(identity); refreshBadges() }

    private fun watchedKey(key: String): Boolean {
        val parts = key.split(':')
        return when (parts.firstOrNull()) {
            "movie" -> watched.movie(parts.getOrElse(1) { "" })
            "series" -> watched.show(parts.getOrElse(1) { "" })
            "episode" -> parts.size == 4 && watched.episode(parts[1], parts[2].toIntOrNull() ?: -1, parts[3].toIntOrNull() ?: -1)
            else -> false
        }
    }

    /** A small ✓ on artwork, shown when the linked tracker says it was watched. Untrackable titles get none. */
    private fun watchedBadge(kind: String, imdb: String, season: Int = 0, episode: Int = 0): View? {
        if (!imdb.matches(Regex("tt\\d+")) || publicPreview) return null
        val key = if (kind == "episode") "episode:$imdb:$season:$episode" else "$kind:$imdb"
        return text("✓", 12, Color.BLACK, true).apply {
            gravity = Gravity.CENTER; tag = "watched-badge:$key"; contentDescription = "Watched"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(1), 0x33000000) }
            elevation = dp(2).toFloat(); visibility = if (watchedKey(key)) View.VISIBLE else View.GONE
        }
    }

    /** Updates every badge on screen and in the back stack after the history changes. */
    private fun refreshBadges() {
        if (!::body.isInitialized) return
        fun visit(view: View) {
            (view.tag as? String)?.takeIf { it.startsWith("watched-badge:") }?.let { tag ->
                view.visibility = if (watchedKey(tag.removePrefix("watched-badge:"))) View.VISIBLE else View.GONE
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        retainedRoots().forEach(::visit)
    }

    private fun cardWidth(): Int {
        if (flix) return theme.layout.cardWidthDp
        val visible = 5
        val available = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2 - 13
        return minOf(theme.layout.cardWidthDp, (available - (visible - 1) * theme.layout.cardGapDp) / visible)
    }

    private fun section(title: String) {
        body.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = if (isHome && body.childCount == 1) 3 else 11, bottom = 7))
    }
    private fun mediaRow(title: String, items: List<MediaItemData>, host: LinearLayout = body, seeAll: (() -> Unit)? = null) {
        if (items.isEmpty()) return
        if (flix) {
            // One press per shelf: the title is a label and "See all" closes the row.
            host.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
            val row = horizontalRow(host)
            if (title.contains("watchlist", ignoreCase = true)) row.setTag(R.id.pov_list_scope, "watchlist:${items.first().type}")
            items.take(20).forEach { row.addView(card(it).apply { tag = "row:$title:${it.type}:${it.imdbId}" }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
            row.addView(text("See all  ›", 13, ink, true).apply {
                gravity = Gravity.CENTER; tag = "shelf:$title"; contentDescription = "See all $title"
                isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; background = rounded(withAlpha(Color.WHITE, 22), theme.layout.cornerRadiusDp)
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(if (focused) Color.WHITE else withAlpha(Color.WHITE, 22), theme.layout.cornerRadiusDp)
                    setTextColor(if (focused) Color.BLACK else ink)
                }
                setOnClickListener { seeAll?.invoke() ?: navigate { shelfGrid(title, items) } }
            }, margin(110, theme.layout.cardHeightDp))
            return
        }
        host.addView(button("$title  ›") {
            navigate { shelfGrid(title, items) }
        }.apply { tag = "shelf:$title" }, margin(-1, 40, top = 10, bottom = 7))
        val row = horizontalRow(host)
        if (title.contains("watchlist", ignoreCase = true)) row.setTag(R.id.pov_list_scope, "watchlist:${items.first().type}")
        items.take(20).forEach { row.addView(card(it).apply { tag = "row:$title:${it.type}:${it.imdbId}" }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
    }
    private fun removeFromListViews(scope: String, identity: PlaybackIdentity) {
        fun visit(root: View, inside: Boolean = false) {
            val selected = inside || root.getTag(R.id.pov_list_scope) == scope
            val tag = root.tag as? String
            if (selected && (tag == "media:${identity.type}:${identity.imdbId}" || tag?.endsWith(":${identity.type}:${identity.imdbId}") == true)) root.visibility = View.GONE
            if (root is ViewGroup) (0 until root.childCount).map(root::getChildAt).forEach { visit(it, selected) }
        }
        retainedRoots().forEach { visit(it) }
    }
    private fun personalListGrid(listId: String, title: String) {
        current = { personalListGrid(listId, title) }; val id = page(title, "Your ${wetrakr.name} playlist")
        val host = column().apply { setTag(R.id.pov_list_scope, "playlist:$listId") }; body.addView(host)
        read(id, { wetrakr.listItems(listId) }) { mediaGrid(it, host = host) }
    }
    private fun shelfGrid(title: String, items: List<MediaItemData>) {
        current = { shelfGrid(title, items) }; val id = page(title)
        val host = column().apply { if (title.contains("watchlist", ignoreCase = true) && items.isNotEmpty()) setTag(R.id.pov_list_scope, "watchlist:${items.first().type}") }
        body.addView(host); mediaGrid(items, host = host); focusAfterRender(id)
    }
    private fun mediaGrid(items: List<MediaItemData>, showType: Boolean = false, host: LinearLayout = body) {
        if (items.isEmpty()) { host.addView(text("No titles found. Try a different search.", 14, muted)); return }
        val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
        val count = if (flix) (if (DeviceLayout.phone) 2 else 4) else (width / (cardWidth() + theme.layout.cardGapDp)).coerceAtLeast(1)
        // Streaming grids stretch their columns to the full content width.
        val gridWidth = if (flix) (width - 6 - (count - 1) * theme.layout.cardGapDp) / count else cardWidth()
        val gridHeight = if (flix) gridWidth * 9 / 16 else theme.layout.cardHeightDp
        items.chunked(count).forEach { chunk ->
            val row = LinearLayout(this).apply { tag = "media-grid-row" }; host.addView(row, margin(-1, -2, top = 14))
            chunk.forEach { item ->
                val view = if (showType) tile(item, caption = listOf(if (item.type == "movie") "Movie" else "TV show", item.year).filter { it.isNotBlank() }.joinToString(" · ")) { navigate { details(item) } } else card(item)
                row.addView(view, margin(gridWidth, gridHeight, right = theme.layout.cardGapDp))
            }
        }
    }
    private fun card(item: MediaItemData): View = tile(item) { navigate { details(item) } }

    /** Phones have no focus highlight, so tappable surfaces show a ripple instead. */
    private fun <T : View> T.pressable(radius: Int): T = apply {
        if (DeviceLayout.phone) foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, rounded(Color.WHITE, radius))
    }

    private fun tile(item: MediaItemData, progress: Double? = null, caption: String = "", action: () -> Unit): View {
        val shape = theme.layout.cards
        val frame = FrameLayout(this).apply {
            background = rounded(panel, theme.layout.cornerRadiusDp); isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            contentDescription = listOf(item.title, item.year, caption).filter(String::isNotBlank).joinToString(", ")
            tag = "media:${item.type}:${item.imdbId.ifBlank { item.title }}"
        }
        val artFrame = FrameLayout(this).apply { background = rounded(panel, theme.layout.cornerRadiusDp); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        val fallback = if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/${if (shape == CardFormat.POSTER) "poster" else "background"}/medium/${item.imdbId}/img" else ""
        image(art, thumbnail((if (shape == CardFormat.POSTER) item.poster else item.background).ifBlank { fallback.ifBlank { item.poster } }))
        artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE8000000.toInt(), 0x08000000, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        val labels = column().apply { setPadding(dp(10), dp(5), dp(9), dp(if (progress != null) 17 else 9)) }
        val name = text(item.title, 12, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        if (flix && logoFor(item).isNotBlank()) {
            // Title artwork replaces the plain caption once it loads; both share one slot so the swap never re-lays out the row.
            val slot = FrameLayout(this).apply { minimumHeight = dp(30) }
            val logo = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.INVISIBLE }
            slot.addView(logo, FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM))
            slot.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            labels.addView(slot, margin(-1, -2))
            // A card's logo is drawn about 60 px tall, so Metahub's 300 px logo is plenty at a quarter of the bytes.
            image(logo, logoFor(item).replace("/logo/medium/", "/logo/small/")) { loaded -> if (loaded) { logo.visibility = View.VISIBLE; name.visibility = View.INVISIBLE } }
        } else labels.addView(name)
        val secondary = caption.ifBlank { item.year }
        if (secondary.isNotBlank() && (caption.isNotBlank() || shape == CardFormat.LANDSCAPE)) labels.addView(text(secondary, 10, 0xFFCFD8E4.toInt()), margin(-1, -2, top = 2))
        artFrame.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.addView(artFrame, FrameLayout.LayoutParams(-1, -1))
        watchedBadge(if (item.type == "movie") "movie" else "series", item.imdbId)?.let {
            frame.addView(it, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })
        }
        if (progress != null) {
            val track = FrameLayout(this).apply { background = rounded(0xFF34424D.toInt(), 3) }
            track.addView(View(this).apply { background = rounded(theme.palette.progress, 3) }, FrameLayout.LayoutParams(0, -1))
            frame.addView(track, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM).apply { leftMargin = dp(9); rightMargin = dp(9); bottomMargin = dp(6) })
            track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val targetWidth = (track.width * progress.coerceIn(0.0, 1.0)).roundToInt()
                val fill = track.getChildAt(0)
                if (fill.layoutParams.width != targetWidth) fill.layoutParams = fill.layoutParams.apply { width = targetWidth }
            }
        }
        frame.foreground = if (flix) null else FocusRingDrawable(theme.palette.border, dp(theme.layout.cornerRadiusDp).toFloat(), false, dp(1).toFloat())
        frame.setOnFocusChangeListener { view, focused ->
            view.foreground = if (flix) { if (focused) FocusRingDrawable(Color.WHITE, dp(theme.layout.cornerRadiusDp).toFloat(), true, dp(1).toFloat()) else null }
                else FocusRingDrawable(if (focused) accent else theme.palette.border, dp(theme.layout.cornerRadiusDp).toFloat(), focused, dp(1).toFloat())
            view.animate().scaleX(if (focused) theme.layout.focusScale else 1f).scaleY(if (focused) theme.layout.focusScale else 1f).setDuration(130).start()
            if (focused) scheduleFeature(item)
        }
        frame.setOnClickListener { action() }
        return frame.pressable(theme.layout.cornerRadiusDp)
    }

    private fun image(view: ImageView, address: String, keep: Boolean = false, feature: Boolean = false, loaded: ((Boolean) -> Unit)? = null) {
        if (!address.startsWith("https://")) { loaded?.invoke(false); return }
        val backdrop = flix && view === backdropArt
        // The backdrop is never blanked: the current picture stays until the next one is ready.
        if (view.tag != address && !keep && !backdrop) view.setImageDrawable(null)
        view.tag = address
        // Home's corner backdrop only needs soft edges; full-screen (details) art needs heavier shading under its text.
        val corner = backdrop && heroBackdrop.layoutParams.width > 0
        val key = if (corner) "backdrop-corner:$address" else if (backdrop) "backdrop:$address" else address
        fun show(bitmap: Bitmap) {
            view.setImageBitmap(bitmap)
            if (backdrop) {
                view.setTag(R.id.pov_backdrop_key, key)
                // The details art fades up from the dimmed Home version instead of switching at once.
                if (view.alpha < 1f) view.animate().alpha(1f).setDuration(300).setInterpolator(EASE_OUT).start() else view.alpha = 1f
            }
        }
        bitmapCache.get(key)?.let { show(it); loaded?.invoke(true); return }
        // Home's corner art has lighter shading than a details page needs; dim it until the right version is ready.
        if (backdrop && !corner && (view.getTag(R.id.pov_backdrop_key) as? String)?.startsWith("backdrop-corner:") == true) view.alpha = 0.4f
        // Decode only as large as the slot: cards ~200dp, feature art ~360dp, the backdrop at 1280px.
        val target = when { backdrop -> BACKDROP_WIDTH; feature || view.width >= dp(350) -> dp(360); else -> dp(200) }
        val load = Runnable {
            try {
                if (view.tag != address) return@Runnable
                bitmapCache.get(key)?.let { cached -> runOnUiThread { if (!isDestroyed && view.tag == address) { show(cached); loaded?.invoke(true) } }; return@Runnable }
                val bitmap = fetchBitmap(address, target)?.let { if (backdrop) shadedBackdrop(it, corner) else it }
                if (bitmap != null) { bitmapCache.put(key, bitmap); runOnUiThread { if (!isDestroyed && view.tag == address) { show(bitmap); loaded?.invoke(true) } } }
                else if (loaded != null) runOnUiThread { if (!isDestroyed && view.tag == address) loaded(false) }
            } catch (_: Exception) {
                /* The title remains usable when artwork is unavailable. */
                if (loaded != null) runOnUiThread { if (!isDestroyed && view.tag == address) loaded(false) }
            }
        }
        if (feature || backdrop) featureImages.execute(load) else images.execute(ImageTask(generation, imageOrder++, load))
    }

    private fun fetchBitmap(address: String, targetWidth: Int): Bitmap? {
        val bytes = ArtworkClient.bytes(address)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        // Opaque photos decode at 16 bits per pixel: half the memory and half the texture upload of a card.
        // (Hardware bitmaps would avoid the upload, but this TV's GPU cannot draw them inside rounded clips.)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; if (bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
        })
        // Starts the texture upload on a render worker so the first draw does not stall a scroll frame.
        bitmap?.prepareToDraw()
        return bitmap
    }

    /** Bakes the flix backdrop's left and bottom shading into the artwork: one full-screen layer instead of three. */
    private fun shadedBackdrop(source: Bitmap, corner: Boolean = false): Bitmap {
        val width = BACKDROP_WIDTH; val height = BACKDROP_WIDTH * 9 / 16
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(bg)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale; val drawnHeight = source.height * scale
        canvas.drawBitmap(source, null, android.graphics.RectF((width - drawnWidth) / 2, (height - drawnHeight) / 2,
            (width + drawnWidth) / 2, (height + drawnHeight) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val shade = android.graphics.Paint()
        // The phone banner has its copy below it, so only its foot fades into the page.
        if (DeviceLayout.tv) shade.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            if (corner) intArrayOf(bg, withAlpha(bg, 120), withAlpha(bg, 0), withAlpha(bg, 0), withAlpha(bg, 0))
            else intArrayOf(withAlpha(bg, 250), withAlpha(bg, 170), withAlpha(bg, 0), withAlpha(bg, 0)), null, android.graphics.Shader.TileMode.CLAMP)
        if (DeviceLayout.tv) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        shade.shader = android.graphics.LinearGradient(0f, height.toFloat(), 0f, 0f,
            if (corner) intArrayOf(bg, withAlpha(bg, 70), withAlpha(bg, 0), withAlpha(bg, 0))
            else if (DeviceLayout.phone) intArrayOf(bg, withAlpha(bg, 150), withAlpha(bg, 0), withAlpha(bg, 0))
            else intArrayOf(bg, withAlpha(bg, 215), withAlpha(bg, 0)), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        source.recycle()
        return out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
    }

    /** Thumbnails come from the image service's small renditions (480 px) instead of 1920 px originals. */
    private fun thumbnail(address: String) = when {
        address.startsWith("https://images.metahub.space/") -> address.replace("/background/medium/", "/background/small/").replace("/poster/medium/", "/poster/small/")
        // Cards are ~380 px wide on screen, so TMDB's 780 px backdrop keeps them sharp.
        address.startsWith("https://image.tmdb.org/t/p/w1280/") -> address.replace("/t/p/w1280/", "/t/p/w780/")
        else -> address
    }

    /** Warms the cache so the next rotating feature appears without a wait. */
    private fun prefetch(address: String, backdrop: Boolean = false) {
        val corner = backdrop && flix && heroBackdrop.layoutParams.width > 0
        val key = if (corner) "backdrop-corner:$address" else if (backdrop && flix) "backdrop:$address" else address
        if (!address.startsWith("https://") || bitmapCache.get(key) != null) return
        featureImages.execute {
            try {
                val shaded = backdrop && flix
                fetchBitmap(address, if (backdrop) BACKDROP_WIDTH else dp(360))?.let { bitmapCache.put(key, if (shaded) shadedBackdrop(it, corner) else it) }
            }
            catch (_: Exception) { }
        }
    }

    private fun horizontalRow(host: LinearLayout = body): LinearLayout {
        val scroller = TvHorizontalScrollView(this) { !(flix && isHome) }.apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val row = CullingLinearLayout(this, resources.displayMetrics.widthPixels / 2).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false; clipToPadding = false }
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> row.invalidate() }
        // Titles scrolled past slide out at the content edge instead of beneath the menu.
        if (flix) { scroller.clipChildren = true; scroller.clipToPadding = true; row.setPadding(dp(9), dp(7), dp(10), dp(7)) }
        scroller.addView(row); host.addView(scroller, margin(-1, -2).apply { if (flix) leftMargin = -dp(9) }); return row
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    /** Phone tab strip: equal-width buttons across the full width. */
    private fun phoneTabs(tabs: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this)
        tabs.forEachIndexed { index, (label, action) ->
            row.addView(button(label) { action() }.apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index < tabs.lastIndex) rightMargin = dp(8) })
        }
        body.addView(row, margin(-1, -2, bottom = 4))
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(size).toFloat()); setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL); includeFontPadding = false
    }
    private fun button(label: String, action: () -> Unit): TextView = text(label, 13, ink, true).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)); background = rounded(panel, 7)
        isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; contentDescription = label; tag = "action:$label"
        if (flix) {
            // Translucent until focused, then solid white with dark text.
            background = rounded(withAlpha(0xFF6D6D6E.toInt(), 150), 4)
            setOnFocusChangeListener { view, focus ->
                view.background = rounded(if (focus) Color.WHITE else withAlpha(0xFF6D6D6E.toInt(), 150), 4)
                setTextColor(if (focus) Color.BLACK else ink)
                view.animate().scaleX(if (focus) 1.05f else 1f).scaleY(if (focus) 1.05f else 1f).setDuration(120).start()
            }
            setOnClickListener { action() }
            pressable(4)
            return@apply
        }
        setOnFocusChangeListener { view, focus ->
            view.background = rounded(if (focus) accent else panel, 7)
            view.foreground = if (focus) FocusRingDrawable(accent, dp(7).toFloat(), true, dp(1).toFloat()) else null
            setTextColor(if (focus) Color.rgb(4, 15, 24) else ink)
        }
        setOnClickListener { action() }
    }
    private fun primaryButton(label: String, action: () -> Unit): TextView = text(label, 15, theme.palette.buttonText, true).apply {
        tag = "primary:$label"
        gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
        if (flix) {
            background = rounded(withAlpha(Color.WHITE, 225), 4)
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) Color.WHITE else withAlpha(Color.WHITE, 225), 4)
                view.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null
                view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
            }
            setOnClickListener { action() }
            pressable(4)
            return@apply
        }
        background = rounded(theme.palette.primaryButton, 6)
        setOnFocusChangeListener { view, focused ->
            view.foreground = if (focused) FocusRingDrawable(accent,
                dp(6).toFloat(), true, dp(1).toFloat()) else null
        }
        setOnClickListener { action() }
    }
    /** A sharp 2dp white outline: Flix's focus mark where a soft glow or zoom would blur text and video. */
    private fun crispOutline(radius: Int) = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), Color.WHITE) }
    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke) }
    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun margin(width: Int, height: Int, top: Int = 0, bottom: Int = 0, right: Int = 0) = LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height)).apply { setMargins(0, dp(top), dp(right), dp(bottom)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.widthPixels / DeviceLayout.canvasWidth.toFloat()).roundToInt()
    private var notice: TextView? = null
    private val hideNotice = Runnable { notice?.animate()?.alpha(0f)?.translationY(dp(12).toFloat())?.setDuration(220)?.withEndAction { notice?.visibility = View.GONE }?.start() }
    /** A short in-app notice styled like the app's dialogs, instead of the system toast. */
    private fun toast(message: String) {
        if (!::shellRoot.isInitialized || isDestroyed) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); return }
        val view = notice?.takeIf { it.parent === shellRoot } ?: text("", 14, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(11), dp(18), dp(11)); maxLines = 3; elevation = dp(6).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF22A2A30.toInt(), 0xF217171A.toInt())).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x2EFFFFFF)
            }
            shellRoot.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(if (DeviceLayout.phone) BOTTOM_BAR_HEIGHT + 16 else 28) })
            notice = this
        }
        view.text = message; view.bringToFront(); view.visibility = View.VISIBLE
        view.alpha = 0f; view.translationY = dp(12).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(200).start()
        view.removeCallbacks(hideNotice); view.postDelayed(hideNotice, 3_500)
    }
    /**
     * A long synopsis in its own capped, scrollable area: a plain dialog message would grow past the screen
     * and squeeze the Close button into a second scrolling strip.
     */
    private fun synopsisDialog(title: String, synopsis: String) {
        val density = resources.displayMetrics.density
        val body = TextView(this).apply {
            text = synopsis; textSize = 16f; setTextColor(0xFFE6E6E6.toInt()); setLineSpacing(0f, 1.15f)
            setPadding((24 * density).toInt(), (4 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val cap = (resources.displayMetrics.heightPixels * 0.6f).roundToInt()
        // Never taller than 60% of the screen. Focusable so the remote's Up/Down scrolls the text.
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }.apply { isFocusable = true; addView(body) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun confirm(message: String, action: () -> Unit)
 { AlertDialog.Builder(this).setMessage(message).setPositiveButton("Continue") { _, _ -> action() }.setNegativeButton("Cancel", null).show() }
}
