package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listens to Spotify for Android's "Device Broadcast Status" broadcasts:
 * - com.spotify.music.metadatachanged
 * - com.spotify.music.playbackstatechanged
 * - com.spotify.music.queuechanged
 *
 * Immediately pushes track title, artist, play/pause state, and album art to the widget.
 */
class SpotifyBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val signature = buildSignature(action, intent)
        if (isDuplicateBroadcast(signature)) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                withTimeoutOrNull(8_500L) {
                    val repository = SpotifyRepository(appContext)
                    when (action) {
                        ACTION_METADATA_CHANGED -> {
                            val rawId = intent.getStringExtra("id").orEmpty()
                            val artist = intent.getStringExtra("artist").orEmpty()
                            val album = intent.getStringExtra("album").orEmpty()
                            val trackName = intent.getStringExtra("track").orEmpty()
                            val lengthInt = intent.getIntExtra("length", -1)
                            val lengthMs = if (lengthInt > 0) {
                                lengthInt.toLong()
                            } else {
                                intent.getLongExtra("length", 0L).coerceAtLeast(0L)
                            }
                            val playingOverride = if (intent.hasExtra("playing")) {
                                intent.getBooleanExtra("playing", true)
                            } else {
                                null
                            }
                            if (trackName.isNotBlank() || rawId.isNotBlank()) {
                                repository.handleSpotifyBroadcastMetadata(
                                    rawTrackId = rawId,
                                    trackName = trackName,
                                    artist = artist,
                                    album = album,
                                    durationMs = lengthMs,
                                    playingOverride = playingOverride,
                                )
                            }
                        }

                        ACTION_PLAYBACK_STATE_CHANGED -> {
                            val isPlaying = intent.getBooleanExtra("playing", false)
                            val posInt = intent.getIntExtra("playbackPosition", -1)
                            val posMs = if (posInt >= 0) {
                                posInt.toLong()
                            } else {
                                intent.getLongExtra("playbackPosition", -1L).takeIf { it >= 0L }
                            }
                            repository.handleSpotifyBroadcastPlaybackState(
                                isPlaying = isPlaying,
                                progressMs = posMs,
                            )
                        }

                        ACTION_QUEUE_CHANGED -> {
                            if (!WidgetStateCache.isWarmed) {
                                WidgetStateCache.ensureWarmed(appContext)
                            }
                            if (WidgetStateCache.isAuthenticated) {
                                repository.refreshPlayback()
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildSignature(action: String, intent: Intent): String {
        return when (action) {
            ACTION_METADATA_CHANGED -> {
                val id = intent.getStringExtra("id").orEmpty()
                val track = intent.getStringExtra("track").orEmpty()
                val artist = intent.getStringExtra("artist").orEmpty()
                val sent = intent.getLongExtra("timeSent", 0L)
                "$action|$id|$track|$artist|$sent"
            }
            ACTION_PLAYBACK_STATE_CHANGED -> {
                val playing = intent.getBooleanExtra("playing", false)
                val pos = intent.getIntExtra("playbackPosition", -1)
                val sent = intent.getLongExtra("timeSent", 0L)
                "$action|$playing|$pos|$sent"
            }
            else -> "$action|${intent.getLongExtra("timeSent", 0L)}"
        }
    }

    companion object {
        const val ACTION_METADATA_CHANGED = "com.spotify.music.metadatachanged"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"
        const val ACTION_QUEUE_CHANGED = "com.spotify.music.queuechanged"

        private val SUPPORTED_ACTIONS = setOf(
            ACTION_METADATA_CHANGED,
            ACTION_PLAYBACK_STATE_CHANGED,
            ACTION_QUEUE_CHANGED,
        )

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val dedupLock = Any()
        private val recentSignatures = LinkedHashMap<String, Long>(16, 0.75f, true)

        private fun isDuplicateBroadcast(signature: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            synchronized(dedupLock) {
                val previous = recentSignatures[signature]
                if (previous != null && (now - previous) < 500L) {
                    return true
                }
                recentSignatures[signature] = now
                if (recentSignatures.size > 16) {
                    val oldestKey = recentSignatures.keys.firstOrNull()
                    if (oldestKey != null) recentSignatures.remove(oldestKey)
                }
                return false
            }
        }

        fun createIntentFilter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_METADATA_CHANGED)
            addAction(ACTION_PLAYBACK_STATE_CHANGED)
            addAction(ACTION_QUEUE_CHANGED)
        }
    }
}
