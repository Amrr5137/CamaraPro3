package com.example.miappcamarapro3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.example.miappcamarapro3.R
import com.google.android.material.tabs.TabLayout

/**
 * Panel principal de ajustes que contiene todos los controles de edición
 */
class AdjustmentPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Callbacks para cada tipo de ajuste
    var onExposureChanged: ((Float, Float, Float, Float, Float, Float) -> Unit)? = null
    var onColorChanged: ((Float, Float, Float, Float) -> Unit)? = null
    var onDetailChanged: ((Float, Float, Float) -> Unit)? = null
    var onCurveChanged: ((List<Pair<Float, Float>>) -> Unit)? = null

    // Vistas internas
    private lateinit var tabLayout: TabLayout
    private lateinit var contentContainer: FrameLayout

    // Sub-vistas
    private var exposureView: View? = null
    private var colorView: View? = null
    private var detailView: View? = null
    private var curveView: CurveEditorView? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_adjustment_panel, this, true)
        initializeViews()
    }

    private fun initializeViews() {
        tabLayout = findViewById(R.id.tabLayout)
        contentContainer = findViewById(R.id.contentContainer)

        // Configurar tabs
        tabLayout.addTab(tabLayout.newTab().setText("Luz"))
        tabLayout.addTab(tabLayout.newTab().setText("Color"))
        tabLayout.addTab(tabLayout.newTab().setText("Detalle"))
        tabLayout.addTab(tabLayout.newTab().setText("Curvas"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showTabContent(tab?.position ?: 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Mostrar primer tab por defecto
        showTabContent(0)
    }

    private fun showTabContent(position: Int) {
        contentContainer.removeAllViews()

        when (position) {
            0 -> showExposureControls()
            1 -> showColorControls()
            2 -> showDetailControls()
            3 -> showCurveControls()
        }
    }

    private fun showExposureControls() {
        if (exposureView == null) {
            exposureView =
                LayoutInflater.from(context).inflate(R.layout.view_exposure_controls, null)
            setupExposureListeners(exposureView!!)
        }
        contentContainer.addView(exposureView)
    }

    private fun showColorControls() {
        if (colorView == null) {
            colorView = LayoutInflater.from(context).inflate(R.layout.view_color_controls, null)
            setupColorListeners(colorView!!)
        }
        contentContainer.addView(colorView)
    }

    private fun showDetailControls() {
        if (detailView == null) {
            detailView = LayoutInflater.from(context).inflate(R.layout.view_detail_controls, null)
            setupDetailListeners(detailView!!)
        }
        contentContainer.addView(detailView)
    }

    private fun showCurveControls() {
        if (curveView == null) {
            curveView = CurveEditorView(context).apply {
                id = R.id.curveEditorView
                onCurveChanged = { curve ->
                    val points = curve.rgb.map { Pair(it.x, it.y) }
                    this@AdjustmentPanelView.onCurveChanged?.invoke(points)
                }
            }
        }
        contentContainer.addView(curveView)
    }

    private fun setupExposureListeners(view: View) {
        val sbExposure = view.findViewById<SeekBar>(R.id.sbExposure)
        val sbContrast = view.findViewById<SeekBar>(R.id.sbContrast)
        val sbHighlights = view.findViewById<SeekBar>(R.id.sbHighlights)
        val sbShadows = view.findViewById<SeekBar>(R.id.sbShadows)
        val sbWhites = view.findViewById<SeekBar>(R.id.sbWhites)
        val sbBlacks = view.findViewById<SeekBar>(R.id.sbBlacks)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onExposureChanged?.invoke(
                        ((sbExposure?.progress ?: 50) - 50) / 10f,
                        ((sbContrast?.progress ?: 50) - 50) * 2f,
                        ((sbHighlights?.progress ?: 50) - 50) * 2f,
                        ((sbShadows?.progress ?: 50) - 50) * 2f,
                        ((sbWhites?.progress ?: 50) - 50) * 2f,
                        ((sbBlacks?.progress ?: 50) - 50) * 2f
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbExposure?.setOnSeekBarChangeListener(listener)
        sbContrast?.setOnSeekBarChangeListener(listener)
        sbHighlights?.setOnSeekBarChangeListener(listener)
        sbShadows?.setOnSeekBarChangeListener(listener)
        sbWhites?.setOnSeekBarChangeListener(listener)
        sbBlacks?.setOnSeekBarChangeListener(listener)
    }

    private fun setupColorListeners(view: View) {
        val sbTemp = view.findViewById<SeekBar>(R.id.sbTemperature)
        val sbTint = view.findViewById<SeekBar>(R.id.sbTint)
        val sbVibrance = view.findViewById<SeekBar>(R.id.sbVibrance)
        val sbSaturation = view.findViewById<SeekBar>(R.id.sbSaturation)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onColorChanged?.invoke(
                        ((sbTemp?.progress ?: 50) - 50) * 2f,
                        ((sbTint?.progress ?: 50) - 50) * 2f,
                        ((sbVibrance?.progress ?: 50) - 50) * 2f,
                        ((sbSaturation?.progress ?: 50) - 50) * 2f
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbTemp?.setOnSeekBarChangeListener(listener)
        sbTint?.setOnSeekBarChangeListener(listener)
        sbVibrance?.setOnSeekBarChangeListener(listener)
        sbSaturation?.setOnSeekBarChangeListener(listener)
    }

    private fun setupDetailListeners(view: View) {
        val sbSharpness = view.findViewById<SeekBar>(R.id.sbSharpness)
        val sbClarity = view.findViewById<SeekBar>(R.id.sbClarity)
        val sbDenoise = view.findViewById<SeekBar>(R.id.sbDenoise)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onDetailChanged?.invoke(
                        (sbSharpness?.progress ?: 0).toFloat(),
                        (sbClarity?.progress ?: 0).toFloat(),
                        (sbDenoise?.progress ?: 0).toFloat()
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbSharpness?.setOnSeekBarChangeListener(listener)
        sbClarity?.setOnSeekBarChangeListener(listener)
        sbDenoise?.setOnSeekBarChangeListener(listener)
    }

    fun resetAll() {
        // Resetear todos los controles a valores por defecto
        exposureView?.let { view ->
            view.findViewById<SeekBar>(R.id.sbExposure)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbContrast)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbHighlights)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbShadows)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbWhites)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbBlacks)?.progress = 50
        }

        colorView?.let { view ->
            view.findViewById<SeekBar>(R.id.sbTemperature)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbTint)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbVibrance)?.progress = 50
            view.findViewById<SeekBar>(R.id.sbSaturation)?.progress = 50
        }

        detailView?.let { view ->
            view.findViewById<SeekBar>(R.id.sbSharpness)?.progress = 0
            view.findViewById<SeekBar>(R.id.sbClarity)?.progress = 0
            view.findViewById<SeekBar>(R.id.sbDenoise)?.progress = 0
        }

        curveView?.resetCurve()
    }
}
