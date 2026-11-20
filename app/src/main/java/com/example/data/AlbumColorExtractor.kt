package com.example.data

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import com.example.model.DynamicAlbumPalette
import kotlin.math.abs

object AlbumColorExtractor {

    /**
     * Extracts a unified Material 3-inspired pastel color scheme from the album artwork:
     * - Pastel background derived from the dominant artwork hue
     * - Slightly darker pastel pills for Previous/Next buttons
     * - A darker shade of the same album-art hue scheme for the Play/Pause button
     */
    fun extractPastelPalette(bitmap: Bitmap): DynamicAlbumPalette {
        val scaled = if (bitmap.width > 48 || bitmap.height > 48) {
            Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        } else {
            bitmap
        }

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val binCount = 24
        val binWeights = FloatArray(binCount)
        val binHueSin = FloatArray(binCount)
        val binHueCos = FloatArray(binCount)
        val binSatSum = FloatArray(binCount)

        val hsl = FloatArray(3)
        val fallbackHue = 145f // soft sage fallback if artwork is pure grayscale

        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 128) continue

            ColorUtils.colorToHSL(pixel, hsl)
            val hue = hsl[0]
            val sat = hsl[1]
            val light = hsl[2]

            if (light < 0.08f || light > 0.93f || sat < 0.12f) continue

            val bin = ((hue / 360f) * binCount).toInt().coerceIn(0, binCount - 1)
            val lightnessScore = 1f - abs(light - 0.5f) * 1.4f
            val weight = (0.35f + sat * 1.2f) * lightnessScore.coerceAtLeast(0.2f)

            val rad = Math.toRadians(hue.toDouble())
            binWeights[bin] += weight
            binHueSin[bin] += (kotlin.math.sin(rad) * weight).toFloat()
            binHueCos[bin] += (kotlin.math.cos(rad) * weight).toFloat()
            binSatSum[bin] += sat * weight
        }

        var primaryBin = -1
        var bestWeight = 0f
        for (i in 0 until binCount) {
            if (binWeights[i] > bestWeight) {
                bestWeight = binWeights[i]
                primaryBin = i
            }
        }

        val primaryHue: Float
        val primaryRawSat: Float
        if (primaryBin >= 0 && bestWeight > 0f) {
            val avgRad = kotlin.math.atan2(
                binHueSin[primaryBin].toDouble(),
                binHueCos[primaryBin].toDouble(),
            )
            primaryHue = (((Math.toDegrees(avgRad) % 360.0) + 360.0) % 360.0).toFloat()
            primaryRawSat = (binSatSum[primaryBin] / binWeights[primaryBin]).coerceIn(0f, 1f)
        } else {
            primaryHue = fallbackHue
            primaryRawSat = 0.25f
        }

        // Material 3 Pastel Container (Tone ~87)
        val bgSat = (0.24f + primaryRawSat * 0.18f).coerceIn(0.24f, 0.42f)
        val backgroundArgb = ColorUtils.HSLToColor(floatArrayOf(primaryHue, bgSat, 0.87f))

        // Prev/Next buttons: same hue scheme, slightly deeper pastel pill (Tone ~79)
        val buttonSat = (bgSat + 0.04f).coerceAtMost(0.46f)
        val elevatedArgb = ColorUtils.HSLToColor(floatArrayOf(primaryHue, buttonSat, 0.79f))

        // Play/Pause button: same album-art hue & pastel scheme as other buttons, just darker (Tone ~48)
        val playButtonSat = (buttonSat + 0.04f).coerceAtMost(0.50f)
        val playButtonArgb = ColorUtils.HSLToColor(floatArrayOf(primaryHue, playButtonSat, 0.48f))

        // High-contrast tonal ink colors matching the album hue
        val primaryTextArgb = ColorUtils.HSLToColor(floatArrayOf(primaryHue, 0.28f, 0.14f))
        val secondaryTextArgb = ColorUtils.HSLToColor(floatArrayOf(primaryHue, 0.22f, 0.34f))

        val lightIcon = 0xFFFCFBF8.toInt()
        val playIconArgb = if (
            ColorUtils.calculateContrast(lightIcon, playButtonArgb) >=
            ColorUtils.calculateContrast(primaryTextArgb, playButtonArgb)
        ) {
            lightIcon
        } else {
            primaryTextArgb
        }

        return DynamicAlbumPalette(
            backgroundArgb = backgroundArgb,
            elevatedArgb = elevatedArgb,
            playButtonArgb = playButtonArgb,
            playIconArgb = playIconArgb,
            primaryTextArgb = primaryTextArgb,
            secondaryTextArgb = secondaryTextArgb,
        )
    }
}
