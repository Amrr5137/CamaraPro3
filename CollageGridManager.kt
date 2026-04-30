package com.example.miappcamarapro3.filters

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.withSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Crea collages y grids usando las 4 cámaras del Xiaomi 12 5G
 */
class CollageGridManager {

    enum class LayoutType {
        GRID_2x2,           // 4 fotos iguales
        GRID_1x3,           // 3 fotos horizontales
        GRID_3x1,           // 3 fotos verticales
        PIP_MAIN_SMALL,     // Principal grande + 3 pequeñas
        PIP_ULTRAWIDE_MAIN, // Ultra-wide arriba, principal abajo
        FILMSTRIP,          // Tira de película
        BEFORE_AFTER,       // Antes/después con divisor
        CIRCLE_CROP,        // 4 círculos
        DIAMOND             // Forma diamante
    }

    data class CollageResult(
        val bitmap: Bitmap,
        val layout: LayoutType,
        val cameraIds: List<String>
    )

    /**
     * Crea collage con fotos de las 4 cámaras
     */
    suspend fun createCollage(
        images: Map<String, Bitmap>, // cameraId -> bitmap
        layout: LayoutType,
        outputWidth: Int = 2160,
        outputHeight: Int = 2160,
        borderWidth: Int = 8,
        borderColor: Int = Color.WHITE,
        cornerRadius: Float = 0f
    ): CollageResult = withContext(Dispatchers.Default) {

        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Fondo
        canvas.drawColor(borderColor)

        if (images.isEmpty()) {
            val paint = Paint().apply {
                color = Color.RED
                textSize = 50f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Error: No hay imágenes", outputWidth / 2f, outputHeight / 2f, paint)
        }

        when (layout) {
            LayoutType.GRID_2x2 -> drawGrid2x2(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.GRID_1x3 -> drawGrid1x3(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.GRID_3x1 -> drawGrid3x1(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.PIP_MAIN_SMALL -> drawPipMainSmall(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.PIP_ULTRAWIDE_MAIN -> drawPipUltrawideMain(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.FILMSTRIP -> drawFilmstrip(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.BEFORE_AFTER -> drawBeforeAfter(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.CIRCLE_CROP -> drawCircleCrop(canvas, images, outputWidth, outputHeight, borderWidth)
            LayoutType.DIAMOND -> drawDiamond(canvas, images, outputWidth, outputHeight, borderWidth)
        }

        // Aplicar esquinas redondeadas si se solicita
        if (cornerRadius > 0) {
            return@withContext CollageResult(
                applyRoundedCorners(result, cornerRadius),
                layout,
                images.keys.toList()
            )
        }

        CollageResult(result, layout, images.keys.toList())
    }

    private fun drawGrid2x2(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val cellW = (width - border * 3) / 2
        val cellH = (height - border * 3) / 2
        
        val positions = listOf(
            Rect(border, border, border + cellW, border + cellH), // Top-left
            Rect(border * 2 + cellW, border, width - border, border + cellH), // Top-right
            Rect(border, border * 2 + cellH, border + cellW, height - border), // Bottom-left
            Rect(border * 2 + cellW, border * 2 + cellH, width - border, height - border) // Bottom-right
        )

        val sortedCameraIds = listOf("0", "1", "2", "3")
        positions.forEachIndexed { index, rect ->
            val cameraId = sortedCameraIds.getOrNull(index)
            images[cameraId]?.let { bitmap ->
                drawCroppedBitmap(canvas, bitmap, rect)
            } ?: run {
                // Si no hay imagen de esa cámara, intentar por índice en los valores disponibles
                images.values.toList().getOrNull(index)?.let { bitmap ->
                    drawCroppedBitmap(canvas, bitmap, rect)
                }
            }
        }
    }

    private fun drawGrid1x3(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val cellW = (width - border * 4) / 3
        val cellH = height - border * 2
        
        val positions = listOf(
            Rect(border, border, border + cellW, border + cellH),
            Rect(border * 2 + cellW, border, border * 2 + cellW * 2, border + cellH),
            Rect(border * 3 + cellW * 2, border, width - border, border + cellH)
        )

        images.values.take(3).forEachIndexed { index, bitmap ->
            positions.getOrNull(index)?.let { rect ->
                drawCroppedBitmap(canvas, bitmap, rect)
            }
        }
    }

    private fun drawGrid3x1(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val cellW = width - border * 2
        val cellH = (height - border * 4) / 3
        
        val positions = listOf(
            Rect(border, border, border + cellW, border + cellH),
            Rect(border, border * 2 + cellH, border + cellW, border * 2 + cellH * 2),
            Rect(border, border * 3 + cellH * 2, border + cellW, height - border)
        )

        images.values.take(3).forEachIndexed { index, bitmap ->
            positions.getOrNull(index)?.let { rect ->
                drawCroppedBitmap(canvas, bitmap, rect)
            }
        }
    }

    private fun drawPipMainSmall(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        // Principal grande (cámara 0)
        val mainRect = Rect(border, border, width - border, height * 2 / 3)
        images["0"]?.let { drawCroppedBitmap(canvas, it, mainRect) }

        // 3 pequeñas abajo
        val smallW = (width - border * 4) / 3
        val smallH = height * 1 / 3 - border * 2
        val topY = height * 2 / 3 + border

        listOf("2", "3", "1").forEachIndexed { index, cameraId ->
            val left = border + index * (smallW + border)
            val rect = Rect(left, topY, left + smallW, topY + smallH)
            images[cameraId]?.let { drawCroppedBitmap(canvas, it, rect) }
        }
    }

    private fun drawPipUltrawideMain(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        // Ultra-wide arriba (cámara 2)
        val topRect = Rect(border, border, width - border, height / 2 - border / 2)
        images["2"]?.let { drawCroppedBitmap(canvas, it, topRect) }

        // Principal abajo (cámara 0)
        val bottomRect = Rect(border, height / 2 + border / 2, width - border, height - border)
        images["0"]?.let { drawCroppedBitmap(canvas, it, bottomRect) }
    }

    private fun drawFilmstrip(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val frameH = (height - border * 5) / 4
        
        images.values.take(4).forEachIndexed { index, bitmap ->
            val top = border + index * (frameH + border)
            val rect = Rect(border, top, width - border, top + frameH)
            drawCroppedBitmap(canvas, bitmap, rect)
            
            // Añadir perforaciones de película
            drawFilmPerforations(canvas, rect)
        }
    }

    private fun drawFilmPerforations(canvas: Canvas, frameRect: Rect) {
        val paint = Paint().apply { color = Color.BLACK }
        val holeSize = frameRect.height() / 8f
        
        // Perforaciones izquierda
        for (i in 0..3) {
            val y = frameRect.top + frameRect.height() * (i + 1) / 5f
            canvas.drawCircle(frameRect.left.toFloat() + holeSize, y, holeSize / 2f, paint)
        }
        
        // Perforaciones derecha
        for (i in 0..3) {
            val y = frameRect.top + frameRect.height() * (i + 1) / 5f
            canvas.drawCircle(frameRect.right.toFloat() - holeSize, y, holeSize / 2f, paint)
        }
    }

    private fun drawBeforeAfter(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val splitX = width / 2
        
        // Izquierda: primera imagen
        val leftRect = Rect(border, border, splitX - border / 2, height - border)
        images.values.firstOrNull()?.let { drawCroppedBitmap(canvas, it, leftRect) }

        // Derecha: segunda imagen
        val rightRect = Rect(splitX + border / 2, border, width - border, height - border)
        images.values.drop(1).firstOrNull()?.let { drawCroppedBitmap(canvas, it, rightRect) }

        // Línea divisoria
        val linePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 4f
        }
        canvas.drawLine(splitX.toFloat(), border.toFloat(), splitX.toFloat(), (height - border).toFloat(), linePaint)
    }

    private fun drawCircleCrop(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val diameter = min(width, height) / 2 - border * 2
        val radius = diameter / 2
        
        val centers = listOf(
            PointF(border + radius.toFloat(), border + radius.toFloat()),
            PointF(width - border - radius.toFloat(), border + radius.toFloat()),
            PointF(border + radius.toFloat(), height - border - radius.toFloat()),
            PointF(width - border - radius.toFloat(), height - border - radius.toFloat())
        )

        images.values.take(4).forEachIndexed { index, bitmap ->
            centers.getOrNull(index)?.let { center ->
                val path = Path().apply {
                    addCircle(center.x, center.y, radius.toFloat(), Path.Direction.CCW)
                }
                canvas.withSave {
                    clipPath(path)
                    val rect = Rect(
                        (center.x - radius).toInt(),
                        (center.y - radius).toInt(),
                        (center.x + radius).toInt(),
                        (center.y + radius).toInt()
                    )
                    drawCroppedBitmap(canvas, bitmap, rect)
                }
            }
        }
    }

    private fun drawDiamond(
        canvas: Canvas,
        images: Map<String, Bitmap>,
        width: Int,
        height: Int,
        border: Int
    ) {
        val centerX = width / 2f
        val centerY = height / 2f
        val halfSize = min(width, height) / 2f - border

        val diamondPath = Path().apply {
            moveTo(centerX, centerY - halfSize)
            lineTo(centerX + halfSize, centerY)
            lineTo(centerX, centerY + halfSize)
            lineTo(centerX - halfSize, centerY)
            close()
        }

        canvas.withSave {
            clipPath(diamondPath)
            images.values.firstOrNull()?.let { bitmap ->
                drawCroppedBitmap(canvas, bitmap, Rect(0, 0, width, height))
            }
        }
    }

    private fun drawCroppedBitmap(canvas: Canvas, bitmap: Bitmap, dstRect: Rect) {
        val srcAspect = bitmap.width.toFloat() / bitmap.height
        val dstAspect = dstRect.width().toFloat() / dstRect.height()

        val srcRect = if (srcAspect > dstAspect) {
            // Imagen más ancha que destino: recortar laterales
            val newWidth = (bitmap.height * dstAspect).toInt()
            val xOffset = (bitmap.width - newWidth) / 2
            Rect(xOffset, 0, xOffset + newWidth, bitmap.height)
        } else {
            // Imagen más alta que destino: recortar arriba/abajo
            val newHeight = (bitmap.width / dstAspect).toInt()
            val yOffset = (bitmap.height - newHeight) / 2
            Rect(0, yOffset, bitmap.width, yOffset + newHeight)
        }

        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
    }

    private fun applyRoundedCorners(bitmap: Bitmap, radius: Float): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val path = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CCW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Añade etiquetas de cámara al collage
     */
    fun addCameraLabels(
        canvas: Canvas,
        cameraIds: List<String>,
        positions: List<Rect>,
        labelColor: Int = Color.WHITE
    ) {
        val paint = Paint().apply {
            color = labelColor
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val labels = mapOf(
            "0" to "Principal",
            "1" to "Frontal",
            "2" to "Ultra-wide",
            "3" to "Telemacro"
        )

        cameraIds.forEachIndexed { index, id ->
            positions.getOrNull(index)?.let { rect ->
                val label = labels[id] ?: "Cam $id"
                canvas.drawText(label, rect.left + 10f, rect.top + 30f, paint)
            }
        }
    }
}