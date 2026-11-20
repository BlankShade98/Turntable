package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.PlaybackTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service (`dataSync`) that keeps Turntable's process unfrozen
 * on Android 12–16 so the application-level SpotifyBroadcastReceiver receives every
 * Spotify `metadatachanged` and `playbackstatechanged` broadcast immediately.
 */
class SpotifySyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        val initialNotification = buildNotification(this, PlaybackTrack())
        startInForeground(initialNotification)
        _isRunning.value = true

        // Refresh notification with whatever track is already stored
        serviceScope.launch {
            val currentTrack = TurntablePreferences(applicationContext).getTrack()
            updateNotificationIfRunning(applicationContext, currentTrack)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync FGS timeout: stop cleanly so the OS never raises RemoteServiceException.
        stopSelf(startId)
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "turntable_live_sync"
        private const val NOTIFICATION_ID = 4109
        private const val ACTION_STOP = "com.example.action.STOP_SPOTIFY_SYNC"

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SpotifySyncService::class.java)
            return runCatching {
                ContextCompat.startForegroundService(appContext, intent)
                true
            }.getOrDefault(false)
        }

        suspend fun startIfEnabled(context: Context) {
            val appContext = context.applicationContext
            if (_isRunning.value) return
            val enabled = runCatching {
                TurntablePreferences(appContext).isLiveSyncEnabled()
            }.getOrDefault(true)
            if (enabled) {
                start(appContext)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, SpotifySyncService::class.java))
            }
            _isRunning.value = false
        }

        fun updateNotificationIfRunning(context: Context, track: PlaybackTrack) {
            if (!_isRunning.value) return
            val appContext = context.applicationContext
            ensureNotificationChannel(appContext)
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            runCatching {
                manager.notify(NOTIFICATION_ID, buildNotification(appContext, track))
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.sync_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.sync_channel_description)
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        private fun buildNotification(context: Context, track: PlaybackTrack): Notification {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val contentTitle = if (track.hasTrack) {
                track.title
            } else {
                "Turntable Live Sync"
            }
            val contentText = if (track.hasTrack) {
                val stateLabel = if (track.isPlaying) "Playing" else "Paused"
                "${track.artist.ifBlank { "Spotify" }} • $stateLabel"
            } else {
                "Listening for Spotify song & play/pause updates"
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget_music_waves)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
