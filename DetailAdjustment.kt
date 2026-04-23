package com.example.miappcamarapro3.editing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import androidx.core.graphics.set
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ajustes avanzados de detalle: nitidez, claridad, reducción de ruido
 */
class DetailAdjustment {

    data class DetailParams(
        val sharpness: Float = 0f,      // -100 a 100
        val clarity: Float = 0f,          // -100 a 100
        val texture: Float = 0f,          // -100 a 100 (estructura)
        val denoise: Float = 0f,          // 0 a 100
        val denoiseColor: Float = 0f,     // 0 a 100 (ruido de color)
        val smoothing: Float = 0f         // 0 a 100 (suavizado de piel)
    )

    /**
     * Aplica todos los ajustes de detalle
     */
    fun apply(bitmap: Bitmap, params: DetailParams): Bitmap {
        var result = bitmap

        // Aplicar en orden óptimo
        if (params.denoise > 0 || params.denoiseColor > 0) {
            result = applyDenoise(result, params.denoise, params.denoiseColor)
        }

        if (params.clarity != 0f) {
            result = applyClarity(result, params.clarity)
        }

        if (params.sharpness != 0f) {
            result = applySharpening(result, params.sharpness)
        }

        if (params.texture != 0f) {
            result = applyTexture(result, params.texture)
        }

        if (params.smoothing > 0) {
            result = applySmoothing(result, params.smoothing)
        }

        return result
    }

    /**
     * Nitidez con Unsharp Mask
     */
    fun applySharpening(bitmap: Bitmap, amount: Float): Bitmap {
        if (amount == 0f) return bitmap

        val radius = when {
            amount > 50 -> 3
            amount > 0 -> 2
            else -> 1
        }

        val blurred = applyBoxBlur(bitmap, radius)
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

        val factor = amount / 100f * 2f

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val original = bitmap[x, y]
                val blur = blurred[x, y]

                val r = (Color.red(original) + (Color.red(original) - Color.red(blur)) * factor)
                    .toInt().coerceIn(0, 255)
                val g = (Color.green(original) + (Color.green(original) - Color.green(blur)) * factor)
                    .toInt().coerceIn(0, 255)
                val b = (Color.blue(original) + (Color.blue(original) - Color.blue(blur)) * factor)
                    .toInt().coerceIn(0, 255)

                result[x, y] = Color.rgb(r, g, b)
            }
        }

        blurred.recycle()
        return result
    }

    /**
     * Claridad: contraste de medios tonos con protección de extremos
     */
    fun applyClarity(bitmap: Bitmap, amount: Float): Bitmap {
        if (amount == 0f) return bitmap

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val factor = amount / 100f

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap[x, y]
                val r = Color.red(pixel) / 255f
                val g = Color.green(pixel) / 255f
                val b = Color.blue(pixel) / 255f

                // Aplicar S-curve suave en medios tonos
                val newR = applyClarityCurve(r, factor)
                val newG = applyClarityCurve(g, factor)
                val newB = applyClarityCurve(b, factor)

                result[x, y] = Color.rgb(
                    (newR * 255).toInt().coerceIn(0, 255),
                    (newG * 255).toInt().coerceIn(0, 255),
                    (newB * 255).toInt().coerceIn(0, 255)
                )
            }
        }

        return result
    }

    private fun applyClarityCurve(value: Float, factor: Float): Float {
        // Proteger sombras y highlights
        return when {
            value < 0.25f -> value + (value - 0.25f) * factor * 0.3f
            value > 0.75f -> value + (value - 0.75f) * factor * 0.3f
            else -> value + (value - 0.5f) * factor * 0.8f
        }.coerceIn(0f, 1f)
    }

    /**
     * Textura/estructura: realza o suaviza detalles finos
     */
    fun applyTexture(bitmap: Bitmap, amount: Float): Bitmap {
        if (amount == 0f) return bitmap

        // Usar high-pass filter simplificado
        val blurred = applyBoxBlur(bitmap, 1)
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

        val factor = amount / 100f * 0.5f

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val original = bitmap[x, y]
                val blur = blurred[x, y]

                val r = Color.red(original) + (Color.red(original) - Color.red(blur)) * factor
                val g = Color.green(original) + (Color.green(original) - Color.green(blur)) * factor
                val b = Color.blue(original) + (Color.blue(original) - Color.blue(blur)) * factor

                result[x, y] = Color.rgb(
                    r.toInt().coerceIn(0, 255),
                    g.toInt().coerceIn(0, 255),
                    b.toInt().coerceIn(0, 255)
                )
            }
        }

        blurred.recycle()
        return result
    }

    /**
     * Reducción de ruido
     */
    fun applyDenoise(bitmap: Bitmap, luminance: Float, color: Float = 0f): Bitmap {
        if (luminance == 0f && color == 0f) return bitmap

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        
        // Filtro de mediana simplificado para ruido de color
        if (color > 0) {
            for (y in 1 until bitmap.height - 1) {
                for (x in 1 until bitmap.width - 1) {
                    val neighbors = mutableListOf<Int>()
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            neighbors.add(bitmap[x + dx, y + dy])
                        }
                    }
                    
                    val pixel = bitmap[x, y]
                    val medianColor = getMedianColor(neighbors)
                    
                    val factor = color / 100f
                    val r = (Color.red(pixel) * (1 - factor) + Color.red(medianColor) * factor).toInt()
                    val g = (Color.green(pixel) * (1 - factor) + Color.green(medianColor) * factor).toInt()
                    val b = (Color.blue(pixel) * (1 - factor) + Color.blue(medianColor) * factor).toInt()
                    
                    result[x, y] = Color.rgb(r, g, b)
                }
            }
        }

        // Suavizado para ruido de luminancia
        if (luminance > 0) {
            val blurred = applyBoxBlur(if (color > 0) result else bitmap, 1)
            val blendFactor = luminance / 100f * 0.5f
            
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val original = bitmap[x, y]
                    val blur = blurred[x, y]
                    
                    val r = (Color.red(original) * (1 - blendFactor) + Color.red(blur) * blendFactor).toInt()
                    val g = (Color.green(original) * (1 - blendFactor) + Color.green(blur) * blendFactor).toInt()
                    val b = (Color.blue(original) * (1 - blendFactor) + Color.blue(blur) * blendFactor).toInt()
                    
                    result[x, y] = Color.rgb(r, g, b)
                }
            }
            blurred.recycle()
        }

        return result
    }

    /**
     * Suavizado de piel (preserva bordes)
     */
    fun applySmoothing(bitmap: Bitmap, amount: Float): Bitmap {
        if (amount == 0f) return bitmap

        // Detección simple de tonos de piel
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val blurRadius = (amount / 100f * 5).toInt().coerceAtLeast(1)
        val blurred = applyBoxBlur(bitmap, blurRadius)

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap[x, y]
                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)

                // Detectar tonos de piel (hue entre 15-45, saturación moderada)
                val isSkin = hsv[0] in 15f..45f && hsv[1] in 0.1f..0.6f && hsv[2] > 0.2f
                val factor = if (isSkin) amount / 100f else 0f

                val blurredPixel = blurred[x, y]
                val r = (Color.red(pixel) * (1 - factor) + Color.red(blurredPixel) * factor).toInt()
                val g = (Color.green(pixel) * (1 - factor) + Color.green(blurredPixel) * factor).toInt()
                val b = (Color.blue(pixel) * (1 - factor) + Color.blue(blurredPixel) * factor).toInt()

                result[x, y] = Color.rgb(r, g, b)
            }
        }

        blurred.recycle()
        return result
    }

    /**
     * Blur simple tipo box
     */
    private fun applyBoxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, bitmap.width - 1)
                        val ny = (y + dy).coerceIn(0, bitmap.height - 1)
                        val pixel = bitmap[nx, ny]
                        r += Color.red(pixel)
                        g += Color.green(pixel)
                        b += Color.blue(pixel)
                        count++
                    }
                }

                result[x, y] = Color.rgb(r / count, g / count, b / count)
            }
        }

        return result
    }

    private fun getMedianColor(colors: List<Int>): Int {
        val r = colors.map { Color.red(it) }.sorted()[colors.size / 2]
        val g = colors.map { Color.green(it) }.sorted()[colors.size / 2]
        val b = colors.map { Color.blue(it) }.sorted()[colors.size / 2]
        return Color.rgb(r, g, b)
    }
}


