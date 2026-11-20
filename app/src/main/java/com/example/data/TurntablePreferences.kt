package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DynamicAlbumPalette
import com.example.model.PlaybackTrack
import com.example.model.WidgetCornerStyle
import com.example.model.WidgetLayoutDensity
import com.example.model.WidgetStyleConfig
import com.example.model.WidgetThemePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.turntableDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "turntable_store"
)

data class StoredSpotifyAuth(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMs: Long = 0L,
    val displayName: String = "",
    val pkceVerifier: String = "",
    val oauthState: String = "",
) {
    val isAuthenticated: Boolean
        get() = refreshToken.isNotBlank() || accessToken.isNotBlank()
}

class TurntablePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.turntableDataStore

    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        val EXPIRES_AT_MS = longPreferencesKey("spotify_expires_at_ms")
        val USER_DISPLAY_NAME = stringPreferencesKey("spotify_user_display_name")
        val PKCE_VERIFIER = stringPreferencesKey("spotify_pkce_verifier")
        val OAUTH_STATE = stringPreferencesKey("spotify_oauth_state")

        val TRACK_ID = stringPreferencesKey("track_id")
        val TRACK_TITLE = stringPreferencesKey("track_title")
        val TRACK_ARTIST = stringPreferencesKey("track_artist")
        val TRACK_ALBUM = stringPreferencesKey("track_album")
        val TRACK_ART_URL = stringPreferencesKey("track_art_url")
        val TRACK_LOCAL_ART_PATH = stringPreferencesKey("track_local_art_path")
        val TRACK_DURATION_MS = longPreferencesKey("track_duration_ms")
        val TRACK_PROGRESS_MS = longPreferencesKey("track_progress_ms")
        val TRACK_IS_PLAYING = booleanPreferencesKey("track_is_playing")
        val TRACK_DEVICE_NAME = stringPreferencesKey("track_device_name")
        val TRACK_UPDATED_MS = longPreferencesKey("track_updated_ms")
        val WIDGET_REVISION = intPreferencesKey("widget_revision")

        val STYLE_THEME = stringPreferencesKey("style_theme")
        val STYLE_DENSITY = stringPreferencesKey("style_density")
        val STYLE_CORNER = stringPreferencesKey("style_corner")
        val STYLE_SHOW_ALBUM = booleanPreferencesKey("style_show_album")
        val STYLE_SHOW_DEVICE = booleanPreferencesKey("style_show_device")

        val DYN_BG = intPreferencesKey("dyn_bg_argb")
        val DYN_ELEVATED = intPreferencesKey("dyn_elevated_argb")
        val DYN_PLAY_BTN = intPreferencesKey("dyn_play_btn_argb")
        val DYN_PLAY_ICON = intPreferencesKey("dyn_play_icon_argb")
        val DYN_PRIMARY_TEXT = intPreferencesKey("dyn_primary_text_argb")
        val DYN_SECONDARY_TEXT = intPreferencesKey("dyn_secondary_text_argb")
        val LIVE_SYNC_ENABLED = booleanPreferencesKey("live_sync_enabled")
    }

    val authFlow: Flow<StoredSpotifyAuth> = dataStore.data.map { prefs ->
        StoredSpotifyAuth(
            accessToken = prefs[Keys.ACCESS_TOKEN].orEmpty(),
            refreshToken = prefs[Keys.REFRESH_TOKEN].orEmpty(),
            expiresAtEpochMs = prefs[Keys.EXPIRES_AT_MS] ?: 0L,
            displayName = prefs[Keys.USER_DISPLAY_NAME].orEmpty(),
            pkceVerifier = prefs[Keys.PKCE_VERIFIER].orEmpty(),
            oauthState = prefs[Keys.OAUTH_STATE].orEmpty(),
        )
    }

    val trackFlow: Flow<PlaybackTrack> = dataStore.data.map { prefs ->
        PlaybackTrack(
            trackId = prefs[Keys.TRACK_ID].orEmpty(),
            title = prefs[Keys.TRACK_TITLE].orEmpty(),
            artist = prefs[Keys.TRACK_ARTIST].orEmpty(),
            album = prefs[Keys.TRACK_ALBUM].orEmpty(),
            albumArtUrl = prefs[Keys.TRACK_ART_URL].orEmpty(),
            localArtPath = prefs[Keys.TRACK_LOCAL_ART_PATH].orEmpty(),
            durationMs = prefs[Keys.TRACK_DURATION_MS] ?: 0L,
            progressMs = prefs[Keys.TRACK_PROGRESS_MS] ?: 0L,
            isPlaying = prefs[Keys.TRACK_IS_PLAYING] ?: false,
            deviceName = prefs[Keys.TRACK_DEVICE_NAME].orEmpty(),
            lastUpdatedMs = prefs[Keys.TRACK_UPDATED_MS] ?: 0L,
        )
    }

    val widgetStyleFlow: Flow<WidgetStyleConfig> = dataStore.data.map { prefs ->
        val defaultPalette = DynamicAlbumPalette()
        val palette = DynamicAlbumPalette(
            backgroundArgb = prefs[Keys.DYN_BG] ?: defaultPalette.backgroundArgb,
            elevatedArgb = prefs[Keys.DYN_ELEVATED] ?: defaultPalette.elevatedArgb,
            playButtonArgb = prefs[Keys.DYN_PLAY_BTN] ?: defaultPalette.playButtonArgb,
            playIconArgb = prefs[Keys.DYN_PLAY_ICON] ?: defaultPalette.playIconArgb,
            primaryTextArgb = prefs[Keys.DYN_PRIMARY_TEXT] ?: defaultPalette.primaryTextArgb,
            secondaryTextArgb = prefs[Keys.DYN_SECONDARY_TEXT] ?: defaultPalette.secondaryTextArgb,
        )
        WidgetStyleConfig(
            themePreset = WidgetThemePreset.fromId(prefs[Keys.STYLE_THEME]),
            layoutDensity = WidgetLayoutDensity.fromId(prefs[Keys.STYLE_DENSITY]),
            cornerStyle = WidgetCornerStyle.fromId(prefs[Keys.STYLE_CORNER]),
            showAlbumSubtitle = prefs[Keys.STYLE_SHOW_ALBUM] ?: true,
            showDeviceBadge = prefs[Keys.STYLE_SHOW_DEVICE] ?: true,
            dynamicPalette = palette,
        )
    }

    val liveSyncEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LIVE_SYNC_ENABLED] ?: true
    }

    suspend fun getAuth(): StoredSpotifyAuth = authFlow.first()
    suspend fun getTrack(): PlaybackTrack = trackFlow.first()
    suspend fun getStyle(): WidgetStyleConfig = widgetStyleFlow.first()
    suspend fun isLiveSyncEnabled(): Boolean = liveSyncEnabledFlow.first()

    suspend fun setLiveSyncEnabled(enabled: Boolean) {
        WidgetStateCache.liveSyncEnabled = enabled
        dataStore.edit { prefs ->
            prefs[Keys.LIVE_SYNC_ENABLED] = enabled
        }
    }

    suspend fun savePkceChallenge(verifier: String, state: String) {
        dataStore.edit { prefs ->
            prefs[Keys.PKCE_VERIFIER] = verifier
            prefs[Keys.OAUTH_STATE] = state
        }
    }

    suspend fun saveAuthTokens(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
        displayName: String? = null,
    ) {
        WidgetStateCache.isAuthenticated = refreshToken.isNotBlank() || accessToken.isNotBlank()
        dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            if (refreshToken.isNotBlank()) {
                prefs[Keys.REFRESH_TOKEN] = refreshToken
            }
            prefs[Keys.EXPIRES_AT_MS] = expiresAtEpochMs
            if (!displayName.isNullOrBlank()) {
                prefs[Keys.USER_DISPLAY_NAME] = displayName
            }
            prefs.remove(Keys.PKCE_VERIFIER)
            prefs.remove(Keys.OAUTH_STATE)
        }
    }

    suspend fun clearAuth() {
        WidgetStateCache.isAuthenticated = false
        WidgetStateCache.track = PlaybackTrack()
        dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.EXPIRES_AT_MS)
            prefs.remove(Keys.USER_DISPLAY_NAME)
            prefs.remove(Keys.PKCE_VERIFIER)
            prefs.remove(Keys.OAUTH_STATE)
            prefs.remove(Keys.TRACK_ID)
            prefs.remove(Keys.TRACK_TITLE)
            prefs.remove(Keys.TRACK_ARTIST)
            prefs.remove(Keys.TRACK_ALBUM)
            prefs.remove(Keys.TRACK_ART_URL)
            prefs.remove(Keys.TRACK_LOCAL_ART_PATH)
            prefs.remove(Keys.TRACK_DURATION_MS)
            prefs.remove(Keys.TRACK_PROGRESS_MS)
            prefs[Keys.TRACK_IS_PLAYING] = false
            prefs.remove(Keys.TRACK_DEVICE_NAME)
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }

    suspend fun saveTrack(track: PlaybackTrack) {
        val current = WidgetStateCache.track
        val isNewerOrHasFreshArt = track.lastUpdatedMs == 0L ||
            track.lastUpdatedMs >= current.lastUpdatedMs ||
            (track.trackId == current.trackId && track.localArtPath.isNotBlank() && track.localArtPath != current.localArtPath)
        if (isNewerOrHasFreshArt) {
            WidgetStateCache.track = track
        }
        dataStore.edit { prefs ->
            val existingUpdated = prefs[Keys.TRACK_UPDATED_MS] ?: 0L
            val existingTrackId = prefs[Keys.TRACK_ID].orEmpty()
            val existingArt = prefs[Keys.TRACK_LOCAL_ART_PATH].orEmpty()
            val updatingSameTrackArt = track.trackId == existingTrackId &&
                track.localArtPath.isNotBlank() &&
                track.localArtPath != existingArt
            if (!updatingSameTrackArt && track.lastUpdatedMs != 0L && track.lastUpdatedMs < existingUpdated) {
                return@edit
            }
            prefs[Keys.TRACK_ID] = track.trackId
            prefs[Keys.TRACK_TITLE] = track.title
            prefs[Keys.TRACK_ARTIST] = track.artist
            prefs[Keys.TRACK_ALBUM] = track.album
            prefs[Keys.TRACK_ART_URL] = track.albumArtUrl
            prefs[Keys.TRACK_LOCAL_ART_PATH] = track.localArtPath
            prefs[Keys.TRACK_DURATION_MS] = track.durationMs
            prefs[Keys.TRACK_PROGRESS_MS] = track.progressMs
            prefs[Keys.TRACK_IS_PLAYING] = track.isPlaying
            prefs[Keys.TRACK_DEVICE_NAME] = track.deviceName
            prefs[Keys.TRACK_UPDATED_MS] = maxOf(track.lastUpdatedMs, existingUpdated)
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }

    /**
     * Cleans cached idle track metadata (song name, artist name, album art path) from disk
     * when Spotify media is actively playing in memory.
     */
    suspend fun clearCachedTrack() {
        dataStore.edit { prefs ->
            val hadCachedTrack = !prefs[Keys.TRACK_TITLE].isNullOrBlank() ||
                !prefs[Keys.TRACK_ARTIST].isNullOrBlank() ||
                !prefs[Keys.TRACK_LOCAL_ART_PATH].isNullOrBlank()
            if (!hadCachedTrack) return@edit
            prefs.remove(Keys.TRACK_ID)
            prefs.remove(Keys.TRACK_TITLE)
            prefs.remove(Keys.TRACK_ARTIST)
            prefs.remove(Keys.TRACK_ALBUM)
            prefs.remove(Keys.TRACK_ART_URL)
            prefs.remove(Keys.TRACK_LOCAL_ART_PATH)
            prefs.remove(Keys.TRACK_DURATION_MS)
            prefs.remove(Keys.TRACK_PROGRESS_MS)
            prefs[Keys.TRACK_IS_PLAYING] = false
            prefs.remove(Keys.TRACK_DEVICE_NAME)
            prefs.remove(Keys.TRACK_UPDATED_MS)
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }

    suspend fun updateIsPlaying(isPlaying: Boolean) {
        WidgetStateCache.track = WidgetStateCache.track.copy(isPlaying = isPlaying)
        dataStore.edit { prefs ->
            prefs[Keys.TRACK_IS_PLAYING] = isPlaying
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }

    suspend fun updatePlaybackState(isPlaying: Boolean, progressMs: Long?) {
        val current = WidgetStateCache.track
        WidgetStateCache.track = current.copy(
            isPlaying = isPlaying,
            progressMs = if (progressMs != null && progressMs >= 0L) progressMs else current.progressMs,
        )
        dataStore.edit { prefs ->
            prefs[Keys.TRACK_IS_PLAYING] = isPlaying
            if (progressMs != null && progressMs >= 0L) {
                prefs[Keys.TRACK_PROGRESS_MS] = progressMs
            }
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }

    suspend fun updateWidgetStyle(style: WidgetStyleConfig) {
        WidgetStateCache.style = style.copy(dynamicPalette = WidgetStateCache.style.dynamicPalette)
        dataStore.edit { prefs ->
            prefs[Keys.STYLE_THEME] = style.themePreset.id
            prefs[Keys.STYLE_DENSITY] = style.layoutDensity.id
            prefs[Keys.STYLE_CORNER] = style.cornerStyle.id
            prefs[Keys.STYLE_SHOW_ALBUM] = style.showAlbumSubtitle
            prefs[Keys.STYLE_SHOW_DEVICE] = style.showDeviceBadge
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }

    suspend fun saveDynamicPalette(palette: DynamicAlbumPalette) {
        WidgetStateCache.updatePalette(palette)
        dataStore.edit { prefs ->
            prefs[Keys.DYN_BG] = palette.backgroundArgb
            prefs[Keys.DYN_ELEVATED] = palette.elevatedArgb
            prefs[Keys.DYN_PLAY_BTN] = palette.playButtonArgb
            prefs[Keys.DYN_PLAY_ICON] = palette.playIconArgb
            prefs[Keys.DYN_PRIMARY_TEXT] = palette.primaryTextArgb
            prefs[Keys.DYN_SECONDARY_TEXT] = palette.secondaryTextArgb
            prefs[Keys.WIDGET_REVISION] = (prefs[Keys.WIDGET_REVISION] ?: 0) + 1
        }
    }
}
