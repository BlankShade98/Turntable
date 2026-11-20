package com.example.data

import android.content.Context
import android.graphics.Bitmap
import com.example.model.DynamicAlbumPalette
import com.example.model.PlaybackTrack
import com.example.model.WidgetStyleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hot in-memory state holder for live Spotify playback and artwork.
 * While Spotify is playing, track metadata and album art live strictly in memory here
 * so disk cache stays completely clean; when Spotify stops/pauses, the last track
 * (album art, song name, artist name) is persisted to disk cache and restored on cold start.
 */
object WidgetStateCache {
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artVersionCounter = AtomicLong(0L)

    @Volatile
    var isWarmed: Boolean = false
        private set

    private val _trackFlow = MutableStateFlow(PlaybackTrack())
    val trackFlow: StateFlow<PlaybackTrack> = _trackFlow.asStateFlow()

    var track: PlaybackTrack
        get() = _trackFlow.value
        set(value) {
            _trackFlow.value = value
        }

    private val _styleFlow = MutableStateFlow(WidgetStyleConfig())
    val styleFlow: StateFlow<WidgetStyleConfig> = _styleFlow.asStateFlow()

    var style: WidgetStyleConfig
        get() = _styleFlow.value
        set(value) {
            _styleFlow.value = value
        }

    private val _liveArtBitmapFlow = MutableStateFlow<Bitmap?>(null)
    val liveArtBitmapFlow: StateFlow<Bitmap?> = _liveArtBitmapFlow.asStateFlow()

    val liveArtBitmap: Bitmap?
        get() = _liveArtBitmapFlow.value?.takeIf { !it.isRecycled }

    @Volatile
    var liveArtVersion: Long = 0L
        private set

    @Volatile
    var isAuthenticated: Boolean = false

    @Volatile
    var liveSyncEnabled: Boolean = true

    fun setLiveArtBitmap(source: Bitmap?) {
        if (source == null || source.isRecycled || source.width < 32 || source.height < 32) {
            if (_liveArtBitmapFlow.value != null) {
                liveArtVersion = artVersionCounter.incrementAndGet()
                _liveArtBitmapFlow.value = null
            }
            return
        }

        val maxSide = max(source.width, source.height)
        val boundedCopy = runCatching {
            if (maxSide > 320) {
                val ratio = 320f / maxSide.toFloat()
                val dstW = (source.width * ratio).roundToInt().coerceAtLeast(32)
                val dstH = (source.height * ratio).roundToInt().coerceAtLeast(32)
                val scaled = Bitmap.createScaledBitmap(source, dstW, dstH, true)
                if (scaled.config != Bitmap.Config.ARGB_8888) {
                    val converted = scaled.copy(Bitmap.Config.ARGB_8888, false)
                    if (scaled !== source) scaled.recycle()
                    converted
                } else {
                    scaled
                }
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
        }.getOrNull() ?: return

        liveArtVersion = artVersionCounter.incrementAndGet()
        _liveArtBitmapFlow.value = boundedCopy
    }

    fun ensureLiveArtLoadedFromFile(filePath: String, context: Context) {
        if (liveArtBitmap != null) return
        if (filePath.isBlank()) return
        val file = File(filePath)
        if (!AlbumArtCache.isValidImageFile(file)) return
        val decoded = AlbumArtCache.decodeDownsampledWidgetBitmap(
            filePath = filePath,
            context = context,
            targetSizeDp = 160,
        ) ?: return
        setLiveArtBitmap(decoded)
        if (!decoded.isRecycled) {
            decoded.recycle()
        }
    }

    fun warmAsync(context: Context) {
        if (isWarmed) return
        val appContext = context.applicationContext
        warmScope.launch {
            ensureWarmed(appContext)
        }
    }

    suspend fun ensureWarmed(context: Context) {
        if (isWarmed) return
        val appContext = context.applicationContext
        val prefs = TurntablePreferences(appContext)
        val diskTrack = prefs.getTrack()
        if (track.lastUpdatedMs == 0L || diskTrack.lastUpdatedMs > track.lastUpdatedMs) {
            track = diskTrack
            if (diskTrack.localArtPath.isNotBlank() && liveArtBitmap == null) {
                ensureLiveArtLoadedFromFile(diskTrack.localArtPath, appContext)
            }
        }
        style = prefs.getStyle()
        isAuthenticated = prefs.getAuth().isAuthenticated
        liveSyncEnabled = prefs.isLiveSyncEnabled()
        isWarmed = true
    }

    fun updatePalette(palette: DynamicAlbumPalette) {
        style = style.copy(dynamicPalette = palette)
    }
}
