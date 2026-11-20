package com.example.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent

object MediaKeyBridge {
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private val SPOTIFY_PACKAGES = listOf("com.spotify.music", "com.spotify.lite")

    /**
     * Dispatches a media key event strictly to Spotify's MediaButtonReceiver so tapping
     * widget buttons never controls YouTube, Chrome, Podcasts, or any other media app.
     */
    fun dispatchMediaKey(context: Context, keyCode: Int): Boolean {
        val receiverComponent = resolveSpotifyMediaButtonReceiver(context)
        if (receiverComponent != null) {
            val eventTime = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = receiverComponent
                putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = receiverComponent
                putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
            }
            return runCatching {
                context.sendBroadcast(downIntent)
                context.sendBroadcast(upIntent)
                true
            }.getOrDefault(false)
        }

        // If Spotify has no resolvable MediaButtonReceiver, never hijack another media app
        // via global AudioManager media keys; open Spotify instead.
        openSpotify(context)
        return false
    }

    fun playPause(context: Context): Boolean {
        return dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun next(context: Context): Boolean {
        return dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(context: Context): Boolean {
        return dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun isSpotifyInstalled(context: Context): Boolean {
        return SPOTIFY_PACKAGES.any { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }
    }

    fun openSpotify(context: Context): Boolean {
        val launchIntent = SPOTIFY_PACKAGES.firstNotNullOfOrNull { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)
        }
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } else {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://open.spotify.com/")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(webIntent) }.isSuccess
        }
    }

    private fun resolveSpotifyMediaButtonReceiver(context: Context): ComponentName? {
        val pm = context.packageManager
        for (pkg in SPOTIFY_PACKAGES) {
            val queryIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
            val receivers = runCatching {
                pm.queryBroadcastReceivers(queryIntent, 0)
            }.getOrNull().orEmpty()
            val activityInfo = receivers.firstOrNull()?.activityInfo
            if (activityInfo != null) {
                return ComponentName(activityInfo.packageName, activityInfo.name)
            }
        }
        return null
    }
}
