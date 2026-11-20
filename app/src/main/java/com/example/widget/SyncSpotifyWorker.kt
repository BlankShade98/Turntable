package com.example.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.SpotifyRepository

class SyncSpotifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = SpotifyRepository(applicationContext)
        if (!repository.preferences.getAuth().isAuthenticated) {
            WidgetUpdater.pushWidgetUpdate(applicationContext)
            return Result.success()
        }
        val result = repository.refreshPlayback()
        return if (result.isSuccess) Result.success() else Result.retry()
    }
}
