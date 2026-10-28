package dev.pov.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : Activity() {
    private lateinit var view: PlayerView
    private lateinit var controls: PlayerControlVisibility.Binding
    private lateinit var boostButton: Button
    private var activeDialog: Dialog? = null
    private var activeTrackType: Int? = null
    private var activeTrackChoices: List<PlayerTrackChoice> = emptyList()
    private var activeTracksLoading = false
    internal val currentOptionSheet: Dialog? get() = activeDialog
    private val player get() = InAppPlaybackSession.playerOrNull
    private val boost get() = InAppPlaybackSession.volumeBoost
    private val accent by lazy { ThemePreferences(this).selected().palette.accent }
    private var stopped = false
    private val seekPreview = DpadSeekPreview()
    private var seekKey = KeyEvent.KEYCODE_UNKNOWN
    private var resumeAfterSeek = false
    private lateinit var seekLabel: TextView
    private val timeline get() = view.findViewById<PreviewTimeBar>(androidx.media3.ui.R.id.exo_progress)
    private var revealKey = KeyEvent.KEYCODE_UNKNOWN
    private var cachedLinkAttempt = false
    private var observedError: PlaybackException? = null
    private lateinit var splash: FrameLayout
    private lateinit var splashLogo: ImageView
    private lateinit var splashTitle: TextView
    /** The title's transparent logo in the top-left corner while playback is paused. */
    private lateinit var pauseLogo: FrameLayout
    private lateinit var pauseLogoArt: ImageView
    private var splashPulse: ValueAnimator? = null
    private var everReady = false
    /** Until this time, buffering is a decoder catching up after a seek into loaded video, not a network stall. */
    private var quietSeekUntil = 0L
    private val splashHandler = Handler(Looper.getMainLooper())
    private val showSplashLater = Runnable { setSplash(true) }
    private val rowIds = listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio, R.id.pov_subtitles, R.id.pov_boost, R.id.pov_browse, R.id.pov_stop)
    private val sessionListener: () -> Unit = {
        if (!stopped) {
            updateControls()
            activeTrackType?.let { type ->
                player?.let { current ->
                    val loading = current.playbackState == Player.STATE_IDLE || current.playbackState == Player.STATE_BUFFERING
                    if (activeDialog?.isShowing == true && (loading != activeTracksLoading ||
                        PlayerTrackOptions.choices(current.currentTracks, current.trackSelectionParameters, type) != activeTrackChoices)) {
                        showTracks(type)
                    }
                }
            }
            val failure = InAppPlaybackSession.error
            if (failure != null && observedError !== failure) showPlaybackFailure(failure)
            if (!InAppPlaybackSession.hasActive()) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cachedLinkAttempt = intent.getBooleanExtra("cached_playback", false)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        InAppPlaybackSession.initialize(this)
        view = LayoutInflater.from(this).inflate(R.layout.pov_player, null, false) as PlayerView
        setContentView(view)
        controls = PlayerControlVisibility.bind(view)
        configureButtons()
        seekLabel = TextView(this).apply {
            tag = "pov_seek_preview"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD); setLineSpacing(0f, 0.9f)
            setPadding(dp(28), dp(14), dp(28), dp(14)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
        }
        requireNotNull(view.overlayFrameLayout).addView(seekLabel,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        buildSplash()
        openCurtain()
        val reuse = InAppPlaybackSession.hasActive() &&
            (intent.getBooleanExtra("reuse_session", false) || (savedInstanceState != null && InAppPlaybackSession.matches(intent)))
        if (!reuse && !InAppPlaybackSession.start(this, intent, savedInstanceState)) {
            showSheet("Cannot open video", "This playback link is invalid. Choose the source again.",
                listOf(PlayerSheetChoice("Back to browsing") { finish() }))
        }
    }

    private fun configureButtons() {
        // Focused controls become solid white pills with dark icons and text.
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.BLACK, 0xFF6B6B70.toInt(), Color.WHITE)
        )
        rowIds.forEachIndexed { index, id ->
            val button = view.findViewById<View>(id)
            button.isFocusable = true
            button.isFocusableInTouchMode = true
            button.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            button.nextFocusLeftId = rowIds[(index - 1).coerceAtLeast(0)]
            button.nextFocusRightId = rowIds[(index + 1).coerceAtMost(rowIds.lastIndex)]
            fun style(focused: Boolean) {
                // Transport buttons are round, the text actions are pills; neither zooms, so the highlight stays inside the strip.
                button.background = GradientDrawable().apply {
                    setColor(if (focused) Color.WHITE else Color.TRANSPARENT)
                    if (button is ImageView) shape = GradientDrawable.OVAL else cornerRadius = dp(20).toFloat()
                }
                if (button is TextView) {
                    button.setTextColor(if (focused) Color.BLACK else Color.WHITE)
                    button.compoundDrawableTintList = tint
                }
                if (button is ImageView) button.imageTintList = tint
            }
            style(false)
            button.setOnFocusChangeListener { _, focused -> style(focused) }
        }
        boostButton = view.findViewById(R.id.pov_boost)
        view.findViewById<View>(R.id.pov_audio).setOnClickListener { showTracks(C.TRACK_TYPE_AUDIO) }
        view.findViewById<View>(R.id.pov_subtitles).setOnClickListener { showTracks(C.TRACK_TYPE_TEXT) }
        boostButton.setOnClickListener { showBoost() }
        view.findViewById<View>(R.id.pov_browse).setOnClickListener { finish() }
        // Stop ends the session (no mini player) and returns to the page the video was started from.
        view.findViewById<View>(R.id.pov_stop).setOnClickListener { InAppPlaybackSession.stop(); finish() }
        view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).apply {
            setPlayedColor(accent)
            setKeyTimeIncrement(1000)
            nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
            nextFocusUpId = id
        }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        InAppPlaybackSession.addListener(sessionListener)
        InAppPlaybackSession.attachPlayerView(view)
        controls.start()
        view.removeCallbacks(endTimeTicker); view.post(endTimeTicker)
        updateControls()
        InAppPlaybackSession.error?.let { if (observedError !== it) showPlaybackFailure(it) }
        controls.show()
    }

    /** Stremio-style loading: the title's logo pulses over its blurred artwork while video loads or rebuffers. */
    private fun buildSplash() {
        splash = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isFocusable = false; tag = "pov_loading_splash" }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(dp(14).toFloat(), dp(14).toFloat(), Shader.TileMode.CLAMP))
        }
        splash.addView(art, FrameLayout.LayoutParams(-1, -1))
        splash.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = resources.displayMetrics.widthPixels * 0.7f
                colors = intArrayOf(0x33000000, 0xE6000000.toInt())
            }
        }, FrameLayout.LayoutParams(-1, -1))
        splashLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        splash.addView(splashLogo, FrameLayout.LayoutParams(dp(420), dp(160), Gravity.CENTER))
        splashTitle = TextView(this).apply {
            textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(dp(40), 0, dp(40), 0)
        }
        splash.addView(splashTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        requireNotNull(view.overlayFrameLayout).addView(splash, 0, FrameLayout.LayoutParams(-1, -1))
        pauseLogoArt = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; maxWidth = dp(190); maxHeight = dp(70) }
        pauseLogo = FrameLayout(this).apply {
            visibility = View.GONE; alpha = 0f; isFocusable = false
            addView(pauseLogoArt, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        requireNotNull(view.overlayFrameLayout).addView(pauseLogo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(30); leftMargin = dp(30)
        })
        val imdb = try { intent.getStringExtra("identity")?.let { org.json.JSONObject(it).optString("imdbId") } } catch (_: Exception) { null }
        if (imdb != null && imdb.matches(Regex("tt\\d+"))) {
            loadSplashImage("https://images.metahub.space/background/medium/$imdb/img") { art.setImageBitmap(it) }
            loadSplashImage("https://images.metahub.space/logo/medium/$imdb/img") {
                splashLogo.setImageBitmap(it); splashLogo.visibility = View.VISIBLE; splashTitle.visibility = View.GONE
                pauseLogoArt.setImageBitmap(withShadow(it)); updatePauseLogo()
            }
        }
    }

    private fun loadSplashImage(address: String, done: (android.graphics.Bitmap) -> Unit) {
        Thread {
            try {
                ArtworkClient.init(applicationContext)
                val bytes = ArtworkClient.bytes(address)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) runOnUiThread { if (!isDestroyed) done(bitmap) }
            } catch (_: Exception) { /* The title text remains on the splash. */ }
        }.start()
    }

    private fun setSplash(show: Boolean) {
        if (!::splash.isInitialized) return
        if (show) {
            splashTitle.text = InAppPlaybackSession.title.ifBlank { "Loading" }
            if (splash.visibility != View.VISIBLE) { splash.alpha = 0f; splash.visibility = View.VISIBLE }
            splash.animate().cancel(); splash.animate().alpha(1f).setDuration(220).start()
            if (splashPulse == null) splashPulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { val value = it.animatedValue as Float; splashLogo.alpha = value; splashTitle.alpha = value }
                start()
            }
        } else if (splash.visibility == View.VISIBLE) {
            splash.animate().cancel()
            splash.animate().alpha(0f).setDuration(260).withEndAction {
                splash.visibility = View.GONE; splashPulse?.cancel(); splashPulse = null
            }.start()
        }
    }

    private fun updateSplash() {
        val state = player?.playbackState
        if (state == Player.STATE_READY) everReady = true
        val waiting = InAppPlaybackSession.error == null && player != null &&
            (state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && !everReady))
        if (!waiting) { splashHandler.removeCallbacks(showSplashLater); setSplash(false); return }
        // First load shows at once; a rebuffer waits briefly so quick seeks never flash the splash.
        if (!everReady) setSplash(true)
        else if (splash.visibility != View.VISIBLE) {
            splashHandler.removeCallbacks(showSplashLater)
            val quiet = (quietSeekUntil - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)
            splashHandler.postDelayed(showSplashLater, maxOf(500, quiet))
        }
    }

    private val endTimeTicker = object : Runnable {
        override fun run() { updateEndTime(); if (!stopped) view.postDelayed(this, 15_000) }
    }
    /** "Ends at 9:42 PM": remaining time at the current speed, added to the TV's local clock. */
    private fun updateEndTime() {
        val label = view.findViewById<TextView>(R.id.pov_end_time) ?: return
        val exo = player
        val remaining = exo?.let { it.duration - it.currentPosition } ?: -1
        if (exo == null || exo.duration <= 0 || remaining <= 0) { label.visibility = View.GONE; return }
        val speed = exo.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        val end = java.util.Date(System.currentTimeMillis() + (remaining / speed).toLong())
        label.text = "Ends at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(end)
        label.visibility = View.VISIBLE
    }

    /** No panel behind the logo: a soft shadow traced from its own shape keeps it readable over a bright frame. */
    private fun withShadow(logo: android.graphics.Bitmap): android.graphics.Bitmap {
        val blur = dp(10)
        val out = android.graphics.Bitmap.createBitmap(logo.width + blur * 2, logo.height + blur * 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val shadow = android.graphics.Paint().apply {
            color = Color.BLACK; alpha = 170; maskFilter = android.graphics.BlurMaskFilter(blur.toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(logo.extractAlpha(), blur.toFloat(), blur.toFloat() + dp(2), shadow)
        canvas.drawBitmap(logo, blur.toFloat(), blur.toFloat(), null)
        return out
    }

    private fun updatePauseLogo() {
        if (!::pauseLogo.isInitialized) return
        val exo = player
        val show = exo != null && pauseLogoArt.drawable != null && exo.playbackState == Player.STATE_READY && !exo.playWhenReady &&
            !seekPreview.active && splash.visibility != View.VISIBLE && InAppPlaybackSession.error == null
        if (show == (pauseLogo.visibility == View.VISIBLE && pauseLogo.alpha > 0f && pauseLogo.tag != "hiding")) return
        pauseLogo.animate().cancel()
        if (show) {
            pauseLogo.tag = null; pauseLogo.visibility = View.VISIBLE
            pauseLogo.animate().alpha(1f).setDuration(260).start()
        } else {
            pauseLogo.tag = "hiding"
            pauseLogo.animate().alpha(0f).setDuration(160).withEndAction { pauseLogo.visibility = View.GONE; pauseLogo.tag = null }.start()
        }
    }

    private fun updateControls() {
        updateSplash()
        updatePauseLogo()
        updateEndTime()
        if (player?.playbackState == Player.STATE_READY) cachedLinkAttempt = false
        view.findViewById<TextView>(R.id.pov_playback_title).text = InAppPlaybackSession.title.ifBlank { "Now playing" }
        PlaybackDebrid.bind(view, InAppPlaybackSession.debridService)
        boostButton.text = when {
            boost.gainDb == 0 -> "Boost · Off"
            boost.state == VolumeBoostState.UNAVAILABLE -> "Boost · Unavailable"
            else -> "Boost · +${boost.gainDb} dB"
        }
        controls.refreshPlaybackState()
    }

    private fun showPlaybackFailure(error: PlaybackException) {
        if (stopped || isFinishing || isDestroyed) return
        observedError = error
        if (cachedLinkAttempt) {
            cachedLinkAttempt = false; setResult(RESULT_FIRST_USER, Intent().putExtra("cached_identity", intent.getStringExtra("identity"))); InAppPlaybackSession.stop(); finish(); return
        }
        if (error is VideoCompatibilityException) {
            showSheet("Choose a compatible video", error.issue.message,
                listOf(PlayerSheetChoice("Choose another source") { InAppPlaybackSession.stop(); finish() }))
            return
        }
        showSheet("Playback stopped",
            "The link may have expired, the connection may be unavailable, or this TV may not support the format. Code: ${error.errorCodeName}",
            listOf(
                PlayerSheetChoice("Retry") { if (!stopped) { observedError = null; InAppPlaybackSession.retry() } },
                PlayerSheetChoice("Browse sources") { finish() }
            ))
    }

    private fun showTracks(type: Int) {
        val exo = player ?: return
        val title = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
        val choices = PlayerTrackOptions.choices(exo.currentTracks, exo.trackSelectionParameters, type)
        val loading = exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_BUFFERING
        val detail = PlayerTrackOptions.description(choices, type, loading)
        val rows = choices.map { choice -> PlayerSheetChoice(choice.label, choice.checked, selectable = choice.selectable) {
            if (player === exo && !stopped) {
                val stillAvailable = choice.kind != TrackChoiceKind.TRACK || exo.currentTracks.groups.any {
                    it.mediaTrackGroup == choice.group && choice.index in 0 until it.length && it.isTrackSupported(choice.index)
                }
                if (stillAvailable) exo.trackSelectionParameters = PlayerTrackOptions.apply(exo.trackSelectionParameters, choice)
                else showSheet(title, "Tracks changed. Open this menu again to choose a current track.",
                    listOf(PlayerSheetChoice("Close") {}))
            }
        } }
        val menuRows = if (type == C.TRACK_TYPE_TEXT) rows + PlayerSheetChoice("Subtitle style") { showSubtitleStyle() } else rows
        showSheet(title, detail, menuRows)
        activeTrackType = type
        activeTrackChoices = choices
        activeTracksLoading = loading
    }

    private fun showSubtitleStyle() {
        val preferences = SubtitlePreferences(this)
        showSheet("Subtitle style", "Applies to text subtitles. Image subtitles keep their original appearance.", listOf(
            PlayerSheetChoice("Transparent background", preferences.transparentBackground) {
                preferences.transparentBackground = !preferences.transparentBackground
                preferences.applyTo(view); InAppPlaybackSession.refreshSubtitleStyle(); showSubtitleStyle()
            },
            PlayerSheetChoice("Bold text", preferences.boldText) {
                preferences.boldText = !preferences.boldText
                preferences.applyTo(view); InAppPlaybackSession.refreshSubtitleStyle(); showSubtitleStyle()
            },
            PlayerSheetChoice("Subtitle size · ${preferences.sizeLabel}") { showSubtitleSize() },
            PlayerSheetChoice("Subtitle position · ${preferences.positionLabel}") { showSubtitlePosition() }
        ))
    }

    private fun showSubtitlePosition() {
        finishSeek(cancelled = true)
        activeDialog?.dismiss()
        activeTrackType = null
        activeTrackChoices = emptyList()
        activeTracksLoading = false
        activeDialog = SubtitlePositionPicker.show(this, liveVideo = true,
            changed = { SubtitlePreferences(this).applyTo(view); InAppPlaybackSession.refreshSubtitleStyle() },
            dismissed = { activeDialog = null; if (!stopped) controls.show() })
    }

    private fun showSubtitleSize() {
        val preferences = SubtitlePreferences(this)
        showSheet("Subtitle size", "Medium keeps the original size.", SubtitlePreferences.SIZES.mapIndexed { index, size ->
            PlayerSheetChoice(SubtitlePreferences.SIZE_LABELS[index], preferences.sizePercent == size) {
                preferences.sizePercent = size
                preferences.applyTo(view); InAppPlaybackSession.refreshSubtitleStyle(); showSubtitleStyle()
            }
        })
    }

    private fun showBoost() {
        showSheet("Volume boost",
            "Boost decoded PCM audio in software for this playback session. Encoded passthrough bypasses it. Off restores normal audio.",
            SessionVolumeBoost.STEPS.map { db -> PlayerSheetChoice(
                if (db == 0) "Off · Normal audio" else "+$db dB", boost.gainDb == db
            ) {
                if (player != null && !stopped) {
                    val state = InAppPlaybackSession.setVolumeBoostGain(db)
                    updateControls()
                    val result = when (state) {
                        VolumeBoostState.OFF -> "Volume boost off"
                        VolumeBoostState.WAITING_FOR_AUDIO -> "Software boost will start when decoded audio flows"
                        VolumeBoostState.UNAVAILABLE -> "Software boost is unavailable on this audio route"
                        VolumeBoostState.REQUESTED -> "+${boost.gainDb} dB software boost active"
                    }
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                }
            } })
    }

    private fun showOptionsMenu() {
        showSheet("Playback options", "Choose an action. Playback continues while you browse.",
            listOf(
                PlayerSheetChoice("Audio") { showTracks(C.TRACK_TYPE_AUDIO) },
                PlayerSheetChoice("Subtitles") { showTracks(C.TRACK_TYPE_TEXT) },
                PlayerSheetChoice("Subtitle style") { showSubtitleStyle() },
                PlayerSheetChoice("Volume boost") { showBoost() },
                PlayerSheetChoice("Browse while playing") { finish() },
                PlayerSheetChoice("Stop playback") { InAppPlaybackSession.stop(); finish() }
            ))
    }

    private fun showSheet(title: String, detail: String, choices: List<PlayerSheetChoice>) {
        finishSeek(cancelled = true)
        activeDialog?.dismiss()
        activeTrackType = null
        activeTrackChoices = emptyList()
        activeTracksLoading = false
        if (isFinishing || isDestroyed) return
        controls.show()
        val dialog = PlayerOptionSheet.create(this, title, detail, choices, accent)
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activeTrackType = null
                activeTrackChoices = emptyList()
                activeTracksLoading = false
                if (!stopped) controls.show()
            }
        }
        dialog.show()
    }

    private fun beginOrMoveSeek(keyCode: Int): Boolean {
        val exo = player ?: return false
        if (!exo.isCurrentMediaItemSeekable || exo.duration <= 0) return false
        if (!seekPreview.active) {
            if (!seekPreview.begin(exo.currentPosition, exo.duration)) return false
            resumeAfterSeek = exo.playWhenReady
            exo.pause()
        }
        seekKey = keyCode
        seekPreview.step(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
        controls.show(); timeline.requestFocus()
        timeline.previewPosition = seekPreview.position
        seekLabel.text = "${DpadSeekPreview.time(seekPreview.position)}\n${DpadSeekPreview.offset(seekPreview.offsetSeconds * 1000)}  ·  ${DpadSeekPreview.time(exo.duration)}"
        seekLabel.visibility = View.VISIBLE
        return true
    }

    private fun finishSeek(cancelled: Boolean = false, restorePlayback: Boolean = true) {
        if (!seekPreview.active) return
        val target = seekPreview.finish(cancelled)
        timeline.previewPosition = null
        seekLabel.visibility = View.GONE
        seekKey = KeyEvent.KEYCODE_UNKNOWN
        player?.let { exo ->
            if (target != null) {
                timeline.setPosition(target)
                val from = exo.currentPosition
                // Video decodes forward from a keyframe, so an exact spot inside the buffer can take the onn a second
                // to reach. Landing on a nearby keyframe in the direction of the seek makes it near-instant.
                val tolerance = SEEK_SNAP_MS * 1000
                exo.setSeekParameters(if (target >= from) androidx.media3.exoplayer.SeekParameters(0, tolerance)
                    else androidx.media3.exoplayer.SeekParameters(tolerance, 0))
                if (target >= from - ReadAheadPreferences.BACK_BUFFER_MS && target <= exo.bufferedPosition)
                    quietSeekUntil = android.os.SystemClock.uptimeMillis() + QUIET_SEEK_MS
                exo.seekTo(target)
            }
            if (restorePlayback && resumeAfterSeek) exo.play()
        }
        controls.show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) finishSeek(cancelled = true, restorePlayback = false)
    }

    // Holding Back leaves the player for Settings; the video keeps its mini-player session.
    private val backHold = BackHoldGesture { BackHoldGesture.settingsRequested = true; if (seekPreview.active) finishSeek(cancelled = true); finish() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (backHold.intercept(event)) return true
        val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.action == KeyEvent.ACTION_UP && horizontal && seekPreview.active) {
            if (event.keyCode == seekKey) finishSeek(cancelled = event.isCanceled)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && seekPreview.active && !horizontal) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                finishSeek(cancelled = true); revealKey = KeyEvent.KEYCODE_BACK; return true
            }
            finishSeek()
        }
        if (event.action == KeyEvent.ACTION_DOWN && horizontal && activeDialog?.isShowing != true &&
            (seekPreview.active || !view.isControllerFullyVisible || currentFocus?.id == androidx.media3.ui.R.id.exo_progress || currentFocus === view)) {
            if (beginOrMoveSeek(event.keyCode)) return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == revealKey) {
            revealKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) controls.userInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_CAPTIONS) showTracks(C.TRACK_TYPE_TEXT) else showOptionsMenu()
            }
            return true
        }
        val dpad = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        if (event.action == KeyEvent.ACTION_DOWN && dpad) {
            if (!view.isControllerFullyVisible) {
                controls.show()
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
                revealKey = event.keyCode
                return true
            }
            val focused = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null && focused.id in rowIds) {
                view.findViewById<View>(androidx.media3.ui.R.id.exo_progress).requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focused?.id == androidx.media3.ui.R.id.exo_progress) {
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
                return true
            }
        }
        if (view.dispatchMediaKeyEvent(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) controls.show()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            seekPreview.active -> finishSeek(cancelled = true)
            // Back first tucks the controls away; a second Back leaves for the mini player.
            view.isControllerFullyVisible -> controls.dismiss()
            else -> finish()
        }
    }

    /**
     * A black curtain over everything, video included, fades the player in on open and out before it closes.
     * SurfaceView video and Activity window animations do not compose smoothly on older TVs, so the motion
     * happens inside the window instead.
     */
    private var curtain: View? = null
    private var closing = false

    private fun openCurtain() {
        val cover = View(this).apply { setBackgroundColor(Color.BLACK); isFocusable = false; isClickable = false }
        (window.decorView as android.view.ViewGroup).addView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
        curtain = cover
        view.scaleX = 0.96f; view.scaleY = 0.96f
        cover.post {
            view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            cover.animate().alpha(0f).setDuration(280).withEndAction { cover.visibility = View.GONE }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        if (!isFinishing && InAppPlaybackSession.error is VideoCompatibilityException) InAppPlaybackSession.stop()
        val cover = curtain
        if (closing || cover == null || isDestroyed || !window.decorView.isAttachedToWindow) { closeNow(); return }
        closing = true
        cover.animate().cancel(); cover.visibility = View.VISIBLE
        cover.animate().alpha(1f).setDuration(180).withEndAction { closeNow() }.start()
    }

    @Suppress("DEPRECATION")
    private fun closeNow() {
        closing = true
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        InAppPlaybackSession.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        finishSeek(cancelled = true, restorePlayback = false)
        stopped = true
        controls.stop()
        activeDialog?.dismiss(); activeDialog = null
        InAppPlaybackSession.removeListener(sessionListener)
        InAppPlaybackSession.detachPlayerView(view)
        super.onStop()
    }

    override fun onDestroy() {
        splashHandler.removeCallbacks(showSplashLater); splashPulse?.cancel()
        controls.release()
        InAppPlaybackSession.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** A seek may land up to this far from its target to reach a keyframe. */
        const val SEEK_SNAP_MS = 2_000L
        const val QUIET_SEEK_MS = 4_000L
        fun openCurrent(context: Context): Boolean {
            val intent = InAppPlaybackSession.currentIntent(context) ?: return false
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            @Suppress("DEPRECATION")
            if (context is Activity) context.overridePendingTransition(0, 0)
            return true
        }
    }
}

