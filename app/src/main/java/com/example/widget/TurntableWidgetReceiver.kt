package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.data.MediaKeyBridge
import com.example.data.MediaNotificationListenerService
import com.example.data.SpotifyRepository
import com.example.data.SpotifySyncService
import com.example.data.TurntablePreferences
import com.example.data.WidgetStateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Direct native AppWidgetProvider for the Turntable Spotify widget.
 * Handles home screen button clicks and layout resizes immediately without WorkManager lag.
 */
class TurntableWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action in WIDGET_ACTIONS) {
            val appContext = context.applicationContext
            if (action == ACTION_OPEN_SPOTIFY) {
                MediaKeyBridge.openSpotify(appContext)
                return
            }

            // Ultra-fast synchronous path when not using Spotify Web API OAuth:
            // dispatches strictly to Spotify's MediaController (or Spotify's MediaButtonReceiver).
            if (WidgetStateCache.isWarmed && !WidgetStateCache.isAuthenticated) {
                var optimisticNextPlaying: Boolean? = null
                when (action) {
                    ACTION_PLAY_PAUSE -> {
                        val directState = MediaNotificationListenerService.dispatchPlayPause()
                        val nextPlaying = if (directState != null) {
                            directState
                        } else if (MediaKeyBridge.playPause(appContext)) {
                            !WidgetStateCache.track.isPlaying
                        } else {
                            null
                        }
                        if (nextPlaying != null) {
                            optimisticNextPlaying = nextPlaying
                            WidgetStateCache.track = WidgetStateCache.track.copy(
                                isPlaying = nextPlaying,
                            )
                        }
                    }
                    ACTION_NEXT -> {
                        if (!MediaNotificationListenerService.dispatchNext()) {
                            MediaKeyBridge.next(appContext)
                        }
                    }
                    ACTION_PREVIOUS -> {
                        if (!MediaNotificationListenerService.dispatchPrevious()) {
                            MediaKeyBridge.previous(appContext)
                        }
                    }
                }

                val pendingResult = goAsync()
                receiverScope.launch {
                    try {
                        val nextPlaying = optimisticNextPlaying
                        if (nextPlaying != null) {
                            WidgetUpdater.pushWidgetUpdate(
                                appContext,
                                overrideTrack = WidgetStateCache.track,
                            )
                            SpotifyRepository(appContext).syncCacheForPlaybackState(WidgetStateCache.track)
                        }
                        if (WidgetStateCache.liveSyncEnabled) {
                            runCatching { SpotifySyncService.startIfEnabled(appContext) }
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }

            val pendingResult = goAsync()
            receiverScope.launch {
                try {
                    val repository = SpotifyRepository(appContext)
                    when (action) {
                        ACTION_PLAY_PAUSE -> repository.togglePlayPause()
                        ACTION_NEXT -> repository.nextTrack()
                        ACTION_PREVIOUS -> repository.previousTrack()
                    }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                if (!WidgetStateCache.isWarmed) {
                    WidgetStateCache.ensureWarmed(appContext)
                }
                MediaNotificationListenerService.requestSyncNow()
                WidgetUpdater.pushWidgetUpdate(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                WidgetUpdater.pushSingleWidgetUpdate(appContext, appWidgetId, newOptions)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.schedulePeriodicSync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdater.cancelPeriodicSync(context)
        SpotifySyncService.stop(context)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.widget.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.widget.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.widget.ACTION_PREVIOUS"
        const val ACTION_OPEN_SPOTIFY = "com.example.widget.ACTION_OPEN_SPOTIFY"

        private val WIDGET_ACTIONS = setOf(
            ACTION_PLAY_PAUSE,
            ACTION_NEXT,
            ACTION_PREVIOUS,
            ACTION_OPEN_SPOTIFY,
        )

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
