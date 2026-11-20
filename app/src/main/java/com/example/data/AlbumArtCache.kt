package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages album art decoding and idle disk caching.
 * - While Spotify media IS playing: artwork stays strictly in memory (WidgetStateCache.liveArtBitmap)
 *   and the idle disk cache is cleaned.
 * - When NO Spotify media is playing: ONLY the last track's album art is stored in
 *   `cacheDir/spotify_last_idle_art.jpg` so the widget retains it across restarts/reboots.
 */
object AlbumArtCache {
    private const val ART_PREFIX = "spotify_art_"
    private const val TRACK_PREFIX = "spotify_track_"
    private const val LEGACY_FILENAME = "spotify_album_art_cache.jpg"
    private const val LAST_IDLE_ART_FILENAME = "spotify_last_idle_art.jpg"

    private val roundedArtCache = LruCache<String, Bitmap>(12)

    /**
     * Verifies that a cached file exists and decodes as a real image (>= 32x32).
     * Automatically deletes corrupt/non-image files so they never poison future lookups.
     */
    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || !file.canRead() || file.length() < 64L) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val valid = opts.outWidth >= 32 && opts.outHeight >= 32
        if (!valid) {
            runCatching { file.delete() }
        }
        return valid
    }

    /**
     * Saves ONLY the last track's album art into app cacheDir when no Spotify media is playing.
     * Removes any stale/legacy cached artwork files so disk usage stays minimal (1 file).
     */
    suspend fun saveLastIdleArt(
        context: Context,
        bitmap: Bitmap,
    ): String = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) return@withContext ""
        val cacheDir = context.cacheDir
        val targetFile = File(cacheDir, LAST_IDLE_ART_FILENAME)

        val maxSide = max(bitmap.width, bitmap.height)
        val scaled = if (maxSide > 320) {
            val ratio = 320f / maxSide.toFloat()
            val dstW = (bitmap.width * ratio).roundToInt().coerceAtLeast(32)
            val dstH = (bitmap.height * ratio).roundToInt().coerceAtLeast(32)
            Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        } else {
            bitmap
        }

        val jpegBytes = try {
            val out = ByteArrayOutputStream()
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
            if (ok && out.size() >= 64) out.toByteArray() else null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        } ?: return@withContext ""

        val existingBytes = if (targetFile.exists() && targetFile.length() == jpegBytes.size.toLong()) {
            runCatching { targetFile.readBytes() }.getOrNull()
        } else {
            null
        }

        if (existingBytes == null || !existingBytes.contentEquals(jpegBytes)) {
            val tempFile = File(cacheDir, "${targetFile.name}.${System.nanoTime()}.tmp")
            tempFile.writeBytes(jpegBytes)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeBytes(jpegBytes)
                tempFile.delete()
            }
            targetFile.setLastModified(System.currentTimeMillis())
        }

        deleteArtworkFilesInDir(context.filesDir, keepName = null)
        deleteArtworkFilesInDir(cacheDir, keepName = LAST_IDLE_ART_FILENAME)
        targetFile.absolutePath
    }

    /**
     * Cleans the cached idle album art (and any legacy artwork files) from disk
     * whenever Spotify media is actively playing.
     */
    fun clearIdleCache(context: Context) {
        deleteArtworkFilesInDir(context.cacheDir, keepName = null)
        deleteArtworkFilesInDir(context.filesDir, keepName = null)
    }

    fun clearCache(context: Context) {
        roundedArtCache.evictAll()
        clearIdleCache(context)
    }

    /**
     * Downloads and decodes an artwork URL straight into a bounded in-memory Bitmap (<= 320px)
     * without writing to disk cache.
     */
    suspend fun downloadBitmap(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        runCatching {
            val request = Request.Builder().url(imageUrl).build()
            SpotifyNetworkClient.fastImageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val rawBytes = response.body?.bytes() ?: return@withContext null
                if (rawBytes.size < 64) return@withContext null
                decodeBoundedBitmap(rawBytes)
            }
        }.getOrNull()
    }

    /**
     * Loads and decodes a `content://` or `file://` artwork URI straight into a bounded
     * in-memory Bitmap (<= 320px) without writing to disk cache.
     */
    suspend fun loadBitmapFromContentUri(
        context: Context,
        uriString: String,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        if (bytes.size < 64) return@withContext null
        decodeBoundedBitmap(bytes)
    }

    private fun decodeBoundedBitmap(rawBytes: ByteArray): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        if (boundsOpts.outWidth < 32 || boundsOpts.outHeight < 32) return null

        var inSampleSize = 1
        while (
            (boundsOpts.outWidth / inSampleSize) > 640 &&
            (boundsOpts.outHeight / inSampleSize) > 640
        ) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
        if (decoded.width < 32 || decoded.height < 32) {
            decoded.recycle()
            return null
        }

        val maxSide = max(decoded.width, decoded.height)
        return if (maxSide > 320) {
            val ratio = 320f / maxSide.toFloat()
            val dstW = (decoded.width * ratio).roundToInt().coerceAtLeast(32)
            val dstH = (decoded.height * ratio).roundToInt().coerceAtLeast(32)
            val scaled = Bitmap.createScaledBitmap(decoded, dstW, dstH, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }
    }

    private fun deleteArtworkFilesInDir(dir: File?, keepName: String?) {
        dir?.listFiles()?.forEach { file ->
            val name = file.name
            val isArtFile = name == LAST_IDLE_ART_FILENAME ||
                name == LEGACY_FILENAME ||
                name.startsWith(ART_PREFIX) ||
                name.startsWith(TRACK_PREFIX)
            if (isArtFile && name != keepName) {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * Decodes a rounded square bitmap sized specifically for the widget's 70% height art slot,
     * preferring live in-memory artwork when playing, then idle disk cache when paused/stopped,
     * and finally the vinyl placeholder.
     */
    fun decodeRoundedWidgetArt(
        context: Context,
        filePath: String,
        placeholderResId: Int,
        sizePx: Int,
        cornerRadiusPx: Float,
        liveBitmap: Bitmap? = null,
        liveBitmapVersion: Long = 0L,
    ): Bitmap? {
        val safeSize = sizePx.coerceIn(72, 320)
        val hasLiveBitmap = liveBitmap != null && !liveBitmap.isRecycled && liveBitmap.width >= 32 && liveBitmap.height >= 32

        val file = if (!hasLiveBitmap) filePath.takeIf { it.isNotBlank() }?.let(::File) else null
        val hasExistingFile = file != null && file.exists() && file.length() >= 64L

        val realCacheKey = when {
            hasLiveBitmap -> "live|$liveBitmapVersion|$safeSize|${cornerRadiusPx.roundToInt()}"
            hasExistingFile -> "$filePath|${file!!.lastModified()}|$safeSize|${cornerRadiusPx.roundToInt()}"
            else -> null
        }
        val placeholderCacheKey = "placeholder_$placeholderResId|$safeSize|${cornerRadiusPx.roundToInt()}"

        if (realCacheKey != null) {
            roundedArtCache.get(realCacheKey)?.let { cached ->
                if (!cached.isRecycled) return cached
            }
        } else {
            roundedArtCache.get(placeholderCacheKey)?.let { cached ->
                if (!cached.isRecycled) return cached
            }
        }

        val targetDp = (safeSize / context.resources.displayMetrics.density.coerceAtLeast(1f)).roundToInt()
        val realSource = when {
            hasLiveBitmap -> liveBitmap
            hasExistingFile -> decodeDownsampledWidgetBitmap(
                filePath = filePath,
                context = context,
                targetSizeDp = targetDp,
            )
            else -> null
        }

        val source = realSource ?: decodePlaceholderWidgetBitmap(
            context = context,
            resId = placeholderResId,
            targetSizeDp = targetDp,
        ) ?: return null

        val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888).apply {
            this.density = context.resources.displayMetrics.densityDpi
        }
        val canvas = Canvas(output)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(safeSize.toFloat() / source.width.toFloat(), safeSize.toFloat() / source.height.toFloat())
        val dx = (safeSize - source.width * scale) / 2f
        val dy = (safeSize - source.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        shader.setLocalMatrix(matrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        val rect = RectF(0f, 0f, safeSize.toFloat(), safeSize.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

        val cacheKeyToStore = if (realSource != null && realCacheKey != null) {
            realCacheKey
        } else {
            placeholderCacheKey
        }
        roundedArtCache.put(cacheKeyToStore, output)
        return output
    }

    fun decodeDownsampledWidgetBitmap(
        filePath: String,
        context: Context,
        targetSizeDp: Int = 120,
    ): Bitmap? {
        if (filePath.isBlank()) return null
        val file = File(filePath)
        if (!file.exists() || !file.canRead() || file.length() < 64L) return null

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val reqSizePx = (targetSizeDp * density).roundToInt().coerceIn(72, 360)

        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, boundsOpts)
        if (boundsOpts.outWidth < 32 || boundsOpts.outHeight < 32) return null

        var inSampleSize = 1
        while (
            (boundsOpts.outWidth / inSampleSize) > reqSizePx * 2 &&
            (boundsOpts.outHeight / inSampleSize) > reqSizePx * 2
        ) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(filePath, decodeOpts) ?: return null
        return scaleBitmapForWidget(raw, context, reqSizePx)
    }

    fun decodePlaceholderWidgetBitmap(
        context: Context,
        resId: Int,
        targetSizeDp: Int = 120,
    ): Bitmap? {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val reqSizePx = (targetSizeDp * density).roundToInt().coerceIn(72, 360)

        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(context.resources, resId, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var inSampleSize = 1
        while (
            (boundsOpts.outWidth / inSampleSize) > reqSizePx * 2 &&
            (boundsOpts.outHeight / inSampleSize) > reqSizePx * 2
        ) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val raw = BitmapFactory.decodeResource(context.resources, resId, decodeOpts) ?: return null
        return scaleBitmapForWidget(raw, context, reqSizePx)
    }

    private fun scaleBitmapForWidget(
        raw: Bitmap,
        context: Context,
        reqSizePx: Int,
    ): Bitmap {
        val maxSide = max(raw.width, raw.height)
        val screenCapBytes = calculateWidgetMemoryCapBytes(context)
        val scaleForTarget = if (maxSide > reqSizePx) reqSizePx.toFloat() / maxSide.toFloat() else 1f
        val estimatedBytes = (raw.width * scaleForTarget) * (raw.height * scaleForTarget) * 2f
        val safeScale = if (estimatedBytes > screenCapBytes) {
            val ratio = screenCapBytes / estimatedBytes
            min(scaleForTarget, kotlin.math.sqrt(ratio.toDouble()).toFloat() * scaleForTarget)
        } else {
            scaleForTarget
        }

        if (safeScale >= 0.99f) return raw

        val dstW = (raw.width * safeScale).roundToInt().coerceAtLeast(48)
        val dstH = (raw.height * safeScale).roundToInt().coerceAtLeast(48)
        val scaled = Bitmap.createScaledBitmap(raw, dstW, dstH, true)
        if (scaled != raw) {
            raw.recycle()
        }
        return scaled
    }

    private fun calculateWidgetMemoryCapBytes(context: Context): Long {
        val metrics = context.resources.displayMetrics
        val totalCap = 6L * metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        return (totalCap / 4L).coerceIn(256_000L, 1_500_000L)
    }
}
