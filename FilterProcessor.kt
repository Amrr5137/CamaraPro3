package com.example.miappcamarapro3.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set

class FilterProcessor() {

    fun applyFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        return when (filter) {
            FilterType.NORMAL, FilterType.NONE -> bitmap.copy(Bitmap.Config.ARGB_8888, true)
            FilterType.BLACK_WHITE, FilterType.MONOCHROME -> applyBlackAndWhite(bitmap)
            FilterType.SEPIA -> applySepia(bitmap)
            FilterType.NEGATIVE, FilterType.INVERT -> applyNegative(bitmap)
            FilterType.SIMULATED_IR -> applySimulatedIR(bitmap)
            FilterType.NIGHT_VISION -> applyNightVision(bitmap)
            FilterType.THERMAL_SIM -> applyThermalSimulation(bitmap)
            FilterType.SKETCH -> applySketch(bitmap)
        }
    }

    // ============ FILTROS BÁSICOS ============

    private fun applyBlackAndWhite(bitmap: Bitmap): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
            })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun applySepia(bitmap: Bitmap): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                set(
                    floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun applyNegative(bitmap: Bitmap): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                set(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    // ============ FILTROS ESPECIALIZADOS ============

    fun applySimulatedIR(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            val irIntensity = (r * 0.6 + g * 0.3 + b * 0.1).toInt()

            val newR = (irIntensity * 1.2).toInt().coerceIn(0, 255)
            val newG = irIntensity
            val newB = (irIntensity * 1.5).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(newR, newG, newB)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return applyHighContrast(result, 1.4f)
    }

    fun applyNightVision(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val brightness = Color.red(pixel) * 0.299 +
                    Color.green(pixel) * 0.587 +
                    Color.blue(pixel) * 0.114

            val amplified = (brightness * 2.0).toInt().coerceIn(0, 255)
            val green = (amplified * 0.9).toInt().coerceIn(0, 255)

            val noise = (kotlin.random.Random.nextDouble() * 10 - 5).toInt()
            val finalGreen = (green + noise).coerceIn(0, 255)

            pixels[i] = Color.rgb(0, finalGreen, 0)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    fun applyThermalSimulation(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val brightness = (Color.red(pixel) * 0.299 +
                    Color.green(pixel) * 0.587 +
                    Color.blue(pixel) * 0.114).toInt()

            pixels[i] = when {
                brightness < 30 -> Color.BLACK
                brightness < 60 -> Color.rgb(0, 0, brightness * 4)
                brightness < 100 -> Color.rgb(brightness - 60, 0, 255)
                brightness < 140 -> Color.rgb(255, 0, 255 - (brightness - 100) * 4)
                brightness < 180 -> Color.rgb(255, (brightness - 140) * 4, 0)
                brightness < 220 -> Color.rgb(255, 255, (brightness - 180) * 4)
                else -> Color.WHITE
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    fun applySketch(bitmap: Bitmap): Bitmap {
        val gray = applyBlackAndWhite(bitmap)
        val inverted = applyNegative(gray)
        val blurred = applyFastBlur(inverted, 5)
        return colorDodgeBlend(gray, blurred)
    }

    // ============ UTILIDADES ============

    private fun applyHighContrast(bitmap: Bitmap, contrast: Float): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            val scale = contrast
            val translate = (-0.5f * scale + 0.5f) * 255f
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                set(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, translate,
                        0f, scale, 0f, 0f, translate,
                        0f, 0f, scale, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Reemplazo moderno para RenderScript Blur (Fast Box Blur)
     */
    private fun applyFastBlur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val wm = width - 1
        val hm = height - 1
        val wh = width * height
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int

        val vmin = IntArray(maxOf(width, height))
        val vmax = IntArray(maxOf(width, height))

        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) {
            dv[i] = (i / div)
        }

        yw = 0
        yi = 0

        for (y in 0 until height) {
            rsum = 0
            gsum = 0
            bsum = 0
            for (i in -radius..radius) {
                p = pixels[yi + minOf(wm, maxOf(i, 0))]
                rsum += (p and 0xff0000) shr 16
                gsum += (p and 0x00ff00) shr 8
                bsum += (p and 0x0000ff)
            }
            for (x in 0 until width) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                    vmax[x] = maxOf(x - radius, 0)
                }
                val p1 = pixels[yw + vmin[x]]
                val p2 = pixels[yw + vmax[x]]

                rsum += ((p1 and 0xff0000) - (p2 and 0xff0000)) shr 16
                gsum += ((p1 and 0x00ff00) - (p2 and 0x00ff00)) shr 8
                bsum += (p1 and 0x0000ff) - (p2 and 0x0000ff)
                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * width
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x
                rsum += r[yi]
                gsum += g[yi]
                bsum += b[yi]
                yp += width
            }
            yi = x
            for (y in 0 until height) {
                pixels[yi] =
                    (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                if (x == 0) {
                    vmin[y] = minOf(y + radius + 1, hm) * width
                    vmax[y] = maxOf(y - radius, 0) * width
                }
                val p1 = x + vmin[y]
                val p2 = x + vmax[y]

                rsum += r[p1] - r[p2]
                gsum += g[p1] - g[p2]
                bsum += b[p1] - b[p2]

                yi += width
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun colorDodgeBlend(base: Bitmap, blend: Bitmap): Bitmap {
        val width = base.width
        val height = base.height
        val result = createBitmap(width, height)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val basePixel = base[x, y]
                val blendPixel = blend[x, y]

                val r = colorDodge(Color.red(basePixel), Color.red(blendPixel))
                val g = colorDodge(Color.green(basePixel), Color.green(blendPixel))
                val b = colorDodge(Color.blue(basePixel), Color.blue(blendPixel))

                result[x, y] = Color.rgb(r, g, b)
            }
        }
        return result
    }

    private fun colorDodge(base: Int, blend: Int): Int {
        return if (blend == 255) 255 else (base * 255 / (255 - blend)).coerceIn(0, 255)
    }
}

