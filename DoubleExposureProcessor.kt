package com.example.miappcamarapro3.filters

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Procesador de efecto Double Exposure (doble exposición)
 * Combina dos imágenes con modos de fusión creativos
 */
class DoubleExposureProcessor {

    enum class BlendMode {
        SCREEN,          // Modo pantalla (clásico)
        MULTIPLY,        // Multiplicar
        OVERLAY,         // Superposición
        SOFT_LIGHT,      // Luz suave
        HARD_LIGHT,      // Luz dura
        DIFFERENCE,      // Diferencia
        EXCLUSION,       // Exclusión
        COLOR_DODGE,     // Color dodge
        LINEAR_DODGE,    // Linear dodge
        DARKEN,          // Oscurecer
        LIGHTEN          // Aclarar
    }

    data class DoubleExposureParams(
        val blendMode: BlendMode = BlendMode.SCREEN,
        val baseOpacity: Float = 1.0f,           // Opacidad imagen base (0-1)
        val overlayOpacity: Float = 0.7f,        // Opacidad imagen superpuesta (0-1)
        val overlayScale: Float = 1.0f,          // Escala de la imagen superpuesta
        val overlayOffsetX: Float = 0f,          // Desplazamiento X (-1 a 1)
        val overlayOffsetY: Float = 0f,          // Desplazamiento Y (-1 a 1)
        val overlayRotation: Float = 0f,           // Rotación en grados
        val maskType: MaskType = MaskType.NONE,  // Tipo de máscara
        val maskIntensity: Float = 0.5f,         // Intensidad de la máscara
        val invertMask: Boolean = false          // Invertir máscara
    )

    enum class MaskType {
        NONE,           // Sin máscara
        CIRCLE,         // Máscara circular
        ELLIPSE,        // Máscara elíptica
        GRADIENT_RADIAL,// Gradiente radial
        GRADIENT_LINEAR,// Gradiente lineal
        TEXTURE,        // Máscara basada en textura (luminosidad)
        EDGES           // Máscara basada en bordes
    }

    /**
     * Aplica efecto double exposure entre dos imágenes
     */
    fun applyDoubleExposure(
        baseImage: Bitmap,
        overlayImage: Bitmap,
        params: DoubleExposureParams = DoubleExposureParams()
    ): Bitmap {
        val width = baseImage.width
        val height = baseImage.height

        // Crear bitmap resultado
        val result = createBitmap(width, height)
        val canvas = Canvas(result)

        // Dibujar imagen base
        val basePaint = Paint().apply {
            alpha = (params.baseOpacity * 255).toInt()
        }
        canvas.drawBitmap(baseImage, 0f, 0f, basePaint)

        // Preparar imagen superpuesta
        val overlayBitmap = prepareOverlayImage(overlayImage, width, height, params)

        // Aplicar máscara si es necesario
        val maskedOverlay = when (params.maskType) {
            MaskType.NONE -> overlayBitmap
            MaskType.CIRCLE -> applyCircularMask(overlayBitmap, params)
            MaskType.ELLIPSE -> applyEllipticalMask(overlayBitmap, params)
            MaskType.GRADIENT_RADIAL -> applyRadialGradientMask(overlayBitmap, params)
            MaskType.GRADIENT_LINEAR -> applyLinearGradientMask(overlayBitmap, params)
            MaskType.TEXTURE -> applyTextureMask(overlayBitmap, baseImage, params)
            MaskType.EDGES -> applyEdgesMask(overlayBitmap, baseImage, params)
        }

        // Aplicar modo de fusión
        val blendPaint = createBlendPaint(params.blendMode, params.overlayOpacity)
        canvas.drawBitmap(maskedOverlay, 0f, 0f, blendPaint)

        // Limpiar
        if (overlayBitmap != overlayImage) overlayBitmap.recycle()
        if (maskedOverlay != overlayBitmap) maskedOverlay.recycle()

        return result
    }

    /**
     * Prepara la imagen superpuesta (escala, rotación, posición)
     */
    private fun prepareOverlayImage(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        params: DoubleExposureParams
    ): Bitmap {
        val result = createBitmap(targetWidth, targetHeight)
        val canvas = Canvas(result)

        canvas.withSave {
            // Trasladar al centro
            translate(targetWidth / 2f, targetHeight / 2f)

            // Aplicar desplazamiento
            translate(
                params.overlayOffsetX * targetWidth / 2,
                params.overlayOffsetY * targetHeight / 2
            )

            // Aplicar rotación
            rotate(params.overlayRotation)

            // Aplicar escala
            val scale = params.overlayScale
            scale(scale, scale)

            // Dibujar imagen centrada
            val left = -source.width / 2f
            val top = -source.height / 2f
            drawBitmap(source, left, top, null)
        }

        return result
    }

    /**
     * Crea Paint con modo de fusión específico
     */
    private fun createBlendPaint(mode: BlendMode, opacity: Float): Paint {
        return Paint().apply {
            alpha = (opacity * 255).toInt()
            xfermode = when (mode) {
                BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                BlendMode.DARKEN -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                BlendMode.LIGHTEN -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                else -> null // Para modos complejos usamos shader personalizado
            }
        }
    }

    // ============ MÁSCARAS ============

    private fun applyCircularMask(bitmap: Bitmap, params: DoubleExposureParams): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)

        val paint = Paint().apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val radius = min(centerX, centerY) * params.maskIntensity

        canvas.drawCircle(centerX, centerY, radius, paint)

        // Aplicar suavizado al borde
        val edgePaint = Paint().apply {
            shader = RadialGradient(
                centerX, centerY, radius,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(params.maskIntensity * 0.8f, params.maskIntensity),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawCircle(centerX, centerY, radius, edgePaint)

        return result
    }

    private fun applyEllipticalMask(bitmap: Bitmap, params: DoubleExposureParams): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)

        val paint = Paint().apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val rx = bitmap.width * params.maskIntensity / 2
        val ry = bitmap.height * params.maskIntensity / 2

        canvas.drawOval(
            centerX - rx, centerY - ry,
            centerX + rx, centerY + ry,
            paint
        )

        return result
    }

    private fun applyRadialGradientMask(bitmap: Bitmap, params: DoubleExposureParams): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)

        // Dibujar imagen
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Aplicar gradiente como máscara de alpha
        val gradientPaint = Paint().apply {
            shader = RadialGradient(
                bitmap.width / 2f, bitmap.height / 2f,
                max(bitmap.width, bitmap.height) / 2f,
                intArrayOf(Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, params.maskIntensity),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), gradientPaint)

        return result
    }

    private fun applyLinearGradientMask(bitmap: Bitmap, params: DoubleExposureParams): Bitmap {
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(result)

        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val gradientPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, bitmap.height.toFloat(),
                intArrayOf(Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, params.maskIntensity),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), gradientPaint)

        return result
    }

    private fun applyTextureMask(
        overlay: Bitmap,
        base: Bitmap,
        params: DoubleExposureParams
    ): Bitmap {
        // Crear máscara basada en luminosidad de la imagen base
        val mask = createBitmap(base.width, base.height, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(mask)

        val basePixels = IntArray(base.width * base.height)
        base.getPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)

        val maskPixels = IntArray(base.width * base.height)
        for (i in basePixels.indices) {
            val luminance = (0.299 * Color.red(basePixels[i]) +
                    0.587 * Color.green(basePixels[i]) +
                    0.114 * Color.blue(basePixels[i])).toInt()
            val alpha = (luminance / 255f * params.maskIntensity * 255).toInt()
            maskPixels[i] = Color.alpha(alpha)
        }

        mask.setPixels(maskPixels, 0, base.width, 0, 0, base.width, base.height)

        // Aplicar máscara
        val result = createBitmap(overlay.width, overlay.height)
        val canvas = Canvas(result)
        canvas.drawBitmap(overlay, 0f, 0f, null)

        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(mask, 0f, 0f, paint)

        mask.recycle()
        return result
    }

    private fun applyEdgesMask(
        overlay: Bitmap,
        base: Bitmap,
        params: DoubleExposureParams
    ): Bitmap {
        // Detectar bordes en imagen base y usar como máscara
        val edges = detectEdges(base)
        val result = createBitmap(overlay.width, overlay.height)
        val canvas = Canvas(result)

        canvas.drawBitmap(overlay, 0f, 0f, null)

        val paint = Paint().apply {
            alpha = (params.maskIntensity * 255).toInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(edges, 0f, 0f, paint)

        edges.recycle()
        return result
    }

    private fun detectEdges(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = createBitmap(width, height, Bitmap.Config.ALPHA_8)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val edges = IntArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x

                // Filtro Sobel simplificado
                val gx = luminance(pixels[idx - 1]) - luminance(pixels[idx + 1])
                val gy = luminance(pixels[idx - width]) - luminance(pixels[idx + width])

                val magnitude = min(255, sqrt((gx * gx + gy * gy).toDouble()).toInt())
                edges[idx] = Color.alpha(magnitude)
            }
        }

        result.setPixels(edges, 0, width, 0, 0, width, height)
        return result
    }

    private fun luminance(pixel: Int): Int {
        return (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
    }

    /**
     * Versión simplificada para usar con una sola imagen (autodoble exposición)
     * Usa la misma imagen como base y overlay con tratamiento diferente
     */
    fun applyAutoDoubleExposure(
        image: Bitmap,
        mode: BlendMode = BlendMode.SCREEN,
        overlayTreatment: (Bitmap) -> Bitmap = { it }
    ): Bitmap {
        val treatedOverlay = overlayTreatment(image)
        return applyDoubleExposure(
            image,
            treatedOverlay,
            DoubleExposureParams(
                blendMode = mode,
                baseOpacity = 1.0f,
                overlayOpacity = 0.6f,
                overlayScale = 1.1f,
                overlayOffsetX = 0.05f
            )
        )
    }
}
