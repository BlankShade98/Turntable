package com.example

import android.app.Application
import androidx.core.content.ContextCompat
import com.example.data.SpotifyBroadcastReceiver
import com.example.data.WidgetStateCache

class TurntableApplication : Application() {

    private val spotifyBroadcastReceiver = SpotifyBroadcastReceiver()

    override fun onCreate() {
        super.onCreate()
        // Pre-warm hot in-memory state so the very first broadcast or widget tap has zero disk latency
        WidgetStateCache.warmAsync(this)

        // Single process-wide dynamic receiver for Spotify Device Broadcast Status
        runCatching {
            ContextCompat.registerReceiver(
                this,
                spotifyBroadcastReceiver,
                SpotifyBroadcastReceiver.createIntentFilter(),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }
}
