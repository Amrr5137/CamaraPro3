package com.example.miappcamarapro3.filters

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procesador de efectos Light Leaks (fugas de luz analógicas)
 * Simula las fugas de luz de cámaras analógicas
 */
class LightLeaksProcessor {

    enum class LeakType {
        ORANGE_STREAK,      // Raya naranja clásica (luz del sol)
        RED_HALO,           // Halo rojo (puerta de cámara abierta)
        BLUE_CORNER,        // Esquina azul (luz fría)
        GREEN_TINT,         // Tintura verde (película expuesta)
        PURPLE_FLARE,       // Destello púrpura (lente)
        RAINBOW_STREAK,     // Raya arcoíris (prisma)
        RANDOM_SPOTS,       // Manchas aleatorias
        FILM_BURN           // Quemadura de película
    }

    data class LightLeakParams(
        val type: LeakType = LeakType.ORANGE_STREAK,
        val intensity: Float = 0.5f,           // 0.0 a 1.0
        val position: LeakPosition = LeakPosition.TOP_LEFT,
        val size: Float = 0.5f,              // Tamaño relativo (0-1)
        val angle: Float = 45f,              // Ángulo en grados
        val softness: Float = 0.7f,          // Suavidad del borde
        val animate: Boolean = false,        // Si tiene animación sutil
        val seed: Long = System.currentTimeMillis() // Semilla para random
    )

    enum class LeakPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
        RANDOM
    }

    private val random = Random(0)

    /**
     * Aplica efecto de fuga de luz a la imagen
     */
    fun applyLightLeak(bitmap: Bitmap, params: LightLeakParams): Bitmap {
        random.apply { // Reset random with seed if needed
            // Crear capa de fuga
            val leakLayer = createLeakLayer(bitmap.width, bitmap.height, params)

            // Combinar con imagen original
            return blendLeakWithImage(bitmap, leakLayer, params)
        }
    }

    /**
     * Crea la capa de fuga de luz
     */
    private fun createLeakLayer(width: Int, height: Int, params: LightLeakParams): Bitmap {
        val leakBitmap = createBitmap(width, height)
        val canvas = Canvas(leakBitmap)

        when (params.type) {
            LeakType.ORANGE_STREAK -> drawOrangeStreak(canvas, width, height, params)
            LeakType.RED_HALO -> drawRedHalo(canvas, width, height, params)
            LeakType.BLUE_CORNER -> drawBlueCorner(canvas, width, height, params)
            LeakType.GREEN_TINT -> drawGreenTint(canvas, width, height, params)
            LeakType.PURPLE_FLARE -> drawPurpleFlare(canvas, width, height, params)
            LeakType.RAINBOW_STREAK -> drawRainbowStreak(canvas, width, height, params)
            LeakType.RANDOM_SPOTS -> drawRandomSpots(canvas, width, height, params)
            LeakType.FILM_BURN -> drawFilmBurn(canvas, width, height, params)
        }

        return leakBitmap
    }

    private fun drawOrangeStreak(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val (startX, startY) = getPositionCoordinates(params.position, width, height)
        val angleRad = params.angle.toDouble() * (PI / 180.0)
        val length = maxOf(width, height) * params.size * 2

        // Gradiente lineal para la raya
        val gradient = LinearGradient(
            startX, startY,
            (startX + cos(angleRad) * length).toFloat(),
            (startY + sin(angleRad) * length).toFloat(),
            intArrayOf(
                Color.argb((255 * params.intensity).toInt(), 255, 140, 0),
                Color.argb((200 * params.intensity).toInt(), 255, 100, 0),
                Color.argb((100 * params.intensity).toInt(), 255, 80, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        paint.shader = gradient

        // Dibujar raya con ancho variable
        val path = Path()
        val perpAngle = angleRad + PI / 2
        val streakWidth = minOf(width, height) * params.size * 0.3f

        path.moveTo(
            (startX + cos(perpAngle) * streakWidth).toFloat(),
            (startY + sin(perpAngle) * streakWidth).toFloat()
        )
        path.lineTo(
            (startX - cos(perpAngle) * streakWidth).toFloat(),
            (startY - sin(perpAngle) * streakWidth).toFloat()
        )
        path.lineTo(
            (startX - cos(perpAngle) * streakWidth + cos(angleRad) * length).toFloat(),
            (startY - sin(perpAngle) * streakWidth + sin(angleRad) * length).toFloat()
        )
        path.lineTo(
            (startX + cos(perpAngle) * streakWidth + cos(angleRad) * length).toFloat(),
            (startY + sin(perpAngle) * streakWidth + sin(angleRad) * length).toFloat()
        )
        path.close()

        canvas.drawPath(path, paint)

        // Añadir brillo central
        val glowPaint = Paint().apply {
            color = Color.argb((150 * params.intensity).toInt(), 255, 200, 100)
            maskFilter = BlurMaskFilter(streakWidth * 0.5f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(startX, startY, streakWidth * 0.8f, glowPaint)
    }

    private fun drawRedHalo(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = maxOf(width, height) * params.size

        // Halo exterior rojo
        val haloPaint = Paint().apply {
            shader = RadialGradient(
                centerX, centerY, maxRadius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((100 * params.intensity).toInt(), 255, 0, 0),
                    Color.argb((200 * params.intensity).toInt(), 255, 50, 50),
                    Color.argb((50 * params.intensity).toInt(), 255, 100, 100),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 0.7f, 0.9f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawCircle(centerX, centerY, maxRadius, haloPaint)

        // Líneas de fuga radiales
        val linePaint = Paint().apply {
            color = Color.argb((80 * params.intensity).toInt(), 255, 100, 100)
            strokeWidth = 3f
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        }

        for (i in 0 until 8) {
            val angle = (i * 45) * PI / 180
            canvas.drawLine(
                centerX, centerY,
                (centerX + cos(angle) * maxRadius).toFloat(),
                (centerY + sin(angle) * maxRadius).toFloat(),
                linePaint
            )
        }
    }

    private fun drawBlueCorner(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        val (cornerX, cornerY) = when (params.position) {
            LeakPosition.TOP_LEFT -> Pair(0f, 0f)
            LeakPosition.TOP_RIGHT -> Pair(width.toFloat(), 0f)
            LeakPosition.BOTTOM_LEFT -> Pair(0f, height.toFloat())
            LeakPosition.BOTTOM_RIGHT -> Pair(width.toFloat(), height.toFloat())
            else -> Pair(0f, 0f)
        }

        val radius = minOf(width, height) * params.size

        val gradient = RadialGradient(
            cornerX, cornerY, radius,
            intArrayOf(
                Color.argb((200 * params.intensity).toInt(), 100, 150, 255),
                Color.argb((150 * params.intensity).toInt(), 150, 180, 255),
                Color.argb((50 * params.intensity).toInt(), 200, 220, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        val paint = Paint().apply { shader = gradient }
        canvas.drawCircle(cornerX, cornerY, radius, paint)
    }

    private fun drawGreenTint(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        // Tintura verde tipo película expuesta
        val paint = Paint().apply {
            color = Color.argb((80 * params.intensity).toInt(), 100, 255, 150)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(minOf(width, height) * 0.3f, BlurMaskFilter.Blur.NORMAL)
        }

        // Manchas irregulares
        val random = Random(params.seed)
        for (i in 0..5) {
            val x = random.nextInt(width).toFloat()
            val y = random.nextInt(height).toFloat()
            val radius = random.nextInt(minOf(width, height) / 4).toFloat() * params.size
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    private fun drawPurpleFlare(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        val centerX = width / 2f
        val centerY = height / 2f

        // Destello hexagonal (simulando apertura de lente)
        val flarePaint = Paint().apply {
            color = Color.argb((180 * params.intensity).toInt(), 200, 100, 255)
            maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
        }

        // Dibujar hexágonos concéntricos
        for (i in 3 downTo 0) {
            val radius = (minOf(width, height) * params.size * (0.2f + i * 0.15f))
            val hexPath = createHexagonPath(centerX, centerY, radius)
            flarePaint.alpha = (100 * params.intensity / (i + 1)).toInt()
            canvas.drawPath(hexPath, flarePaint)
        }

        // Rayos de destello
        val rayPaint = Paint().apply {
            color = Color.argb((120 * params.intensity).toInt(), 220, 150, 255)
            strokeWidth = 2f
        }

        for (i in 0 until 12) {
            val angle = (i * 30) * PI / 180
            canvas.drawLine(
                centerX, centerY,
                (centerX + cos(angle) * maxOf(width, height)).toFloat(),
                (centerY + sin(angle) * maxOf(width, height)).toFloat(),
                rayPaint
            )
        }
    }

    private fun drawRainbowStreak(
        canvas: Canvas,
        width: Int,
        height: Int,
        params: LightLeakParams
    ) {
        val (startX, startY) = getPositionCoordinates(params.position, width, height)
        val angleRad = params.angle.toDouble() * (PI / 180.0)
        val length = maxOf(width, height) * params.size * 2

        val colors = intArrayOf(
            Color.argb((200 * params.intensity).toInt(), 255, 0, 0),
            Color.argb((200 * params.intensity).toInt(), 255, 165, 0),
            Color.argb((200 * params.intensity).toInt(), 255, 255, 0),
            Color.argb((200 * params.intensity).toInt(), 0, 255, 0),
            Color.argb((200 * params.intensity).toInt(), 0, 0, 255),
            Color.argb((200 * params.intensity).toInt(), 75, 0, 130),
            Color.argb((200 * params.intensity).toInt(), 238, 130, 238),
            Color.TRANSPARENT
        )

        val positions = floatArrayOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f, 1f)

        val gradient = LinearGradient(
            startX, startY,
            (startX + cos(angleRad) * length).toFloat(),
            (startY + sin(angleRad) * length).toFloat(),
            colors,
            positions,
            Shader.TileMode.CLAMP
        )

        val paint = Paint().apply {
            shader = gradient
            maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        }

        val path = Path()
        val streakWidth = minOf(width, height) * params.size * 0.2f
        val perpAngle = angleRad + PI / 2

        path.moveTo(
            (startX + cos(perpAngle) * streakWidth).toFloat(),
            (startY + sin(perpAngle) * streakWidth).toFloat()
        )
        path.lineTo(
            (startX - cos(perpAngle) * streakWidth).toFloat(),
            (startY - sin(perpAngle) * streakWidth).toFloat()
        )
        path.lineTo(
            (startX - cos(perpAngle) * streakWidth + cos(angleRad) * length).toFloat(),
            (startY - sin(perpAngle) * streakWidth + sin(angleRad) * length).toFloat()
        )
        path.lineTo(
            (startX + cos(perpAngle) * streakWidth + cos(angleRad) * length).toFloat(),
            (startY + sin(perpAngle) * streakWidth + sin(angleRad) * length).toFloat()
        )
        path.close()

        canvas.drawPath(path, paint)
    }

    private fun drawRandomSpots(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        val random = Random(params.seed)
        val spotCount = (10 * params.size).toInt().coerceAtLeast(3)

        for (i in 0 until spotCount) {
            val x = random.nextInt(width).toFloat()
            val y = random.nextInt(height).toFloat()
            val radius = random.nextInt(50).toFloat() + 20f

            val colors = arrayOf(
                Color.argb((200 * params.intensity).toInt(), 255, 200, 100),
                Color.argb((200 * params.intensity).toInt(), 255, 100, 100),
                Color.argb((200 * params.intensity).toInt(), 100, 200, 255)
            )

            val paint = Paint().apply {
                color = colors[random.nextInt(colors.size)]
                maskFilter = BlurMaskFilter(radius * 0.5f, BlurMaskFilter.Blur.NORMAL)
            }

            canvas.drawCircle(x, y, radius, paint)
        }
    }

    private fun drawFilmBurn(canvas: Canvas, width: Int, height: Int, params: LightLeakParams) {
        // Simular quemadura de película en los bordes
        val paint = Paint().apply {
            style = Paint.Style.FILL
        }

        // Gradiente desde los bordes hacia adentro
        val edgeWidth = minOf(width, height) * params.size * 0.3f

        // Borde superior
        val topGradient = LinearGradient(
            0f, 0f, 0f, edgeWidth,
            intArrayOf(
                Color.argb((250 * params.intensity).toInt(), 255, 100, 50),
                Color.argb((150 * params.intensity).toInt(), 255, 150, 100),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        paint.shader = topGradient
        canvas.drawRect(0f, 0f, width.toFloat(), edgeWidth, paint)

        // Borde inferior
        val bottomGradient = LinearGradient(
            0f, height.toFloat(), 0f, height - edgeWidth,
            intArrayOf(
                Color.argb((250 * params.intensity).toInt(), 255, 100, 50),
                Color.argb((150 * params.intensity).toInt(), 255, 150, 100),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        paint.shader = bottomGradient
        canvas.drawRect(0f, height - edgeWidth, width.toFloat(), height.toFloat(), paint)

        // Líneas de quemadura verticales aleatorias
        val random = Random(params.seed)
        val linePaint = Paint().apply {
            color = Color.argb((180 * params.intensity).toInt(), 255, 120, 80)
            strokeWidth = 2f
            maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        }

        for (i in 0..3) {
            val x = random.nextInt(width).toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
    }

    private fun createHexagonPath(cx: Float, cy: Float, radius: Float): Path {
        val path = Path()
        for (i in 0 until 6) {
            val angle = (i * 60 - 30) * PI / 180
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return path
    }

    private fun getPositionCoordinates(
        position: LeakPosition,
        width: Int,
        height: Int
    ): Pair<Float, Float> {
        return when (position) {
            LeakPosition.TOP_LEFT -> Pair(0f, 0f)
            LeakPosition.TOP_CENTER -> Pair(width / 2f, 0f)
            LeakPosition.TOP_RIGHT -> Pair(width.toFloat(), 0f)
            LeakPosition.CENTER_LEFT -> Pair(0f, height / 2f)
            LeakPosition.CENTER -> Pair(width / 2f, height / 2f)
            LeakPosition.CENTER_RIGHT -> Pair(width.toFloat(), height / 2f)
            LeakPosition.BOTTOM_LEFT -> Pair(0f, height.toFloat())
            LeakPosition.BOTTOM_CENTER -> Pair(width / 2f, height.toFloat())
            LeakPosition.BOTTOM_RIGHT -> Pair(width.toFloat(), height.toFloat())
            LeakPosition.RANDOM -> Pair(
                Random.nextInt(width).toFloat(),
                Random.nextInt(height).toFloat()
            )
        }
    }

    private fun blendLeakWithImage(
        original: Bitmap,
        leakLayer: Bitmap,
        params: LightLeakParams
    ): Bitmap {
        val result = createBitmap(original.width, original.height)
        val canvas = Canvas(result)

        // Dibujar imagen original
        canvas.drawBitmap(original, 0f, 0f, null)

        // Aplicar fuga con blend mode SCREEN para efecto luminoso
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            alpha = (params.intensity * 255).toInt()
        }

        canvas.drawBitmap(leakLayer, 0f, 0f, paint)

        leakLayer.recycle()
        return result
    }

    /**
     * Aplica múltiples fugas de luz para efecto analógico completo
     */
    fun applyAnalogFilmEffect(bitmap: Bitmap, intensity: Float = 0.4f): Bitmap {
        var result = bitmap

        // Añadir fuga naranja sutil
        result = applyLightLeak(
            result, LightLeakParams(
                type = LeakType.ORANGE_STREAK,
                intensity = intensity * 0.6f,
                position = LeakPosition.TOP_LEFT,
                angle = 35f,
                size = 0.7f
            )
        )

        // Añadir halo rojo ligero
        result = applyLightLeak(
            result, LightLeakParams(
                type = LeakType.RED_HALO,
                intensity = intensity * 0.3f,
                position = LeakPosition.CENTER,
                size = 0.8f
            )
        )

        // Añadir quemadura de película en bordes
        result = applyLightLeak(
            result, LightLeakParams(
                type = LeakType.FILM_BURN,
                intensity = intensity * 0.5f,
                size = 0.3f
            )
        )

        return result
    }
}
