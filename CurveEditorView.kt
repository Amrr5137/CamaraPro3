package com.example.miappcamarapro3.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.miappcamarapro3.filters.AdvancedFilterProcessor
import kotlin.math.pow

/**
 * Vista interactiva para editar curvas de tono tipo Lightroom
 */
class CurveEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCurveChanged: ((AdvancedFilterProcessor.ToneCurve) -> Unit)? = null
    
    private var currentChannel = Channel.RGB
    val curve = AdvancedFilterProcessor.ToneCurve()
    private val controlPoints = mutableListOf<PointF>()
    private var selectedPoint: PointF? = null
    
    private val gridPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 1f
        alpha = 100
    }
    
    private val curvePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    
    private val gridSize = 4
    
    enum class Channel { RGB, RED, GREEN, BLUE }
    
    init {
        // Punto inicial
        controlPoints.add(PointF(0f, 0f))
        controlPoints.add(PointF(1f, 1f))
    }
    
    fun setChannel(channel: Channel) {
        currentChannel = channel
        controlPoints.clear()
        
        // Cargar puntos existentes del canal
        val existingCurve = when(channel) {
            Channel.RED -> curve.red
            Channel.GREEN -> curve.green
            Channel.BLUE -> curve.blue
            else -> curve.rgb
        }
        
        if (existingCurve.isNotEmpty()) {
            existingCurve.forEach { controlPoints.add(PointF(it.x, it.y)) }
        } else {
            controlPoints.add(PointF(0f, 0f))
            controlPoints.add(PointF(1f, 1f))
        }
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // Fondo
        canvas.drawColor(Color.BLACK)
        
        // Grid
        for (i in 0..gridSize) {
            val x = i * width / gridSize
            val y = i * height / gridSize
            canvas.drawLine(x, 0f, x, height, gridPaint)
            canvas.drawLine(0f, y, width, y, gridPaint)
        }
        
        // Curva diagonal de referencia
        val diagPaint = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 1f
        }
        canvas.drawLine(0f, height, width, 0f, diagPaint)
        
        // Dibujar curva
        curvePaint.color = when(currentChannel) {
            Channel.RED -> Color.RED
            Channel.GREEN -> Color.GREEN
            Channel.BLUE -> Color.BLUE
            else -> Color.WHITE
        }
        
        val path = Path()
        val sortedPoints = controlPoints.sortedBy { it.x }
        
        if (sortedPoints.isNotEmpty()) {
            path.moveTo(sortedPoints[0].x * width, (1 - sortedPoints[0].y) * height)
            
            for (i in 1 until sortedPoints.size) {
                val prev = sortedPoints[i - 1]
                val curr = sortedPoints[i]
                path.lineTo(curr.x * width, (1 - curr.y) * height)
            }
        }
        
        canvas.drawPath(path, curvePaint)
        
        // Dibujar puntos de control
        for (point in controlPoints) {
            val px = point.x * width
            val py = (1 - point.y) * height
            canvas.drawCircle(px, py, 10f, pointPaint)
            
            if (point == selectedPoint) {
                canvas.drawCircle(px, py, 14f, Paint().apply {
                    color = Color.CYAN
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                })
            }
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x / width).coerceIn(0f, 1f)
        val y = (1 - event.y / height).coerceIn(0f, 1f)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                performClick()
                selectedPoint = findNearestPoint(x, y)
                if (selectedPoint == null) {
                    selectedPoint = PointF(x, y)
                    controlPoints.add(selectedPoint!!)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                selectedPoint?.let {
                    it.x = x
                    it.y = y
                    updateCurve()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                selectedPoint = null
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    
    private fun findNearestPoint(x: Float, y: Float): PointF? {
        val threshold = 0.05f
        return controlPoints.find { 
            kotlin.math.abs(it.x - x) < threshold && kotlin.math.abs(it.y - y) < threshold 
        }
    }
    
    private fun updateCurve() {
        val sorted = controlPoints.sortedBy { it.x }
        val curvePoints = sorted.map { 
            AdvancedFilterProcessor.CurvePoint(it.x, it.y) 
        }
        
        when(currentChannel) {
            Channel.RED -> curve.red.apply { clear(); addAll(curvePoints) }
            Channel.GREEN -> curve.green.apply { clear(); addAll(curvePoints) }
            Channel.BLUE -> curve.blue.apply { clear(); addAll(curvePoints) }
            else -> curve.setRgbCurve(curvePoints)
        }
        
        onCurveChanged?.invoke(curve)
    }
    
    fun resetCurve() {
        controlPoints.clear()
        controlPoints.add(PointF(0f, 0f))
        controlPoints.add(PointF(1f, 1f))
        updateCurve()
        invalidate()
    }
}

