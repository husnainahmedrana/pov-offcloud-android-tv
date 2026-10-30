package dev.pov.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** All playback chrome lives under Media3's controller, including buffering status and feature actions. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object PlayerControlVisibility {
    fun bind(playerView: PlayerView): Binding = Binding(playerView)

    class Binding internal constructor(private val view: PlayerView) {
        private val handler = Handler(Looper.getMainLooper())
        private var active = true
        private var lastInteractionAt = SystemClock.elapsedRealtime()
        private val hide = Runnable {
            if (active && shouldAutoHide()) view.hideController()
        }

        /** Called after the controls appear or hide. */
        var visibilityChanged: (() -> Unit)? = null

        init {
            view.setControllerAnimationEnabled(false)
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                // Controls opened by a tap count as an interaction; the timeout runs from now, not the last key.
                if (visibility == View.VISIBLE) { lastInteractionAt = SystemClock.elapsedRealtime(); schedule() }
                else handler.removeCallbacks(hide)
                visibilityChanged?.invoke()
            })
            refreshPlaybackState()
        }

        fun start() { active = true; lastInteractionAt = SystemClock.elapsedRealtime(); refreshPlaybackState() }
        fun stop() { active = false; handler.removeCallbacks(hide) }
        fun release() {
            stop()
            view.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
        }
        fun dismiss() { handler.removeCallbacks(hide); view.hideController() }
        fun show() { lastInteractionAt = SystemClock.elapsedRealtime(); view.showController(); schedule() }
        /** Any key, or a touch on the phone, keeps open controls up for another full timeout. */
        fun userInteraction() {
            if (view.isControllerFullyVisible) { lastInteractionAt = SystemClock.elapsedRealtime(); schedule() }
        }

        fun refreshPlaybackState() {
            // This timeout is independent of focused descendants. Merely focusing Audio/Boost must not pin chrome.
            schedule()
        }

        private fun shouldAutoHide(): Boolean {
            val player = view.player ?: return false
            return player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        }

        private fun schedule() {
            handler.removeCallbacks(hide)
            if (active && view.isControllerFullyVisible && shouldAutoHide()) {
                val timeout = view.controllerShowTimeoutMs.takeIf { it > 0 } ?: 4_000
                val remaining = (timeout - (SystemClock.elapsedRealtime() - lastInteractionAt)).coerceAtLeast(1)
                handler.postDelayed(hide, remaining)
            }
        }
    }
}

