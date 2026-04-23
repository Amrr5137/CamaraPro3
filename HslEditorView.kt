package com.example.miappcamarapro3.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.miappcamarapro3.R
import com.example.miappcamarapro3.filters.AdvancedFilterProcessor

/**
 * Vista para ajustes HSL selectivos por rango de color
 */
class HslEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onHslChanged: ((Map<AdvancedFilterProcessor.ColorRange, AdvancedFilterProcessor.HslAdjustment>) -> Unit)? = null
    
    private val adjustments = mutableMapOf<AdvancedFilterProcessor.ColorRange, AdvancedFilterProcessor.HslAdjustment>()
    private var currentRange: AdvancedFilterProcessor.ColorRange = AdvancedFilterProcessor.ColorRange.RED
    
    private lateinit var sbHue: SeekBar
    private lateinit var sbSaturation: SeekBar
    private lateinit var sbLightness: SeekBar
    private lateinit var tvHueValue: TextView
    private lateinit var tvSatValue: TextView
    private lateinit var tvLightValue: TextView
    
    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_hsl_editor, this)
        initializeViews()
    }
    
    private fun initializeViews() {
        sbHue = findViewById(R.id.sbHslHue)
        sbSaturation = findViewById(R.id.sbHslSaturation)
        sbLightness = findViewById(R.id.sbHslLightness)
        tvHueValue = findViewById(R.id.tvHueValue)
        tvSatValue = findViewById(R.id.tvSatValue)
        tvLightValue = findViewById(R.id.tvLightValue)
        
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateAdjustment()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        
        sbHue.setOnSeekBarChangeListener(listener)
        sbSaturation.setOnSeekBarChangeListener(listener)
        sbLightness.setOnSeekBarChangeListener(listener)
    }
    
    fun selectColorRange(range: AdvancedFilterProcessor.ColorRange) {
        currentRange = range
        val adj = adjustments[range] ?: AdvancedFilterProcessor.HslAdjustment()
        
        sbHue.progress = ((adj.hueShift + 180) / 3.6).toInt()
        sbSaturation.progress = ((adj.saturationMultiplier - 0.5) * 100).toInt()
        sbLightness.progress = ((adj.lightnessMultiplier - 0.5) * 100).toInt()
        
        updateLabels()
    }
    
    private fun updateAdjustment() {
        val hue = (sbHue.progress * 3.6 - 180).toFloat()
        val sat = sbSaturation.progress / 100f + 0.5f
        val light = sbLightness.progress / 100f + 0.5f
        
        adjustments[currentRange] = AdvancedFilterProcessor.HslAdjustment(hue, sat, light)
        updateLabels()
        onHslChanged?.invoke(adjustments.toMap())
    }
    
    private fun updateLabels() {
        tvHueValue.text = "${(sbHue.progress * 3.6 - 180).toInt()}°"
        tvSatValue.text = "${(sbSaturation.progress + 50)}%"
        tvLightValue.text = "${(sbLightness.progress + 50)}%"
    }
    
    fun getAdjustments(): Map<AdvancedFilterProcessor.ColorRange, AdvancedFilterProcessor.HslAdjustment> = adjustments.toMap()

    fun resetAdjustments() {
        adjustments.clear()
        selectColorRange(currentRange)
    }
}


