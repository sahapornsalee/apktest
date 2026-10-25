package com.sahaporn.autoplay

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** The player owned by the open PlayerActivity, shared with the media session. */
object PlayerHolder {
    var player: Player? = null
}

/**
 * Keeps audio going with the screen off: a media session over the activity's player,
 * which gives a foreground notification and lock-screen controls while playing.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        val player = PlayerHolder.player ?: return
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: PlaybackService? = null

        /** Called when the player is about to be released. */
        fun shutdown() {
            val service = current ?: return
            service.session?.release()
            service.session = null
            service.stopSelf()
        }
    }
}
