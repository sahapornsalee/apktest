package com.sahaporn.autoplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** The "now playing" notification, with previous / play-pause / next buttons. */
object PlaybackNotification {
    const val ID = 7
    const val ACTION_PLAY_PAUSE = "com.sahaporn.autoplay.action.PLAY_PAUSE"
    const val ACTION_NEXT = "com.sahaporn.autoplay.action.NEXT"
    const val ACTION_PREV = "com.sahaporn.autoplay.action.PREV"
    private const val CHANNEL = "playback"

    /** Latest notification, picked up by [KeepAliveService] when it starts. */
    var current: Notification? = null

    fun build(
        ctx: Context,
        title: String,
        text: String,
        thumbUri: String,
        playing: Boolean,
        token: MediaSession.Token,
    ): Notification {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "กำลังเล่นวิดีโอ", NotificationManager.IMPORTANCE_LOW)
        )
        fun action(icon: Int, label: String, action: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(ctx, icon),
            label,
            PendingIntent.getBroadcast(
                ctx, code, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(action(R.drawable.ic_prev, "ก่อนหน้า", ACTION_PREV, 1))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "หยุด", ACTION_PLAY_PAUSE, 2)
                else action(R.drawable.ic_play, "เล่น", ACTION_PLAY_PAUSE, 2)
            )
            .addAction(action(R.drawable.ic_next, "ถัดไป", ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
        Thumbs.cached(thumbUri)?.let { builder.setLargeIcon(it) }
        return builder.build()
    }
}

/**
 * Foreground service that shows [PlaybackNotification.current], so Android keeps the player
 * running while the screen is off.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = PlaybackNotification.current
        if (notification == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, PlaybackNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    companion object {
        /** Returns false when Android refuses (e.g. the app is no longer in front). */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
