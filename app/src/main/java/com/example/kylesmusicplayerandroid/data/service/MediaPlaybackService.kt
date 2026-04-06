package com.example.kylesmusicplayerandroid.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.example.kylesmusicplayerandroid.MainActivity

/**
 * MODEL B (Production / Car / BT Gold Standard):
 * - The Service owns the ExoPlayer (authoritative playback owner).
 * - The Service owns the MediaSession (system controllers talk here).
 * - UI/VM connects via MediaController.
 *
 * This makes playback survive UI, and keeps Bluetooth/lockscreen stable.
 */
class MediaPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())

        ensurePlayerAndSession()
        Log.w(TAG, "MediaPlaybackService: onCreate complete (player+session ensured).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep alive for BT/lockscreen controllers.
        ensurePlayerAndSession()
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Called when controllers connect (BT, lockscreen, car, etc.)
        ensurePlayerAndSession()
        return session
    }

    /**
     * Production behavior when app is swiped away:
     * - If currently playing: keep service alive (music app standard).
     * - If not playing: stop service cleanly to avoid a "ghost" foreground service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        val playing = p?.isPlaying == true
        Log.w(TAG, "MediaPlaybackService: onTaskRemoved (isPlaying=$playing)")

        if (!playing) {
            stopServiceAndRelease()
        }
        // If playing: do nothing (keep running for truck/BT stability).
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServiceAndRelease()
        super.onDestroy()
        Log.w(TAG, "MediaPlaybackService: destroyed (player+session released).")
    }

    private fun stopServiceAndRelease() {
        try {
            // Stop foreground first (removes notif if we're stopping)
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) { }

        try {
            session?.release()
        } catch (_: Throwable) { }
        session = null

        try {
            player?.release()
        } catch (_: Throwable) { }
        player = null

        PlayerRegistry.clear()

        try {
            stopSelf()
        } catch (_: Throwable) { }
    }

    private fun ensurePlayerAndSession() {
        if (player == null) {
            val p = ExoPlayer.Builder(this).build()

            // Proper audio focus + route-loss handling (BT disconnect, truck stereo off, etc.)
            val attrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            p.setAudioAttributes(attrs, /* handleAudioFocus= */ true)
            p.setHandleAudioBecomingNoisy(true)

            // Minimal sanity logging
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "Player isPlaying=$isPlaying")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d(TAG, "Player state=$playbackState")
                }
            })

            player = p
            PlayerRegistry.set(p)
            Log.w(TAG, "MediaPlaybackService: ExoPlayer created and registered.")
        }

        if (session == null) {
            val p = player ?: return

            // Tap notification → reopen app
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pending = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            session = MediaSession.Builder(this, p)
                .setId(SESSION_ID)
                .setSessionActivity(pending)
                .build()

            Log.w(TAG, "MediaPlaybackService: MediaSession created (id=$SESSION_ID).")
        }
    }

    // ------------------------------------------------------------
    // Notification (minimal placeholder — media-style can come later)
    // ------------------------------------------------------------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kyle’s Music Player")
            .setContentText("Playback service running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MediaPlaybackService"
        private const val CHANNEL_ID = "kmp_playback"
        private const val NOTIFICATION_ID = 1001

        // Stable session id (helps debugging + deterministic controller behavior)
        private const val SESSION_ID = "kmp_main_session"

        /**
         * Start (or keep) the service alive so the MediaSession is available for BT controls.
         * Safe to call multiple times.
         */
        fun ensureServiceRunning(ctx: Context) {
            val appCtx = ctx.applicationContext
            val i = Intent(appCtx, MediaPlaybackService::class.java)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(i)
                } else {
                    appCtx.startService(i)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MediaPlaybackService", t)
            }
        }

        /**
         * SessionToken used by UI/VM to connect via MediaController (Model B).
         */
        fun getSessionToken(ctx: Context): SessionToken {
            val appCtx = ctx.applicationContext
            return SessionToken(appCtx, ComponentName(appCtx, MediaPlaybackService::class.java))
        }

        /**
         * Temporary migration bridge ONLY (avoid using long-term).
         * The final architecture should be MediaController-only.
         */
        fun tryGetAuthoritativePlayerForMigrationOnly(): ExoPlayer? = PlayerRegistry.get()
    }
}

/**
 * Process-local registry so the rest of the app can retrieve the authoritative player
 * IF needed during migration.
 *
 * Final Model B target is: UI/VM connects via MediaController, not direct ExoPlayer access.
 * But this helps us bridge without touching sync logic.
 */
private object PlayerRegistry {
    @Volatile private var player: ExoPlayer? = null
    fun set(p: ExoPlayer) { player = p }
    fun get(): ExoPlayer? = player
    fun clear() { player = null }
}