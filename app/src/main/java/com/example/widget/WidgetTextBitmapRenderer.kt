package com.example.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.example.R
import kotlin.math.ceil
import kotlin.math.roundToInt

data class RenderedWidgetText(
    val bitmap: Bitmap,
    val widthDp: Dp,
    val heightDp: Dp,
)

/**
 * Renders single-line text using the bundled Google Sans typeface (`R.font.google_sans`)
 * onto tightly-cropped density-aware Bitmaps with an in-memory LRU cache so rapid widget
 * updates (like Play/Pause toggles) take < 0.2ms.
 */
object WidgetTextBitmapRenderer {

    @Volatile
    private var cachedBaseTypeface: Typeface? = null

    private val bitmapCache = LruCache<String, RenderedWidgetText>(24)

    private fun getGoogleSansTypeface(context: Context, weight: Int): Typeface {
        val base = cachedBaseTypeface ?: run {
            val loaded = runCatching {
                ResourcesCompat.getFont(context, R.font.google_sans)
            }.getOrNull() ?: Typeface.SANS_SERIF
            cachedBaseTypeface = loaded
            loaded
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            val style = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
            Typeface.create(base, style)
        }
    }

    fun renderSingleLine(
        context: Context,
        text: String,
        textSizeSp: Float,
        colorArgb: Int,
        maxWidthDp: Float,
        fontWeight: Int = 400,
        letterSpacingEm: Float = 0f,
    ): RenderedWidgetText? {
        if (text.isBlank() || maxWidthDp <= 4f) return null

        val key = "$text|${(textSizeSp * 10).roundToInt()}|$colorArgb|${maxWidthDp.roundToInt()}|$fontWeight"
        bitmapCache.get(key)?.let { cached ->
            if (!cached.bitmap.isRecycled) return cached
        }

        return runCatching {
            val metrics = context.resources.displayMetrics
            val density = metrics.density.coerceAtLeast(1f)
            val fontScale = (metrics.scaledDensity / density).coerceIn(0.85f, 1.2f)
            val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeSp * fontScale,
                metrics,
            )
            val maxWidthPx = (maxWidthDp * density).coerceAtLeast(density * 8f)

            val typeface = getGoogleSansTypeface(context, fontWeight)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.typeface = typeface
                this.textSize = textSizePx
                this.color = colorArgb
                this.letterSpacing = letterSpacingEm
                if (fontWeight >= 600 && !typeface.isBold) {
                    isFakeBoldText = true
                }
            }

            val ellipsized = TextUtils.ellipsize(
                text,
                paint,
                maxWidthPx,
                TextUtils.TruncateAt.END,
            ).toString()

            if (ellipsized.isEmpty()) return null

            val fm = paint.fontMetrics
            val measuredWidthPx = ceil(paint.measureText(ellipsized).toDouble()).toInt()
            val padHorizontalPx = ceil((density * 1f).toDouble()).toInt()
            val widthPx = (measuredWidthPx + padHorizontalPx)
                .coerceIn(1, ceil(maxWidthPx.toDouble()).toInt().coerceAtLeast(1))
            val heightPx = ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
                this.density = metrics.densityDpi
            }
            val canvas = Canvas(bitmap)
            canvas.drawText(ellipsized, 0f, -fm.ascent, paint)

            val rendered = RenderedWidgetText(
                bitmap = bitmap,
                widthDp = (widthPx / density).dp,
                heightDp = (heightPx / density).dp,
            )
            bitmapCache.put(key, rendered)
            rendered
        }.getOrNull()
    }
}
