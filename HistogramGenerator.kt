package com.example.miappcamarapro3.editing

import android.graphics.*
import androidx.core.graphics.withSave

/**
 * Genera histogramas RGB para análisis de imagen
 */
class HistogramGenerator {
    
    data class HistogramData(
        val red: IntArray,
        val green: IntArray,
        val blue: IntArray,
        val luminance: IntArray
    )
    
    fun generateHistogram(bitmap: Bitmap): HistogramData {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        val luminance = IntArray(256)
        
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            
            red[r]++
            green[g]++
            blue[b]++
            luminance[lum.coerceIn(0, 255)]++
        }
        
        return HistogramData(red, green, blue, luminance)
    }
    
    /**
     * Dibuja el histograma en un Canvas
     */
    fun drawHistogram(canvas: Canvas, data: HistogramData, bounds: Rect, 
                     showRgb: Boolean = true, showLuminance: Boolean = true) {
        val maxCount = maxOf(
            data.red.maxOrNull() ?: 1,
            data.green.maxOrNull() ?: 1,
            data.blue.maxOrNull() ?: 1,
            data.luminance.maxOrNull() ?: 1
        )
        
        val barWidth = bounds.width() / 256f
        
        canvas.withSave {
            translate(bounds.left.toFloat(), bounds.bottom.toFloat())
            scale(1f, -1f)
            
            if (showLuminance) {
                drawChannel(this, data.luminance, maxCount, barWidth, bounds.height(), Color.WHITE, 0.5f)
            }
            
            if (showRgb) {
                drawChannel(this, data.red, maxCount, barWidth, bounds.height(), Color.RED, 0.6f)
                drawChannel(this, data.green, maxCount, barWidth, bounds.height(), Color.GREEN, 0.6f)
                drawChannel(this, data.blue, maxCount, barWidth, bounds.height(), Color.BLUE, 0.6f)
            }
        }
    }
    
    private fun drawChannel(canvas: Canvas, values: IntArray, maxCount: Int, 
                           barWidth: Float, height: Int, color: Int, alpha: Float) {
        val paint = Paint().apply {
            this.color = color
            this.alpha = (alpha * 255).toInt()
            style = Paint.Style.FILL
        }
        
        for (i in values.indices) {
            val barHeight = (values[i].toFloat() / maxCount * height).coerceAtMost(height.toFloat())
            if (barHeight > 0) {
                canvas.drawRect(
                    i * barWidth,
                    0f,
                    (i + 1) * barWidth,
                    barHeight,
                    paint
                )
            }
        }
    }
}


