package com.example.miappcamarapro3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.*
import com.example.miappcamarapro3.R
import kotlin.math.exp

/**
 * Vista personalizada para controles manuales de cámara
 */
class ManualControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Callbacks
    var onIsoChanged: ((Int) -> Unit)? = null
    var onShutterSpeedChanged: ((Long) -> Unit)? = null
    var onFocusChanged: ((Boolean, Float) -> Unit)? = null
    var onWbChanged: ((Int) -> Unit)? = null

    private lateinit var sbIso: SeekBar
    private lateinit var sbShutter: SeekBar
    private lateinit var sbFocus: SeekBar
    private lateinit var sbWb: SeekBar
    private lateinit var switchManualFocus: Switch

    private lateinit var tvIsoValue: TextView
    private lateinit var tvShutterValue: TextView
    private lateinit var tvFocusValue: TextView
    private lateinit var tvWbValue: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_manual_controls, this, true)
        initializeViews()
    }

    private fun initializeViews() {
        // Referencias (asumiendo layout definido)
        sbIso = findViewById(R.id.sbIso)
        sbShutter = findViewById(R.id.sbShutter)
        sbFocus = findViewById(R.id.sbFocus)
        sbWb = findViewById(R.id.sbWb)
        switchManualFocus = findViewById(R.id.switchManualFocus)

        tvIsoValue = findViewById(R.id.tvIsoValue)
        tvShutterValue = findViewById(R.id.tvShutterValue)
        tvFocusValue = findViewById(R.id.tvFocusValue)
        tvWbValue = findViewById(R.id.tvWbValue)

        // Listeners
        sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val iso = 100 + (progress * 50) // 100-5100 aprox
                tvIsoValue.text = "ISO $iso"
                if (fromUser) onIsoChanged?.invoke(iso)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Escala logarítmica: 1/4000s a 30s
                val ms = exp(progress / 10.0).toLong().coerceIn(1L, 30000L)
                tvShutterValue.text = formatShutterSpeed(ms)
                if (fromUser) onShutterSpeedChanged?.invoke(ms)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        switchManualFocus.setOnCheckedChangeListener { _, isChecked ->
            sbFocus.isEnabled = isChecked
            val distance = if (isChecked) sbFocus.progress / 100f else 0f
            tvFocusValue.text = if (isChecked) "MF ${(distance * 100).toInt()}%" else "AF"
            onFocusChanged?.invoke(isChecked, distance)
        }

        sbFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!switchManualFocus.isChecked) return
                val distance = progress / 100f
                tvFocusValue.text = "MF ${(distance * 100).toInt()}%"
                if (fromUser) onFocusChanged?.invoke(true, distance)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbWb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val kelvin = 2000 + (progress * 60) // 2000-8000K
                tvWbValue.text = "${kelvin}K"
                if (fromUser) onWbChanged?.invoke(kelvin)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun formatShutterSpeed(ms: Long): String {
        return when {
            ms >= 1000 -> "%.1fs".format(ms / 1000.0)
            ms >= 100 -> "${ms}ms"
            else -> "1/${(1000.0 / ms).toInt()}"
        }
    }

    fun setIsoRange(min: Int, max: Int) {
        sbIso.max = (max - min) / 50
    }

    fun setExposureRange(minMs: Long, maxMs: Long) {
        // Ajustar escala según rango
    }
}
