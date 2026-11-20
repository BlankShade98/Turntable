package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.SpotifySyncService
import com.example.data.WidgetStateCache
import com.example.model.PlaybackTrack
import com.example.model.WidgetStyleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object WidgetUpdater {
    private const val PERIODIC_SYNC_WORK_NAME = "turntable_periodic_spotify_sync"
    private val renderMutex = Mutex()

    /**
     * Pushes a direct native RemoteViews update to all placed Turntable widgets (< 10ms)
     * reading from the hot in-memory WidgetStateCache with zero DataStore disk I/O.
     */
    suspend fun pushWidgetUpdate(
        context: Context,
        overrideTrack: PlaybackTrack? = null,
        overrideStyle: WidgetStyleConfig? = null,
        overrideConnected: Boolean? = null,
    ) = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        if (!WidgetStateCache.isWarmed) {
            WidgetStateCache.ensureWarmed(appContext)
        }
        renderMutex.withLock {
            val track = overrideTrack ?: WidgetStateCache.track
            val style = overrideStyle ?: WidgetStateCache.style
            val isConnected = overrideConnected ?: WidgetStateCache.isAuthenticated

            SpotifySyncService.updateNotificationIfRunning(appContext, track)

            val manager = AppWidgetManager.getInstance(appContext)
            val provider = ComponentName(appContext, TurntableWidgetReceiver::class.java)
            val appWidgetIds = runCatching {
                manager.getAppWidgetIds(provider)
            }.getOrDefault(IntArray(0))

            for (appWidgetId in appWidgetIds) {
                val options = runCatching {
                    manager.getAppWidgetOptions(appWidgetId)
                }.getOrNull()
                val views = TurntableWidgetRenderer.buildRemoteViews(
                    context = appContext,
                    appWidgetId = appWidgetId,
                    options = options,
                    track = track,
                    style = style,
                    isConnected = isConnected,
                )
                runCatching {
                    manager.updateAppWidget(appWidgetId, views)
                }
            }
        }
    }

    suspend fun pushSingleWidgetUpdate(
        context: Context,
        appWidgetId: Int,
        options: android.os.Bundle?,
    ) = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        if (!WidgetStateCache.isWarmed) {
            WidgetStateCache.ensureWarmed(appContext)
        }
        renderMutex.withLock {
            val track = WidgetStateCache.track
            val style = WidgetStateCache.style
            val isConnected = WidgetStateCache.isAuthenticated

            val views = TurntableWidgetRenderer.buildRemoteViews(
                context = appContext,
                appWidgetId = appWidgetId,
                options = options,
                track = track,
                style = style,
                isConnected = isConnected,
            )
            runCatching {
                AppWidgetManager.getInstance(appContext).updateAppWidget(appWidgetId, views)
            }
        }
    }

    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncSpotifyWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
    }
}
