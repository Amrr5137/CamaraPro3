package com.example.miappcamarapro3.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Procesador de filtros avanzados con efectos profesionales
 * Incluye: LUTs, Curvas, HSL, Film Grain, Vignette, Bokeh, Tilt-shift
 */
@Suppress("DEPRECATION")
class AdvancedFilterProcessor {

    // ============ CURVAS DE AJUSTE ============

    data class CurvePoint(val x: Float, val y: Float)

    class ToneCurve {
        val red = ArrayList<CurvePoint>()
        val green = ArrayList<CurvePoint>()
        val blue = ArrayList<CurvePoint>()
        val rgb = ArrayList<CurvePoint>()

        init {
            // Curva identidad por defecto
            rgb.add(CurvePoint(0f, 0f))
            rgb.add(CurvePoint(1f, 1f))
        }

        fun setRgbCurve(points: List<CurvePoint>) {
            rgb.clear()
            rgb.addAll(points.sortedBy { it.x })
        }

        fun getInterpolatedValue(input: Float, channel: Channel = Channel.RGB): Float {
            val curve = when (channel) {
                Channel.RED -> red.ifEmpty { rgb }
                Channel.GREEN -> green.ifEmpty { rgb }
                Channel.BLUE -> blue.ifEmpty { rgb }
                else -> rgb
            }

            if (curve.size < 2) return input

            // Interpolación lineal
            for (i in 0 until curve.size - 1) {
                if (input >= curve[i].x && input <= curve[i + 1].x) {
                    val t = (input - curve[i].x) / (curve[i + 1].x - curve[i].x)
                    return curve[i].y + t * (curve[i + 1].y - curve[i].y)
                }
            }
            return input.coerceIn(0f, 1f)
        }
    }

    enum class Channel { RGB, RED, GREEN, BLUE }

    /**
     * Aplica curvas de tono a la imagen (Optimizado con LUT)
     */
    fun applyToneCurves(bitmap: Bitmap, curve: ToneCurve): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Pre-calcular tablas de búsqueda (LUT) para cada canal (0..255)
        val lutR = IntArray(256) { i -> (curve.getInterpolatedValue(i / 255f, Channel.RED) * 255).toInt().coerceIn(0, 255) }
        val lutG = IntArray(256) { i -> (curve.getInterpolatedValue(i / 255f, Channel.GREEN) * 255).toInt().coerceIn(0, 255) }
        val lutB = IntArray(256) { i -> (curve.getInterpolatedValue(i / 255f, Channel.BLUE) * 255).toInt().coerceIn(0, 255) }

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            pixels[i] = Color.rgb(lutR[r], lutG[g], lutB[b])
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    // ============ HSL SELECTIVO ============

    data class HslAdjustment(
        val hueShift: Float = 0f,           // -180 a 180
        val saturationMultiplier: Float = 1f, // 0 a 2
        val lightnessMultiplier: Float = 1f   // 0 a 2
    )

    /**
     * Ajusta HSL por rangos de color
     */
    fun applyHslAdjustment(
        bitmap: Bitmap,
        colorRanges: Map<ColorRange, HslAdjustment>
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val hsv = FloatArray(3)
            Color.colorToHSV(pixel, hsv)

            val hue = hsv[0]
            val matchedRange = colorRanges.entries.find { (range, _) ->
                hue in range.start..range.end ||
                        (range.wrapAround && (hue >= range.start || hue <= range.end))
            }

            matchedRange?.let { (_, adjustment) ->
                hsv[0] = (hsv[0] + adjustment.hueShift + 360) % 360
                hsv[1] = (hsv[1] * adjustment.saturationMultiplier).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] * adjustment.lightnessMultiplier).coerceIn(0f, 1f)
                pixels[i] = Color.HSVToColor(Color.alpha(pixel), hsv)
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    data class ColorRange(
        val start: Float,
        val end: Float,
        val wrapAround: Boolean = false
    ) {
        companion object {
            val RED = ColorRange(330f, 30f, true)
            val ORANGE = ColorRange(30f, 60f)
            val YELLOW = ColorRange(60f, 90f)
            val GREEN = ColorRange(90f, 150f)
            val CYAN = ColorRange(150f, 210f)
            val BLUE = ColorRange(210f, 270f)
            val PURPLE = ColorRange(270f, 330f)
        }
    }

    // ============ FILM GRAIN ============

    /**
     * Añade grano analógico realista
     */
    fun applyFilmGrain(bitmap: Bitmap, intensity: Float = 0.3f, size: Float = 1.0f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val noiseBitmap = createBitmap(width, height)
        Canvas(noiseBitmap)
        Paint()

        // Generar ruido con distribución más natural (Gaussiana aproximada)
        val random = Random(System.currentTimeMillis())
        val noisePixels = IntArray(width * height)

        for (i in noisePixels.indices) {
            val noise = (random.nextGaussian() * intensity * 50).toInt()
            val noiseColor = Color.rgb(
                (128 + noise).coerceIn(0, 255),
                (128 + noise).coerceIn(0, 255),
                (128 + noise).coerceIn(0, 255)
            )
            noisePixels[i] = noiseColor
        }

        noiseBitmap.setPixels(noisePixels, 0, width, 0, 0, width, height)

        // Desenfoque ligero para suavizar el grano
        val blurredNoise = applyGaussianBlur(noiseBitmap, size)

        // Blend con overlay
        val overlayPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            alpha = (intensity * 255).toInt()
        }

        val canvas = Canvas(result)
        canvas.drawBitmap(blurredNoise, 0f, 0f, overlayPaint)

        noiseBitmap.recycle()
        if (blurredNoise != noiseBitmap) blurredNoise.recycle()

        return result
    }

    // ============ VIGNETTE ============

    data class VignetteParams(
        val intensity: Float = 0.5f,      // 0 a 1
        val feather: Float = 0.5f,        // 0 a 1 (suavidad del borde)
        val shape: VignetteShape = VignetteShape.ELLIPSE,
        val color: Int = Color.BLACK
    )

    enum class VignetteShape { ELLIPSE, RECTANGLE, DIAMOND }

    fun applyVignette(bitmap: Bitmap, params: VignetteParams): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val vignetteBitmap = createBitmap(width, height)
        val canvas = Canvas(vignetteBitmap)

        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = sqrt(centerX * centerX + centerY * centerY)
        val innerRadius = maxRadius * (1 - params.feather) * (1 - params.intensity)

        val gradient = when (params.shape) {
            VignetteShape.ELLIPSE -> RadialGradient(
                centerX, centerY, maxRadius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, params.color),
                floatArrayOf(0f, innerRadius / maxRadius, 1f),
                Shader.TileMode.CLAMP
            )

            VignetteShape.RECTANGLE -> {
                // Crear gradiente rectangular
                val colors = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, params.color)
                val positions = floatArrayOf(0f, params.feather, 1f)
                LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    0f,
                    colors,
                    positions,
                    Shader.TileMode.CLAMP
                )
            }

            else -> RadialGradient(
                centerX, centerY, maxRadius,
                intArrayOf(Color.TRANSPARENT, params.color),
                floatArrayOf(params.feather, 1f),
                Shader.TileMode.CLAMP
            )
        }

        val paint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Blend multiply
        val multiplyPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }

        val resultCanvas = Canvas(result)
        resultCanvas.drawBitmap(vignetteBitmap, 0f, 0f, multiplyPaint)

        vignetteBitmap.recycle()
        return result
    }

    // ============ BOKEH / DESENFOQUE DE LENTE ============

    /**
     * Simula desenfoque tipo lente con apertura grande
     */
    fun applyBokeh(
        bitmap: Bitmap, blurRadius: Float = 15f,
        focalPoint: PointF? = null, focalRadius: Float = 0.3f
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Crear máscara de profundidad simple (circular desde el centro o punto focal)
        val maskBitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(maskBitmap)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val centerX = focalPoint?.x ?: (width / 2f)
        val centerY = focalPoint?.y ?: (height / 2f)
        val maxDist = sqrt((width / 2f).pow(2f) + (height / 2f).pow(2f))
        val focalPx = maxDist * focalRadius

        val gradient = RadialGradient(
            centerX, centerY, maxDist,
            intArrayOf(Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(focalPx / maxDist, 1f),
            Shader.TileMode.CLAMP
        )

        maskPaint.shader = gradient
        maskCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // Aplicar desenfoque gaussiano
        val blurredBitmap = applyGaussianBlur(bitmap, blurRadius)

        // Combinar original (nítido) + desenfocado usando máscara
        val result = createBitmap(width, height)
        val resultCanvas = Canvas(result)

        // Primero dibujar la imagen nítida
        resultCanvas.drawBitmap(bitmap, 0f, 0f, null)

        // Luego dibujar el desenfoque con la máscara
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        resultCanvas.drawBitmap(maskBitmap, 0f, 0f, paint)

        val blurPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
        }
        resultCanvas.drawBitmap(blurredBitmap, 0f, 0f, blurPaint)

        maskBitmap.recycle()
        if (blurredBitmap != bitmap) blurredBitmap.recycle()

        return result
    }

    // ============ TILT-SHIFT ============

    fun applyTiltShift(
        bitmap: Bitmap, blurRadius: Float = 20f,
        focusY: Float = 0.5f, focusWidth: Float = 0.2f,
        angle: Float = 0f
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        // Crear máscara lineal con gradiente
        val maskBitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(maskBitmap)

        val focusCenterY = height * focusY
        val focusHalfWidth = height * focusWidth / 2

        val gradient = LinearGradient(
            0f, focusCenterY - focusHalfWidth,
            0f, focusCenterY + focusHalfWidth,
            intArrayOf(Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        val paint = Paint().apply { shader = gradient }
        maskCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Aplicar desenfoque
        val blurredBitmap = applyGaussianBlur(bitmap, blurRadius)

        // Combinar
        val resultCanvas = Canvas(result)
        resultCanvas.drawBitmap(bitmap, 0f, 0f, null)

        val maskPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        resultCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)

        val blurPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
        }
        resultCanvas.drawBitmap(blurredBitmap, 0f, 0f, blurPaint)

        maskBitmap.recycle()
        if (blurredBitmap != bitmap) blurredBitmap.recycle()

        return result
    }

    // ============ LUTS (LOOK-UP TABLES) ============

    /**
     * Aplica LUT 3D para grading cinematográfico
     * El LUT debe ser una imagen PNG de 64x4096 (64x64x64 aplanado)
     */
    fun applyLut(bitmap: Bitmap, lutBitmap: Bitmap): Bitmap {
        val lutSize = 64
        val result = createBitmap(bitmap.width, bitmap.height)

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val lutPixels = IntArray(lutBitmap.width * lutBitmap.height)
        lutBitmap.getPixels(lutPixels, 0, lutBitmap.width, 0, 0, lutBitmap.width, lutBitmap.height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // Escalar 0..255 a 0..63 para LUT de tamaño 64
            val rIdx = r * (lutSize - 1) / 255
            val gIdx = g * (lutSize - 1) / 255
            val bIdx = b * (lutSize - 1) / 255

            // Calcular posición en LUT (formato 64x4096: b * 64 * 64 + g * 64 + r)
            val lutIndex = bIdx * lutSize * lutSize + gIdx * lutSize + rIdx
            if (lutIndex < lutPixels.size) {
                pixels[i] = lutPixels[lutIndex]
            }
        }

        result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }

    // ============ UTILIDADES ============

    private fun applyGaussianBlur(bitmap: Bitmap, radius: Float): Bitmap {
        return applyFastBlur(bitmap, radius.toInt().coerceIn(1, 25))
    }

    /**
     * Fast Box Blur Algorithm as a replacement for RenderScript
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

        val vmin = IntArray(width.coerceAtLeast(height))
        val vmax = IntArray(width.coerceAtLeast(height))

        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) {
            dv[i] = (i / div)
        }

        var yw = 0
        yi = 0

        for (y in 0 until height) {
            rsum = 0
            gsum = 0
            bsum = 0
            for (i in -radius..radius) {
                p = pixels[yi + wm.coerceAtMost(i.coerceAtLeast(0))]
                rsum += (p and 0xff0000) shr 16
                gsum += (p and 0x00ff00) shr 8
                bsum += (p and 0x0000ff)
            }
            for (x in 0 until width) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                if (y == 0) {
                    vmin[x] = (x + radius + 1).coerceAtMost(wm)
                    vmax[x] = (x - radius).coerceAtLeast(0)
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
                yi = 0.coerceAtLeast(yp) + x
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
                    vmin[y] = (y + radius + 1).coerceAtMost(hm) * width
                    vmax[y] = (y - radius).coerceAtLeast(0) * width
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

    /**
     * Ajustes de exposición completos tipo Lightroom (Optimizado)
     */
    fun applyExposureAdjustments(
        bitmap: Bitmap,
        exposure: Float = 0f,      // -5 a 5
        contrast: Float = 0f,       // -100 a 100
        highlights: Float = 0f,       // -100 a 100
        shadows: Float = 0f,        // -100 a 100
        whites: Float = 0f,         // -100 a 100
        blacks: Float = 0f           // -100 a 100
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val exposureFactor = 2f.pow(exposure)
        val contrastFactor = (contrast + 100) / 100f
        val whiteAdj = whites / 100f
        val blackAdj = blacks / 100f
        val highAdj = highlights / 100f
        val shadAdj = shadows / 100f

        for (i in pixels.indices) {
            val pixel = pixels[i]

            var r = (Color.red(pixel) / 255f) * exposureFactor
            var g = (Color.green(pixel) / 255f) * exposureFactor
            var b = (Color.blue(pixel) / 255f) * exposureFactor

            // Contrast
            r = (r - 0.5f) * contrastFactor + 0.5f
            g = (g - 0.5f) * contrastFactor + 0.5f
            b = (b - 0.5f) * contrastFactor + 0.5f

            // Highlights & Shadows
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            if (luminance > 0.5f) {
                val f = 1.0f - highAdj * (luminance - 0.5f) * 2.0f
                r *= f; g *= f; b *= f
            } else {
                val f = 1.0f + shadAdj * (0.5f - luminance) * 2.0f
                r *= f; g *= f; b *= f
            }

            // Whites & Blacks
            r = (r * (1f + whiteAdj) + blackAdj).coerceIn(0f, 1f)
            g = (g * (1f + whiteAdj) + blackAdj).coerceIn(0f, 1f)
            b = (b * (1f + whiteAdj) + blackAdj).coerceIn(0f, 1f)

            pixels[i] = Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Ajustes de color: Temperatura, Tint, Vibrance, Saturación (Optimizado)
     */
    fun applyColorAdjustments(
        bitmap: Bitmap,
        temperature: Float = 0f,    // -100 a 100
        tint: Float = 0f,           // -100 a 100
        vibrance: Float = 0f,        // -100 a 100
        saturation: Float = 0f       // -100 a 100
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val satFactor = 1f + saturation / 100f
        val vibFactor = vibrance / 100f
        
        val tempShift = (temperature / 100f) * 15f
        val tintShift = (tint / 100f) * 10f

        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            Color.colorToHSV(pixel, hsv)

            // Temperature / Tint
            hsv[0] = (hsv[0] + tempShift + tintShift + 360f) % 360f
            
            // Vibrance: saturación que protege lo ya saturado
            val currentSat = hsv[1]
            if (vibFactor != 0f) {
                hsv[1] = (currentSat + vibFactor * (1f - currentSat)).coerceIn(0f, 1f)
            }

            // Saturación global
            hsv[1] = (hsv[1] * satFactor).coerceIn(0f, 1f)

            pixels[i] = Color.HSVToColor(Color.alpha(pixel), hsv)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Ajustes de detalle: Nitidez, Claridad, Reducción de ruido
     */
    fun applyDetailAdjustments(
        bitmap: Bitmap,
        sharpness: Float = 0f,       // -100 a 100
        clarity: Float = 0f,         // -100 a 100 (contraste de medios tonos)
        denoise: Float = 0f          // 0 a 100
    ): Bitmap {
        var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        // Nitidez: Unsharp mask
        if (sharpness != 0f) {
            result = applyUnsharpMask(result, sharpness)
        }

        // Claridad: Contraste de medios tonos con protección de highlights/shadows
        if (clarity != 0f) {
            result = applyClarity(result, clarity)
        }

        // Reducción de ruido
        if (denoise > 0) {
            result = applyDenoise(result, denoise)
        }

        return result
    }

    private fun applyUnsharpMask(bitmap: Bitmap, amount: Float): Bitmap {
        val blurred = applyGaussianBlur(bitmap, 2f)
        val result = createBitmap(bitmap.width, bitmap.height)

        val originalPixels = IntArray(bitmap.width * bitmap.height)
        val blurredPixels = IntArray(bitmap.width * bitmap.height)

        bitmap.getPixels(originalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        blurred.getPixels(blurredPixels, 0, blurred.width, 0, 0, blurred.width, blurred.height)

        val factor = amount / 100f

        for (i in originalPixels.indices) {
            val orig = originalPixels[i]
            val blur = blurredPixels[i]

            val r = (Color.red(orig) + (Color.red(orig) - Color.red(blur)) * factor).toInt()
                .coerceIn(0, 255)
            val g = (Color.green(orig) + (Color.green(orig) - Color.green(blur)) * factor).toInt()
                .coerceIn(0, 255)
            val b = (Color.blue(orig) + (Color.blue(orig) - Color.blue(blur)) * factor).toInt()
                .coerceIn(0, 255)

            originalPixels[i] = Color.rgb(r, g, b)
        }

        result.setPixels(originalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        blurred.recycle()
        return result
    }

    private fun applyClarity(bitmap: Bitmap, amount: Float): Bitmap {
        // Simplificación: aplicar contraste local con protección de extremos
        return applyExposureAdjustments(bitmap, contrast = amount * 0.5f)
    }

    private fun applyDenoise(bitmap: Bitmap, strength: Float): Bitmap {
        // Usar RenderScript para desenfoque bilateral (simplificado como gaussiano)
        return applyGaussianBlur(bitmap, strength / 20f)
    }

    fun release() {
    }
}


