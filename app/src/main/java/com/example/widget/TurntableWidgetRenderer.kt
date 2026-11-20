package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.LruCache
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AlbumArtCache
import com.example.data.AlbumColorExtractor
import com.example.model.DynamicAlbumPalette
import com.example.model.PlaybackTrack
import com.example.model.WidgetCornerStyle
import com.example.model.WidgetStyleConfig
import com.example.model.WidgetThemePreset
import kotlin.math.roundToInt

/**
 * Direct native RemoteViews builder for the Turntable home screen widget.
 * Updates push straight to AppWidgetManager in < 15ms without WorkManager or Glance SessionWorker lag.
 */
object TurntableWidgetRenderer {

    private val paletteCache = LruCache<String, DynamicAlbumPalette>(16)

    fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        options: Bundle?,
        track: PlaybackTrack,
        style: WidgetStyleConfig,
        isConnected: Boolean,
    ): RemoteViews {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0

        // Portrait home screen typically reports width in MIN_WIDTH and height in MAX_HEIGHT
        val widgetWidthDp = when {
            minW >= 200 -> minW.toFloat()
            maxW >= 200 -> maxW.toFloat()
            else -> 349f
        }
        val widgetHeightDp = when {
            maxH in 56..150 -> maxH.toFloat()
            minH in 56..150 -> minH.toFloat()
            else -> 98f
        }

        val isCompact = widgetWidthDp < 305f || widgetHeightDp < 84f
        val layoutRes = if (isCompact) {
            R.layout.widget_turntable_live_compact
        } else {
            R.layout.widget_turntable_live
        }

        val artSizeDp = (widgetHeightDp * 0.70f).coerceIn(48f, 76f)
        val equalArtMarginDp = ((widgetHeightDp - artSizeDp) / 2f).coerceAtLeast(8f)
        val artCornerDp = (style.cornerStyle.radiusDp - 10).coerceAtLeast(8).toFloat()

        val artSizePx = (artSizeDp * density).roundToInt()
        val equalMarginPx = (equalArtMarginDp * density).roundToInt()
        val artCornerPx = artCornerDp * density

        // Decode rounded album art (in-memory live bitmap while playing, idle cache file when stopped/paused)
        val liveArt = com.example.data.WidgetStateCache.liveArtBitmap
        val liveArtVersion = com.example.data.WidgetStateCache.liveArtVersion
        val artBitmap = AlbumArtCache.decodeRoundedWidgetArt(
            context = context,
            filePath = track.localArtPath,
            placeholderResId = R.drawable.img_vinyl_placeholder,
            sizePx = artSizePx,
            cornerRadiusPx = artCornerPx,
            liveBitmap = liveArt,
            liveBitmapVersion = liveArtVersion,
        )

        val resolvedStyle = if (style.themePreset == WidgetThemePreset.ALBUM_PASTEL && artBitmap != null) {
            val artFile = track.localArtPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            val paletteKey = when {
                liveArt != null -> "live|$liveArtVersion"
                artFile != null && artFile.exists() -> "${track.localArtPath}|${artFile.lastModified()}"
                else -> "placeholder"
            }
            val palette = paletteCache.get(paletteKey) ?: run {
                val extracted = AlbumColorExtractor.extractPastelPalette(artBitmap)
                paletteCache.put(paletteKey, extracted)
                extracted
            }
            style.copy(dynamicPalette = palette)
        } else {
            style
        }

        val colors = resolvedStyle.resolvedColors()

        val displayTitle = if (track.hasTrack) track.title else "No song playing"
        val subtitleText = when {
            track.hasTrack -> buildString {
                append(track.artist.ifBlank { "Unknown artist" })
                if (resolvedStyle.showAlbumSubtitle && track.album.isNotBlank()) {
                    append(" • ")
                    append(track.album)
                }
            }
            isConnected -> "Play a song in Spotify"
            else -> "Play music in Spotify"
        }

        val controlsWidthDp = if (isCompact) 128f else 150f
        val textStartGapDp = if (isCompact) 10f else 12f
        val availableTextWidthDp = (
            widgetWidthDp - (equalArtMarginDp * 2f) - artSizeDp - textStartGapDp - 6f - controlsWidthDp
            ).coerceAtLeast(56f)

        val titleBitmap = WidgetTextBitmapRenderer.renderSingleLine(
            context = context,
            text = displayTitle,
            textSizeSp = 13.5f,
            colorArgb = colors.primaryTextArgb,
            maxWidthDp = availableTextWidthDp,
            fontWeight = 700,
        )
        val artistBitmap = WidgetTextBitmapRenderer.renderSingleLine(
            context = context,
            text = subtitleText,
            textSizeSp = 11f,
            colorArgb = colors.secondaryTextArgb,
            maxWidthDp = availableTextWidthDp,
            fontWeight = 400,
        )
        val spotifyLabelBitmap = WidgetTextBitmapRenderer.renderSingleLine(
            context = context,
            text = "Spotify",
            textSizeSp = if (isCompact) 11f else 11.5f,
            colorArgb = colors.playIconArgb,
            maxWidthDp = 64f,
            fontWeight = 700,
        )

        val views = RemoteViews(context.packageName, layoutRes)

        // Background rounded shape & dynamic Material 3 pastel tint
        val bgShapeRes = when (resolvedStyle.cornerStyle) {
            WidgetCornerStyle.STUDIO -> R.drawable.bg_widget_corner_18
            WidgetCornerStyle.SOFT -> R.drawable.bg_widget_corner_24
            WidgetCornerStyle.PILL -> R.drawable.bg_widget_corner_30
        }
        views.setImageViewResource(R.id.widget_bg, bgShapeRes)
        views.setInt(R.id.widget_bg, "setColorFilter", colors.backgroundArgb)

        // Equal left, top, and bottom margins around album art + top-aligned track text
        views.setViewPadding(R.id.widget_content_row, equalMarginPx, 0, equalMarginPx, 0)
        views.setViewPadding(
            R.id.widget_text_col,
            (textStartGapDp * density).roundToInt(),
            equalMarginPx,
            (6f * density).roundToInt(),
            0,
        )

        // Album art
        if (artBitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, artBitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.drawable.img_vinyl_placeholder)
        }

        // Google Sans title & artist
        if (titleBitmap != null) {
            views.setImageViewBitmap(R.id.widget_title_image, titleBitmap.bitmap)
            views.setContentDescription(R.id.widget_title_image, displayTitle)
        }
        if (artistBitmap != null) {
            views.setImageViewBitmap(R.id.widget_artist_image, artistBitmap.bitmap)
            views.setContentDescription(R.id.widget_artist_image, subtitleText)
        }

        // Top-right "Spotify" pill (darker same-hue album color, opens config app)
        views.setImageViewResource(R.id.widget_spotify_pill_bg, R.drawable.bg_widget_btn_pill)
        views.setInt(R.id.widget_spotify_pill_bg, "setColorFilter", colors.playButtonArgb)
        views.setInt(R.id.widget_spotify_icon, "setColorFilter", colors.playIconArgb)
        if (spotifyLabelBitmap != null) {
            views.setImageViewBitmap(R.id.widget_spotify_label, spotifyLabelBitmap.bitmap)
        }

        // Previous & Next borderless icons
        views.setInt(R.id.widget_icon_prev, "setColorFilter", colors.primaryTextArgb)
        views.setInt(R.id.widget_icon_next, "setColorFilter", colors.primaryTextArgb)

        // Play/Pause morphing shape (pill when paused, squircle when playing) + icon
        views.setImageViewResource(
            R.id.widget_play_bg,
            if (track.isPlaying) R.drawable.bg_widget_btn_squircle else R.drawable.bg_widget_btn_pill,
        )
        views.setInt(R.id.widget_play_bg, "setColorFilter", colors.playButtonArgb)
        views.setImageViewResource(
            R.id.widget_icon_play_pause,
            if (track.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setInt(R.id.widget_icon_play_pause, "setColorFilter", colors.playIconArgb)
        views.setContentDescription(
            R.id.widget_icon_play_pause,
            if (track.isPlaying) "Pause" else "Play",
        )

        // Click handlers:
        // 1. Tap anywhere on widget background -> open Spotify directly (BAL-safe on Android 14-16)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            openSpotifyIntent(context, 100 + appWidgetId),
        )
        // 2. Tap top-right "Spotify" pill -> open Turntable config app directly
        views.setOnClickPendingIntent(
            R.id.widget_spotify_pill,
            openConfigAppIntent(context, 200 + appWidgetId),
        )
        // 3. Transport controls
        views.setOnClickPendingIntent(
            R.id.widget_btn_prev,
            broadcastActionIntent(context, TurntableWidgetReceiver.ACTION_PREVIOUS, 300 + appWidgetId),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_next,
            broadcastActionIntent(context, TurntableWidgetReceiver.ACTION_NEXT, 400 + appWidgetId),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_play_pause,
            broadcastActionIntent(context, TurntableWidgetReceiver.ACTION_PLAY_PAUSE, 500 + appWidgetId),
        )

        return views
    }

    private fun openSpotifyIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage("com.spotify.music")
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openConfigAppIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcastActionIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, TurntableWidgetReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
