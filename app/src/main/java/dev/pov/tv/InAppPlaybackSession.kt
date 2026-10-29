package dev.pov.tv

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.WeTrakrApi
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * One in-app playback session shared by browsing and fullscreen.
 * No background service: playback pauses when all application activities leave the foreground.
 * Views borrow the player; destroying an Activity does not destroy the session.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object InAppPlaybackSession {
    private var application: Application? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var targetView: WeakReference<PlayerView>? = null
    private lateinit var history: PlaybackStore
    private lateinit var wetrakr: WeTrakrApi
    private lateinit var wetrakrOutbox: PlaybackStore
    private var sourceUrl = ""
    private var sourceKey = ""
    private var playbackAccount: String? = null
    private var lastLinkSaved = false
    var debridService: String? = null
        private set
    private var identity: PlaybackIdentity? = null
    var title: String = ""
    /** File name/release text and size of the link being played, for the player's badges. */
    var mediaName: String = ""
    var mediaSize: Long? = null
        private set
    var error: PlaybackException? = null
        private set
    val playerOrNull: ExoPlayer? get() = player
    internal val volumeBoost = SessionVolumeBoost(
        dispatchToMain = { task -> handler.post { task() } },
        onStateChanged = { notifyChanged() },
    )
    private var remotePercent = 0.0
    private var releasing = false
    private var lastSyncAction = ""
    private var lastSyncAt = 0L
    private var resumeAfterBackground = false
    private var backgroundPause = false
    private val listeners = linkedSetOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val surfaceAttachment = SurfaceReadyAttachment<ExoPlayer, PlayerView>(
        currentSession = { player?.takeUnless { releasing } },
        isReady = { view ->
            view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                (view.videoSurfaceView as? SurfaceView)?.holder?.surface?.isValid == true
        },
        observeReadiness = { view, ready -> observeSurfaceReadiness(view, ready) },
        attach = { exo, view ->
            val previous = targetView?.get()
            if (previous === view) {
                if (view.player !== exo) view.player = exo
            } else PlayerView.switchTargetView(exo, previous, view)
            previous?.keepScreenOn = false
            targetView = WeakReference(view)
            SubtitlePreferences(view.context).applyTo(view)
            view.keepScreenOn = exo.isPlaying
            // Pending attachment can finish after Activity.onStart. Refresh controller scheduling
            // only after ownership is committed; same-target reentry below does not notify again.
            notifyChanged()
        },
    )
    private val syncWorker = Executors.newSingleThreadExecutor()
    private val checkpoint = object : Runnable {
        override fun run() {
            if (player == null || releasing) return
            saveLocal()
            handler.postDelayed(this, 15_000)
        }
    }
    private val foregroundGate = PlaybackForegroundGate(
        schedule = { handler.postDelayed(it, 350) },
        cancel = { handler.removeCallbacks(it) },
        onBackground = {
            val current = player
            resumeAfterBackground = current != null && current.playWhenReady &&
                current.playbackState != Player.STATE_ENDED && current.playerError == null && error == null
            backgroundPause = true
            current?.pause()
            backgroundPause = false
            handler.removeCallbacks(checkpoint)
        },
        onForeground = {
            if (resumeAfterBackground && error == null && player?.playerError == null && player?.playbackState != Player.STATE_ENDED) player?.play()
            resumeAfterBackground = false
            restartCheckpoint()
        }
    )

    fun initialize(context: Context) {
        if (application != null) return
        val app = context.applicationContext as Application
        application = app
        history = PlaybackStore(app)
        wetrakr = WeTrakrApi(SecureStore(app))
        wetrakrOutbox = PlaybackStore(app, "wetrakr.pending")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foregroundGate.started(activity) }
            override fun onActivityStopped(activity: Activity) { foregroundGate.stopped(activity) }
            override fun onActivityDestroyed(activity: Activity) { foregroundGate.stopped(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    fun hasActive(): Boolean = player != null
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** Returns false for an invalid link without disturbing an existing session. */
    internal fun start(context: Context, intent: Intent, saved: Bundle? = null): Boolean {
        initialize(context)
        val address = intent.getStringExtra("url").orEmpty()
        val uri = try { Uri.parse(address) } catch (_: RuntimeException) { null }
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        stopInternal(notify = false)
        sourceUrl = address
        sourceKey = intent.getStringExtra("key").orEmpty()
        playbackAccount = saved?.getString("playback_account") ?: intent.getStringExtra("playback_account")
        lastLinkSaved = false
        debridService = PlaybackDebrid.normalize(saved?.getString("debrid_service") ?: intent.getStringExtra("debrid_service"))
        title = intent.getStringExtra("title").orEmpty()
        if (saved == null) SubtitleSync.reset()
        mediaName = intent.getStringExtra("media_name").orEmpty().take(4000)
        mediaSize = intent.getLongExtra("media_size", 0L).takeIf { it > 0 }
        identity = try { intent.getStringExtra("identity")?.let { PlaybackIdentity.fromJson(JSONObject(it)) } } catch (_: Exception) { null }
        if (saved == null) { history.show(sourceKey, identity); identity?.let(wetrakr::allowPlayback) }
        val position = (saved?.getLong("position") ?: intent.getLongExtra("resume", 0L)).coerceAtLeast(0)
        remotePercent = saved?.getDouble("remotePercent") ?: if (position == 0L) intent.getDoubleExtra("resume_percent", 0.0) else 0.0
        if (!remotePercent.isFinite() || remotePercent !in 0.0..100.0) remotePercent = 0.0
        val shouldPlay = saved?.getBoolean("playWhenReady", true) ?: intent.getBooleanExtra("playWhenReady", true)
        val parameters = try { saved?.getBundle("tracks")?.let(TrackSelectionParameters::fromBundle) } catch (_: RuntimeException) { null }
        releasing = false
        error = null
        lastSyncAction = ""
        lastSyncAt = 0L
        val gainProcessor = volumeBoost.createAudioProcessor()
        val renderers = object : DefaultRenderersFactory(application!!) {
            init { setEnableDecoderFallback(true) }
            override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
                val builder = androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory).setMediaCodecSelector(mediaCodecSelector)
                    .setEnableDecoderFallback(enableDecoderFallback).setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEventHandler(eventHandler).setEventListener(eventListener).setMaxDroppedFramesToNotify(50)
                out.add(DolbyVisionVideoRenderer(builder, context, VideoCompatibilityPreferences(context).mode))
            }
            override fun buildTextRenderers(context: Context, output: androidx.media3.exoplayer.text.TextOutput, outputLooper: Looper,
                extensionRendererMode: Int, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
                val built = ArrayList<androidx.media3.exoplayer.Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
                built.mapTo(out, ::SubtitleSyncRenderer)
            }
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
                    .build()
                return object : ForwardingAudioSink(sink) {
                    private var encodedRoute = false
                    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
                        super.configure(audioSinkConfig)
                        // Keep device-supported encoded passthrough playable; it bypasses PCM DSP.
                        encodedRoute = audioSinkConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW
                        if (encodedRoute) gainProcessor.onEncodedPassthrough()
                    }
                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                        // A pending format switch may drain old PCM during this call. The selected
                        // encoded route still must finish with an honest bypass status.
                        if (encodedRoute) gainProcessor.onEncodedPassthrough()
                        return handled
                    }
                }
            }
        }
        val exo = ExoPlayer.Builder(application!!, renderers)
            .setLoadControl(ReadAheadPreferences(application!!).loadControl())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player = exo
        exo.trackSelectionParameters = SubtitlePreferences(application!!).trackParameters(exo.trackSelectionParameters)
        exo.trackSelectionParameters = AudioPreferences(application!!).trackParameters(exo.trackSelectionParameters)
        parameters?.let { exo.trackSelectionParameters = it }
        val openApp = PendingIntent.getActivity(application, 0, Intent(application, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(application!!, exo).setSessionActivity(openApp).build()
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // PlayerView has received onCues before this batched callback. Apply custom
                // text positions last so embedded cue regions cannot restore the old location.
                if (isCurrent(exo) && events.contains(Player.EVENT_CUES)) {
                    targetView?.get()?.let { target -> target.subtitleView?.setCues(
                        SubtitlePreferences(target.context).positionedCues(player.currentCues.cues)) }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (!isCurrent(exo) || enforceVideoCompatibility(exo, tracks)) return
                notifyChanged()
            }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (isCurrent(exo)) notifyChanged()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrent(exo)) return
                if (isPlaying && enforceVideoCompatibility(exo)) return
                targetView?.get()?.keepScreenOn = isPlaying
                // Buffering is not a user pause. Only genuine playback starts are recorded here.
                if (isPlaying && exo.duration > 0) { saveLocal(); sync("start") }
                notifyChanged()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!isCurrent(exo)) return
                if (playWhenReady && enforceVideoCompatibility(exo)) return
                if (!backgroundPause && !playWhenReady) resumeAfterBackground = false
                if (!playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.duration > 0) {
                    saveLocal(); sync("pause")
                }
                notifyChanged()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (isCurrent(exo) && playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && exo.duration > 0) {
                    saveLocal(); sync("pause")
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!isCurrent(exo)) return
                if (state != Player.STATE_ENDED && enforceVideoCompatibility(exo)) return
                if (state == Player.STATE_READY && !lastLinkSaved) {
                    lastLinkSaved = true
                    val media = identity; val account = playbackAccount
                    if (media != null && account != null) runCatching {
                        dev.pov.tv.data.LastPlayedLinks(SecureStore(application!!)).save(media, sourceUrl, sourceKey, debridService, account)
                    }
                }
                if (state == Player.STATE_READY && remotePercent > 0 && exo.duration > 0) {
                    val percent = remotePercent
                    remotePercent = 0.0
                    exo.seekTo((exo.duration * percent / 100).toLong())
                }
                if (state == Player.STATE_ENDED) {
                    saveLocal(); sync("stop")
                    volumeBoost.resetGain()
                    targetView?.get()?.keepScreenOn = false
                    handler.removeCallbacks(checkpoint)
                }
                notifyChanged()
            }
            override fun onPlayerError(failure: PlaybackException) {
                if (!isCurrent(exo)) return
                if (error !is VideoCompatibilityException) error = failure
                saveLocal(); sync("pause")
                exo.pause()
                volumeBoost.resetGain()
                handler.removeCallbacks(checkpoint)
                notifyChanged()
            }
        })
        exo.setMediaItem(MediaItem.Builder().setUri(address)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        exo.seekTo(position)
        exo.prepare()
        exo.playWhenReady = shouldPlay
        restartCheckpoint()
        notifyChanged()
        return true
    }

    internal fun matches(intent: Intent): Boolean = player != null &&
        sourceUrl == intent.getStringExtra("url").orEmpty() && sourceKey == intent.getStringExtra("key").orEmpty()

    fun refreshAudioLanguage() {
        val context = application ?: return
        player?.let { it.trackSelectionParameters = AudioPreferences(context).trackParameters(it.trackSelectionParameters) }
    }

    fun refreshSubtitleLanguage() {
        val context = application ?: return
        player?.let { it.trackSelectionParameters = SubtitlePreferences(context).trackParameters(it.trackSelectionParameters) }
    }

    fun refreshSubtitleStyle() {
        targetView?.get()?.let { SubtitlePreferences(it.context).applyTo(it) }
    }

    fun attachPlayerView(view: PlayerView) {
        SubtitlePreferences(view.context).applyTo(view)
        val exo = player ?: run { view.player = null; return }
        if (targetView?.get() === view && view.player === exo) {
            view.keepScreenOn = exo.isPlaying
            return
        }
        surfaceAttachment.request(exo, view)
    }

    fun detachPlayerView(view: PlayerView) {
        surfaceAttachment.cancel(view)
        if (targetView?.get() === view) targetView = null
        if (view.player != null) view.player = null
        view.keepScreenOn = false
    }

    fun retry() {
        val exo = player ?: return
        // A decoder capability failure is deterministic for this copy; don't restart audio only.
        if (error is VideoCompatibilityException) { notifyChanged(); return }
        error = null
        exo.prepare(); exo.play()
        restartCheckpoint()
        notifyChanged()
    }

    fun stop() = stopInternal(notify = true)

    private fun stopInternal(notify: Boolean) {
        val exo = player
        if (exo != null) {
            saveLocal()
            if (exo.playbackState != Player.STATE_ENDED) {
                val watched = exo.duration > 0 && exo.currentPosition.toDouble() / exo.duration >= 0.80
                sync(if (watched) "stop" else "pause")
            }
        }
        releasing = true
        surfaceAttachment.cancelAll()
        handler.removeCallbacks(checkpoint)
        resumeAfterBackground = false
        volumeBoost.close()
        targetView?.get()?.let { it.player = null; it.keepScreenOn = false }
        targetView = null
        mediaSession?.release(); mediaSession = null
        exo?.release(); player = null
        sourceUrl = ""; sourceKey = ""; title = ""; mediaName = ""; mediaSize = null; identity = null; debridService = null; playbackAccount = null; lastLinkSaved = false
        remotePercent = 0.0; error = null
        if (notify) notifyChanged()
    }

    internal fun saveState(out: Bundle) {
        val exo = player ?: return
        out.putLong("position", exo.currentPosition.coerceAtLeast(0))
        out.putString("debrid_service", debridService)
        out.putString("playback_account", playbackAccount)
        out.putBoolean("playWhenReady", exo.playWhenReady && exo.playbackState != Player.STATE_ENDED)
        out.putDouble("remotePercent", remotePercent)
        out.putBundle("tracks", exo.trackSelectionParameters.toBundle())
    }

    internal fun currentIntent(context: Context): Intent? {
        val exo = player ?: return null
        return Intent(context, PlayerActivity::class.java).putExtra("reuse_session", true)
            .putExtra("url", sourceUrl).putExtra("title", title).putExtra("key", sourceKey)
            .putExtra("media_name", mediaName).putExtra("media_size", mediaSize ?: 0L)
            .putExtra("debrid_service", debridService).putExtra("playback_account", playbackAccount)
            .putExtra("resume", exo.currentPosition).putExtra("resume_percent", remotePercent)
            .putExtra("playWhenReady", exo.playWhenReady)
            .also { value -> identity?.let { value.putExtra("identity", it.toJson().toString()) } }
    }

    internal fun setVolumeBoostGain(db: Int): VolumeBoostState {
        val result = volumeBoost.setGain(db)
        notifyChanged()
        return result
    }

    private fun isCurrent(exo: ExoPlayer) = !releasing && player === exo
    private fun observeSurfaceReadiness(view: PlayerView, ready: () -> Unit): () -> Unit {
        val holder = (view.videoSurfaceView as? SurfaceView)?.holder
        val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { ready() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { ready() }
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        // Creation can precede visible-window/layout state. The first eligible draw retries the
        // same pending request; no surface is attached while its holder is still invalid.
        val observer = view.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener { ready(); true }
        holder?.addCallback(surfaceCallback)
        observer.addOnPreDrawListener(preDraw)
        return {
            holder?.removeCallback(surfaceCallback)
            if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
            else view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        }
    }
    private fun enforceVideoCompatibility(exo: ExoPlayer, tracks: Tracks = exo.currentTracks): Boolean {
        val existing = error as? VideoCompatibilityException
        val issue = existing?.issue ?: VideoPlaybackCompatibility.issue(tracks, exo.playbackState, exo.videoFormat)
        if (issue == null) return false
        if (existing == null) error = VideoCompatibilityException(issue)
        resumeAfterBackground = false
        exo.pause()
        volumeBoost.resetGain()
        targetView?.get()?.keepScreenOn = false
        handler.removeCallbacks(checkpoint)
        notifyChanged()
        return true
    }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun restartCheckpoint() {
        handler.removeCallbacks(checkpoint)
        if (player != null && player?.playbackState != Player.STATE_ENDED && error == null) handler.postDelayed(checkpoint, 15_000)
    }
    private fun saveLocal() {
        if (error is VideoCompatibilityException) return
        // Public-stream audits and other transient playback must not create history entries.
        if (sourceKey.isBlank()) return
        val exo = player ?: return
        if (exo.duration <= 0) return
        try { history.save(sourceKey, title, identity, exo.currentPosition.coerceAtLeast(0), exo.duration) }
        catch (_: Exception) { /* Preserve playback if local storage is unavailable. */ }
    }
    private fun sync(action: String) {
        if (error is VideoCompatibilityException) return
        val media = identity ?: return
        val exo = player ?: return
        if (exo.duration <= 0) return
        if (!wetrakr.scrobblingEnabled() || history.isHidden(identity = media)) return
        val targets = listOf(wetrakr).mapNotNull { client ->
            try { if (client.connected()) client to client.accountRevision() else null } catch (_: Exception) { null }
        }
        if (targets.isEmpty()) return
        val percent = (exo.currentPosition.toDouble() * 100 / exo.duration).coerceIn(0.0, 100.0)
        val now = android.os.SystemClock.elapsedRealtime()
        if (action == lastSyncAction && now - lastSyncAt < 3_000) return
        lastSyncAction = action; lastSyncAt = now
        val scrobbleRevision = wetrakr.scrobbleRevision()
        val updates = targets.map { (client, account) ->
            val outbox = wetrakrOutbox
            val revision = try { if (action != "start") outbox.queue(media, percent, action, account, scrobbleRevision = scrobbleRevision) else null } catch (_: Exception) { null }
            Triple(client, account, outbox to revision)
        }
        syncWorker.execute {
            updates.forEach { (client, account, queued) ->
                try {
                    if (!history.isHidden(identity = media) && client.progressForAccount(media, percent, action, account, scrobbleRevision) && queued.second != null) queued.first.acknowledge(media, queued.second!!)
                } catch (_: Exception) { /* Each provider keeps its own final-state retry. */ }
            }
        }
    }
}

