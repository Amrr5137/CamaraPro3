package com.example.miappcamarapro3

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.miappcamarapro3.camara.HdrBracketingCapture
import com.example.miappcamarapro3.camara.ManualModeController
import com.example.miappcamarapro3.camara.RawCaptureManager
import com.example.miappcamarapro3.editing.HistogramGenerator
import com.example.miappcamarapro3.filters.AdvancedFilterProcessor
import com.example.miappcamarapro3.filters.FilterProcessor
import com.example.miappcamarapro3.filters.FilterType
import com.example.miappcamarapro3.filters.GpuFilterRenderer
import com.bumptech.glide.Glide
import androidx.appcompat.app.AlertDialog
import com.example.miappcamarapro3.filters.AstrophotographyManager
import com.example.miappcamarapro3.filters.CollageGridManager
import com.example.miappcamarapro3.filters.TimeLapseManager
import com.example.miappcamarapro3.utils.ImageSaver
import com.example.miappcamarapro3.filters.LightLeaksProcessor
import com.example.miappcamarapro3.ui.AdjustmentPanelView
import com.example.miappcamarapro3.ui.CameraGLSurfaceView
import com.example.miappcamarapro3.ui.CurveEditorView
import com.example.miappcamarapro3.ui.ManualControlsView
import com.example.miappcamarapro3.ui.GalleryAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiCameraPro"
        private const val REQUEST_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    // Modos de operación
    enum class CameraMode { AUTO, MANUAL, RAW, HDR }

    // Views
    private lateinit var glSurfaceView: CameraGLSurfaceView
    private lateinit var textureView: TextureView
    private lateinit var manualControls: ManualControlsView
    private lateinit var cameraSelector: Spinner
    private lateinit var tvCameraInfo: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var btnModeAuto: Button
    private lateinit var btnModeManual: Button
    private lateinit var btnModeRaw: Button
    private lateinit var btnModeHdr: Button
    private lateinit var btnCapture: ImageButton
    private lateinit var btnMultiCapture: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnFlash: Button

    private lateinit var advancedEditingPanel: LinearLayout
    private lateinit var editingPreview: ImageView
    private lateinit var btnApplyEdits: Button
    private lateinit var btnResetEdits: Button

    // Camera2
    private lateinit var cameraManager: CameraManager
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    private var currentCameraId: String = "0"
    private var availableCameras = listOf<CameraInfo>()
    private var currentMode = CameraMode.AUTO
    private var isFlashSupported = false
    private var currentFlashMode = CaptureRequest.FLASH_MODE_OFF

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val cameraOpenCloseLock = Mutex()

    private val imageReaderListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        val cameraId = cameraDevice?.id ?: "0"
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        processCapturedImage(bytes, cameraId)
    }

    // Managers especializados
    private var rawCaptureManager: RawCaptureManager? = null
    private var hdrCaptureManager: HdrBracketingCapture? = null
    private var manualController: ManualModeController? = null
    private var gpuRenderer: GpuFilterRenderer? = null
    private var timeLapseManager: TimeLapseManager? = null
    private var astroManager: AstrophotographyManager? = null
    private lateinit var collageManager: CollageGridManager
    private lateinit var imageSaver: ImageSaver

    // Botones nuevos
    private lateinit var btnTimeLapse: Button
    private lateinit var btnAstro: Button
    private lateinit var btnCollage: Button

    // Filtros
    private lateinit var filterProcessor: FilterProcessor
    private var currentFilter = FilterType.NORMAL

    // Estado
    private var isProcessing = false
    private var setupJob: Job? = null
    private var lastCameraId: String? = null
    private var isCameraReady = false
    private var isActivityResumed = false
    private var lastCapturedFile: File? = null
    private var cameraSetupJob: Job? = null
    private var lastCaptureResult: TotalCaptureResult? = null

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastCaptureResult = result
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRendererRotation()

        glSurfaceView.requestLayout()
        
        runOnUiThread {
            findViewById<LinearLayout>(R.id.topBarLayout)?.bringToFront()
            findViewById<LinearLayout>(R.id.bottomControls)?.bringToFront()
        }
    }

    private fun updateRendererRotation() {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        
        gpuRenderer?.screenRotation = degrees
        Log.d(TAG, "Renderer rotation updated: $degrees degrees")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configurar pantalla completa antes de setContentView
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.e("MultiCameraPro", "Error setting up window flags", e)
        }

        setContentView(R.layout.activity_main)

        imageSaver = ImageSaver(this)
        initializeViews()
        initializeAdvancedEditing()
        initializeAdditionalComponents()
        updateGalleryThumbnail()
        
        updateRendererRotation()
        findViewById<LinearLayout>(R.id.topBarLayout)?.bringToFront()
        findViewById<HorizontalScrollView>(R.id.filterButtonsScroll)?.bringToFront()
        findViewById<LinearLayout>(R.id.bottomControls)?.bringToFront()
        findViewById<TextView>(R.id.tvCameraInfo)?.bringToFront()

        checkPermissions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Usamos un try-catch para evitar cualquier crash por el insets controller
            try {
                val currentWindow = this.window
                if (currentWindow != null) {
                    val controller = WindowCompat.getInsetsController(currentWindow, currentWindow.decorView)
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                Log.e("MultiCameraPro", "Error hiding system UI", e)
            }
        }
    }

    private fun initializeViews() {
        Log.d(TAG, "Initializing views...")

        // Aplicar padding para evitar el notch/barra de estado en los controles superiores
        val topBarLayout = findViewById<View>(R.id.topBarLayout)
        topBarLayout?.let { layout ->
            ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets.displayCutout?.safeInsetTop ?: 0
                } else 0
                
                val finalTopPadding = maxOf(bars.top, cutout)
                v.setPadding(v.paddingLeft, finalTopPadding, v.paddingRight, v.paddingBottom)
                insets
            }
        }

        // Referencias
        glSurfaceView = findViewById(R.id.glSurfaceView)
        textureView = findViewById(R.id.textureView)
        manualControls = findViewById(R.id.manualControls)
        cameraSelector = findViewById(R.id.cameraSelector)
        tvCameraInfo = findViewById(R.id.tvCameraInfo)
        progressBar = findViewById(R.id.progressBar)

        btnModeAuto = findViewById(R.id.btnModeAuto)
        btnModeManual = findViewById(R.id.btnModeManual)
        btnModeRaw = findViewById(R.id.btnModeRaw)
        btnModeHdr = findViewById(R.id.btnModeHdr)
        btnCapture = findViewById(R.id.btnCapture)
        btnMultiCapture = findViewById(R.id.btnMultiCapture)
        btnGallery = findViewById(R.id.btnGallery)
        btnFlash = findViewById(R.id.btnFlash)

        Log.d(TAG, "Views initialized, setting up listeners...")

        filterProcessor = FilterProcessor()

        // Gallery / Open Edit
        btnGallery.setOnClickListener {
            showGalleryBottomSheet()
        }

        // Modos
        btnModeAuto.setOnClickListener {
            Log.d("BUTTON_DEBUG", "Button clicked: AUTO")
            setMode(CameraMode.AUTO)
        }
        btnModeManual.setOnClickListener {
            Log.d("BUTTON_DEBUG", "Button clicked: MANUAL")
            setMode(CameraMode.MANUAL)
        }
        btnModeRaw.setOnClickListener {
            Log.d("BUTTON_DEBUG", "Button clicked: RAW")
            setMode(CameraMode.RAW)
        }
        btnModeHdr.setOnClickListener {
            Log.d("BUTTON_DEBUG", "Button clicked: HDR")
            setMode(CameraMode.HDR)
        }

        // Captura
        btnCapture.setOnClickListener {
            Log.d(TAG, "Capture button clicked")
            capturePhoto()
        }
        btnMultiCapture.setOnClickListener {
            Log.d(TAG, "Multi capture button clicked")
            captureAllCameras()
        }

        btnFlash.setOnClickListener {
            toggleFlash()
        }

        // Filtros GPU
        setupGpuFilterButtons()
        updateFilterButtons() // Set initial state

        // Controles manuales
        setupManualControls()

        // GLSurfaceView
        setupGpuPreview()

        // Make sure buttons are clickable and on top
        btnModeAuto.isEnabled = true
        btnModeAuto.isClickable = true
        btnModeManual.isEnabled = true
        btnModeManual.isClickable = true
        btnModeRaw.isEnabled = true
        btnModeRaw.isClickable = true
        btnModeHdr.isEnabled = true
        btnModeHdr.isClickable = true
        btnCapture.isEnabled = true
        btnCapture.isClickable = true
        btnMultiCapture.isEnabled = true
        btnMultiCapture.isClickable = true

        // Ensure filter buttons are enabled
        val filterIds = arrayOf(
            R.id.btnFilterNormal, R.id.btnFilterBW, R.id.btnFilterSepia, R.id.btnFilterNegative,
            R.id.btnFilterIR, R.id.btnFilterNight, R.id.btnFilterThermal, R.id.btnFilterSketch
        )
        filterIds.forEach { id ->
            try {
                findViewById<Button>(id)?.apply {
                    isEnabled = true
                    isClickable = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling filter button $id", e)
            }
        }

        // Asegurar que los contenedores de controles estén al frente
        findViewById<LinearLayout>(R.id.topBarLayout)?.bringToFront()
        findViewById<HorizontalScrollView>(R.id.filterButtonsScroll)?.bringToFront()
        findViewById<LinearLayout>(R.id.bottomControls)?.bringToFront()

        Log.d(TAG, "View initialization complete")
    }

    private fun toggleFlash() {
        if (!isFlashSupported) {
            Toast.makeText(this, "Flash no soportado en esta cámara", Toast.LENGTH_SHORT).show()
            return
        }

        currentFlashMode = when (currentFlashMode) {
            CaptureRequest.FLASH_MODE_OFF -> CaptureRequest.FLASH_MODE_TORCH
            CaptureRequest.FLASH_MODE_TORCH -> CaptureRequest.FLASH_MODE_OFF
            else -> CaptureRequest.FLASH_MODE_OFF
        }

        updateFlashButtonUI()
        updateCaptureRequest()
    }

    private fun updateFlashButtonUI() {
        btnFlash.text = if (currentFlashMode == CaptureRequest.FLASH_MODE_TORCH) "LUZ ON" else "LUZ OFF"
        btnFlash.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (currentFlashMode == CaptureRequest.FLASH_MODE_TORCH) 0xFFFFCC00.toInt() else 0xFFFF0000.toInt() // ROJO si está apagado para debug
        )
        btnFlash.setTextColor(if (currentFlashMode == CaptureRequest.FLASH_MODE_TORCH) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun updateCaptureRequest() {
        when (currentMode) {
            CameraMode.MANUAL -> {
                manualController?.setFlashMode(currentFlashMode)
            }
            CameraMode.RAW -> {
                previewSurface?.let { rawCaptureManager?.setFlashMode(currentFlashMode, it) }
            }
            CameraMode.HDR -> {
                previewSurface?.let { hdrCaptureManager?.setFlashMode(currentFlashMode, it) }
            }
            else -> {
                val session = captureSession ?: return
                val device = cameraDevice ?: return
                val surface = previewSurface ?: return

                try {
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.FLASH_MODE, currentFlashMode)
                        if (currentFlashMode == CaptureRequest.FLASH_MODE_TORCH) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                    }
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating flash request", e)
                }
            }
        }
    }

    private fun setupGpuPreview() {
        gpuRenderer = GpuFilterRenderer().apply {
            onSurfaceReady = { surface ->
                Log.d(TAG, "GLSurfaceView surface ready")
                previewSurface = surface
                isCameraReady = true
                if (isActivityResumed && availableCameras.isNotEmpty() && cameraDevice == null) {
                    openCamera(currentCameraId)
                }
            }
            onRequestRender = {
                glSurfaceView.requestRender()
            }
        }

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(gpuRenderer!!)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glSurfaceView.preserveEGLContextOnPause = true // Mantener contexto para evitar parpadeos

        // Force layout update
        glSurfaceView.requestLayout()
        glSurfaceView.invalidate()

        Log.d(TAG, "GLSurfaceView setup complete")
    }

    private fun setupGpuFilterButtons() {
        val filterMap = mapOf(
            R.id.btnFilterNormal to FilterType.NORMAL,
            R.id.btnFilterBW to FilterType.BLACK_WHITE,
            R.id.btnFilterSepia to FilterType.SEPIA,
            R.id.btnFilterNegative to FilterType.NEGATIVE,
            R.id.btnFilterIR to FilterType.SIMULATED_IR,
            R.id.btnFilterNight to FilterType.NIGHT_VISION,
            R.id.btnFilterThermal to FilterType.THERMAL_SIM,
            R.id.btnFilterSketch to FilterType.SKETCH
        )

        filterMap.forEach { (id, filter) ->
            try {
                val button = findViewById<Button>(id)
                if (button != null) {
                    button.setOnClickListener {
                        Log.d(TAG, "Filter button clicked: $filter")
                        currentFilter = filter
                        gpuRenderer?.updateFilter(filter)
                        updateFilterButtons()
                    }
                    Log.d(TAG, "Set up filter button: $id")
                } else {
                    Log.e(TAG, "Could not find filter button with id: $id")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up filter button $id", e)
            }
        }
    }

    private fun setupManualControls() {
        manualControls.onIsoChanged = { iso ->
            manualController?.setIso(iso)
            updateInfoOverlay()
        }
        manualControls.onShutterSpeedChanged = { ms ->
            manualController?.setShutterSpeed(ms)
            updateInfoOverlay()
        }
        manualControls.onFocusChanged = { enabled, distance ->
            manualController?.setManualFocus(enabled, distance)
            updateInfoOverlay()
        }
        manualControls.onWbChanged = { kelvin ->
            manualController?.setWhiteBalance(kelvin)
            updateInfoOverlay()
        }
    }

    private fun setMode(mode: CameraMode) {
        Log.d("BUTTON_DEBUG", "setMode called with: $mode")
        if (currentMode == mode && cameraDevice != null) {
            Log.d(TAG, "Mode already set to $mode and camera is open, skipping")
            return
        }

        Log.d(TAG, "Switching mode to $mode")
        currentMode = mode

        Toast.makeText(this, "Modo: ${mode.name}", Toast.LENGTH_SHORT).show()

        // Actualizar UI de botones
        val buttons = mapOf(
            CameraMode.AUTO to btnModeAuto,
            CameraMode.MANUAL to btnModeManual,
            CameraMode.RAW to btnModeRaw,
            CameraMode.HDR to btnModeHdr
        )

        buttons.forEach { (m, btn) ->
            val isActive = m == mode
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) 0xFF1976D2.toInt() else 0xFF424242.toInt()
            )
            btn.alpha = if (isActive) 1.0f else 0.6f
        }

        // Mostrar/ocultar controles manuales
        manualControls.visibility = if (mode == CameraMode.MANUAL) View.VISIBLE else View.GONE

        // Reiniciar cámara de forma limpia
        if (isActivityResumed) {
            restartCamera()
        }
    }

    private fun restartCamera() {
        // Cancelar cualquier operación previa de cámara
        cameraSetupJob?.cancel()

        cameraSetupJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "Restarting camera for $currentMode")

                // 1. Cerrar todo
                closeCameraAsync(cancelSetup = false)

                // 2. Breve espera para que el sistema libere el hardware
                delay(300)

                // 3. Reabrir
                if (isActivityResumed) {
                    performOpenCamera(currentCameraId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en restartCamera: ${e.message}")
            }
        }
    }

    private fun updateFilterButtons() {
        // Resaltar botón activo
        val filterMap = mapOf(
            FilterType.NORMAL to R.id.btnFilterNormal,
            FilterType.BLACK_WHITE to R.id.btnFilterBW,
            FilterType.SEPIA to R.id.btnFilterSepia,
            FilterType.NEGATIVE to R.id.btnFilterNegative,
            FilterType.SIMULATED_IR to R.id.btnFilterIR,
            FilterType.NIGHT_VISION to R.id.btnFilterNight,
            FilterType.THERMAL_SIM to R.id.btnFilterThermal,
            FilterType.SKETCH to R.id.btnFilterSketch
        )

        filterMap.forEach { (filter, buttonId) ->
            try {
                val button = findViewById<Button>(buttonId)
                if (button != null) {
                    if (filter == currentFilter) {
                        button.setBackgroundColor(
                            ContextCompat.getColor(
                                this,
                                android.R.color.holo_blue_dark
                            )
                        )
                        button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    } else {
                        button.setBackgroundColor(
                            ContextCompat.getColor(
                                this,
                                android.R.color.darker_gray
                            )
                        )
                        button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating filter button $buttonId", e)
            }
        }
    }

    private fun updateInfoOverlay() {
        val info = when (currentMode) {
            CameraMode.MANUAL -> {
                val mc = manualController
                "ISO ${mc?.currentIso}  ${mc?.formatExposureTime(mc.currentExposureTime)}  " +
                        if (mc?.isManualFocus == true) "MF" else "AF"
            }

            CameraMode.RAW -> "RAW+JPEG"
            CameraMode.HDR -> "HDR Bracketing"
            else -> "AUTO"
        }
        tvCameraInfo.text = info
    }

    // ============ PERMISOS Y SETUP ============

    private fun checkPermissions() {
        if (allPermissionsGranted()) {
            startCameraSetup()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && allPermissionsGranted()) {
            startCameraSetup()
        } else {
            finish()
        }
    }

    private fun startCameraSetup() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)

        enumerateCameras()
        setupCameraSelector()

        // Now that cameras are enumerated, open the camera if surface is ready
        if (isCameraReady && isActivityResumed) {
            openCamera(currentCameraId)
        }
    }

    // ============ CÁMARA ============

    private fun enumerateCameras() {
        val cameras = mutableListOf<CameraInfo>()

        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Cámaras detectadas por el sistema: ${cameraIds.joinToString()}")

            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                ) ?: floatArrayOf(0f)

                // Identificación más precisa para Xiaomi y otros multicanal
                val type = when {
                    lensFacing == CameraCharacteristics.LENS_FACING_FRONT -> CameraType.FRONT
                    cameraId == "0" -> CameraType.MAIN
                    focalLengths.any { it < 2.5f } -> CameraType.ULTRA_WIDE
                    focalLengths.any { it > 5.0f } -> CameraType.TELEPHOTO
                    else -> CameraType.MAIN
                }

                val info = CameraInfo(
                    cameraId = cameraId,
                    type = type,
                    lensFacing = lensFacing ?: -1,
                    focalLengths = focalLengths.toList(),
                    hasRaw = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                    )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ?: false
                )
                cameras.add(info)
                Log.d(TAG, "Cámara ID $cameraId: ${info.type} (Focal: ${info.focalLengths})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerando cámaras", e)
        }

        availableCameras = cameras.sortedBy { it.cameraId }
    }

    private fun setupCameraSelector() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            availableCameras.map { "${it.type.name} (${it.cameraId})" }
        )
        cameraSelector.adapter = adapter
        cameraSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedCameraId = availableCameras[position].cameraId
                if (selectedCameraId != currentCameraId) {
                    currentCameraId = selectedCameraId
                    // Solo cambiar si la actividad está activa
                    if (isActivityResumed) {
                        closeCamera()
                        openCamera(currentCameraId)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun openCamera(cameraId: String) {
        if (!allPermissionsGranted()) {
            Log.e(TAG, "Permisos no concedidos")
            return
        }

        // No cancelamos el job aquí si ya viene de restartCamera para evitar auto-cancelación
        // Solo lanzamos si no hay un job activo o si lo llamamos directamente
        if (cameraSetupJob == null || cameraSetupJob?.isActive == false) {
            cameraSetupJob = lifecycleScope.launch {
                performOpenCamera(cameraId)
            }
        } else {
            // Si ya hay un job (como restartCamera), solo ejecutamos la lógica
            lifecycleScope.launch {
                performOpenCamera(cameraId)
            }
        }
    }

    private suspend fun performOpenCamera(cameraId: String) {
        try {
            Log.d(TAG, "performOpenCamera: $cameraId")

            withTimeout(8000) {
                cameraOpenCloseLock.withLock {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    lastCameraId = cameraId

                    // Verificar soporte de flash
                    isFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    currentFlashMode = CaptureRequest.FLASH_MODE_OFF
                    withContext(Dispatchers.Main) {
                        updateFlashButtonUI()
                    }

                    suspendCancellableCoroutine { cont ->
                        try {
                            if (ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.CAMERA
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                if (cont.isActive) cont.resumeWithException(SecurityException("Permission denied"))
                                return@suspendCancellableCoroutine
                            }

                            cameraManager.openCamera(
                                cameraId,
                                object : CameraDevice.StateCallback() {
                                    override fun onOpened(device: CameraDevice) {
                                        Log.d(TAG, "Dispositivo de cámara abierto: ${device.id}")
                                        cameraDevice = device

                                        lifecycleScope.launch(Dispatchers.IO) {
                                            try {
                                                setupCameraSession(device, characteristics)
                                                if (cont.isActive) cont.resume(Unit)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Fallo en setupCameraSession", e)
                                                device.close()
                                                if (cont.isActive) cont.resumeWithException(e)
                                            }
                                        }
                                    }

                                    override fun onDisconnected(device: CameraDevice) {
                                        Log.w(TAG, "Cámara desconectada")
                                        device.close()
                                        if (cameraDevice == device) cameraDevice = null
                                        if (cont.isActive) cont.resume(Unit)
                                    }

                                    override fun onError(device: CameraDevice, error: Int) {
                                        Log.e(TAG, "Error en dispositivo de cámara: $error")
                                        device.close()
                                        if (cameraDevice == device) cameraDevice = null
                                        if (cont.isActive) cont.resumeWithException(Exception("Error hardware cámara: $error"))
                                    }
                                },
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Excepción al llamar openCamera", e)
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico abriendo cámara: ${e.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Error cámara: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun getPreviewSurface(characteristics: CameraCharacteristics): Surface? {
        if (previewSurface != null) return previewSurface

        Log.d(TAG, "Obteniendo Preview Surface...")
        val texture = gpuRenderer?.getSurfaceTexture()
            ?: textureView.surfaceTexture
            ?: run {
                Log.e(TAG, "No hay SurfaceTexture disponible")
                return null
            }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        if (sensorOrientation == 90 || sensorOrientation == 270) {
            texture.setDefaultBufferSize(previewSize.height, previewSize.width)
        } else {
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        }

        previewSurface = Surface(texture)
        return previewSurface
    }

    private suspend fun setupCameraSession(
        device: CameraDevice,
        characteristics: CameraCharacteristics
    ) {
        Log.d(TAG, "Configurando sesión para modo: $currentMode")

        // Asegurar que el dispositivo sigue siendo válido
        if (device.id != lastCameraId) {
            Log.d(TAG, "Device ID mismatch en setupCameraSession, abortando")
            return
        }

        try {
            when (currentMode) {
                CameraMode.RAW -> setupRawMode(device, characteristics)
                CameraMode.HDR -> setupHdrMode(device, characteristics)
                CameraMode.MANUAL -> setupManualMode(device, characteristics)
                else -> setupAutoMode(device)
            }

            withContext(Dispatchers.Main) {
                updateInfoOverlay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fatal configurando sesión de cámara", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Error al iniciar cámara: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun setupRawMode(device: CameraDevice, characteristics: CameraCharacteristics) {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSize =
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
                ?: Size(4032, 3024)
        val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: Size(4032, 3024)

        rawCaptureManager = RawCaptureManager(device, backgroundHandler)
        rawCaptureManager?.currentFlashMode = currentFlashMode

        val surface = getPreviewSurface(characteristics) ?: return

        if (rawCaptureManager?.isRawSupported(characteristics) == true) {
            rawCaptureManager?.setupRawCaptureSession(
                surface,
                jpegSize,
                rawSize
            ) { raw, jpeg ->
                saveRawAndJpeg(raw, jpeg)
            }
            Log.d(TAG, "Modo RAW configurado con éxito")
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "RAW no soportado en esta cámara",
                    Toast.LENGTH_LONG
                ).show()
                setMode(CameraMode.AUTO)
            }
        }
    }

    private suspend fun setupHdrMode(device: CameraDevice, characteristics: CameraCharacteristics) {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val captureSize =
            map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(4032, 3024)

        val surface = getPreviewSurface(characteristics) ?: return

        hdrCaptureManager = HdrBracketingCapture(device, characteristics, backgroundHandler)
        hdrCaptureManager?.currentFlashMode = currentFlashMode
        hdrCaptureManager?.setupSession(surface, captureSize, previewCaptureCallback)
        Log.d(TAG, "Modo HDR configurado con éxito")
    }

    private suspend fun setupManualMode(
        device: CameraDevice,
        characteristics: CameraCharacteristics
    ) {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val captureSize =
            map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(4032, 3024)

        imageReader?.close()
        imageReader =
            ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 5)
                .apply {
                    setOnImageAvailableListener(imageReaderListener, backgroundHandler)
                }

        val surface = getPreviewSurface(characteristics) ?: return

        manualController = ManualModeController(device, characteristics, backgroundHandler)
        manualController?.currentFlashMode = currentFlashMode
        manualController?.setupManualSession(surface, imageReader!!.surface)

        // Configurar rangos en UI
        withContext(Dispatchers.Main) {
            manualControls.setIsoRange(
                manualController?.isoRange?.lower ?: 100,
                manualController?.isoRange?.upper ?: 6400
            )
            Log.d(TAG, "Modo MANUAL configurado con éxito")
        }
    }

    private suspend fun setupAutoMode(device: CameraDevice) =
        suspendCancellableCoroutine { continuation ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(device.id)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val captureSize =
                    map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                        ?: Size(1920, 1080)

                val surface = getPreviewSurface(characteristics)
                if (surface == null) {
                    Log.e(TAG, "No se pudo obtener la superficie de preview")
                    if (continuation.isActive) continuation.resume(Unit)
                    return@suspendCancellableCoroutine
                }

                // Liberar recursos previos de sesión si existen antes de crear nueva
                captureSession?.close()
                captureSession = null

                // Liberar ImageReader previo
                imageReader?.close()
                imageReader = ImageReader.newInstance(
                    captureSize.width,
                    captureSize.height,
                    ImageFormat.JPEG,
                    5
                ).apply {
                    setOnImageAvailableListener(imageReaderListener, backgroundHandler)
                }

                val surfaces = listOf(surface, imageReader!!.surface)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val outputConfigs = surfaces.map {
                        android.hardware.camera2.params.OutputConfiguration(it)
                    }
                    val sessionConfiguration = android.hardware.camera2.params.SessionConfiguration(
                        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        { command -> backgroundHandler.post(command) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (device != cameraDevice) {
                                    Log.d(
                                        TAG,
                                        "Dispositivo obsoleto en onConfigured, cerrando sesión"
                                    )
                                    session.close()
                                    if (continuation.isActive) continuation.resume(Unit)
                                    return
                                }
                                captureSession = session
                                try {
                                    val requestBuilder =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply {
                                                addTarget(surface)
                                                set(CaptureRequest.FLASH_MODE, currentFlashMode)
                                            }
                                    session.setRepeatingRequest(
                                        requestBuilder.build(),
                                        previewCaptureCallback,
                                        backgroundHandler
                                    )
                                    Log.d(TAG, "Preview Auto iniciado con éxito")
                                    if (continuation.isActive) continuation.resume(Unit)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error iniciando request repetitivo", e)
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Fallo al configurar sesión de captura")
                                if (continuation.isActive) continuation.resumeWithException(
                                    Exception("Configuración fallida")
                                )
                            }
                        }
                    )
                    device.createCaptureSession(sessionConfiguration)
                } else {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (device != cameraDevice) {
                                    session.close()
                                    if (continuation.isActive) continuation.resume(Unit)
                                    return
                                }
                                captureSession = session
                                try {
                                    val request =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply {
                                                addTarget(surface)
                                                set(CaptureRequest.FLASH_MODE, currentFlashMode)
                                            }.build()
                                    session.setRepeatingRequest(request, previewCaptureCallback, backgroundHandler)
                                    Log.d(TAG, "Preview Auto (Legacy) iniciado")
                                    if (continuation.isActive) continuation.resume(Unit)
                                } catch (e: Exception) {
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Fallo al configurar sesión de captura (legacy)")
                                if (continuation.isActive) continuation.resumeWithException(
                                    Exception("Configuración fallida")
                                )
                            }
                        },
                        backgroundHandler
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en setupAutoMode", e)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }

    // ============ CAPTURA ============

    private fun capturePhoto() {
        when (currentMode) {
            CameraMode.RAW -> rawCaptureManager?.captureRawDng(getRotationCompensation())
            CameraMode.HDR -> captureHdrPhoto()
            CameraMode.MANUAL -> manualController?.captureManual(imageReader!!.surface)
            else -> captureStandardPhoto()
        }
    }

    private fun captureHdrPhoto() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                runOnUiThread { progressBar.visibility = View.VISIBLE }

                withTimeout(20000) { // Timeout de 20 segundos para ráfaga HDR
                    val baseExp = lastCaptureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33333333L
                    val baseIso = lastCaptureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400

                    val images = hdrCaptureManager?.captureHdrBracketing(
                        steps = 5,
                        evStep = 1.2f,
                        baseExposure = baseExp,
                        baseIso = baseIso
                    )

                    if (!images.isNullOrEmpty()) {
                        val merged = hdrCaptureManager?.mergeHdrImages(images)
                        merged?.let { bytes ->
                            val fileName = "HDR_${System.currentTimeMillis()}.jpg"
                            val file = saveImage(bytes, fileName)
                            applyFilterToFile(file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en captura HDR", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Fallo en HDR: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                runOnUiThread { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun captureStandardPhoto() {
        lifecycleScope.launch(Dispatchers.Main) {
            val device = cameraDevice
            val reader = imageReader
            
            cameraOpenCloseLock.withLock {
                val session = captureSession
                if (device == null || reader == null || session == null) {
                    Log.w(TAG, "captureStandardPhoto: Camera resources not ready or session closed")
                    return@withLock
                }

                try {
                    val requestBuilder =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            set(CaptureRequest.JPEG_ORIENTATION, getRotationCompensation())
                            set(CaptureRequest.FLASH_MODE, currentFlashMode)
                        }

                    session.capture(
                        requestBuilder.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureStarted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                timestamp: Long,
                                frameNumber: Long
                            ) {
                                super.onCaptureStarted(session, request, timestamp, frameNumber)
                                runOnUiThread { progressBar.visibility = View.VISIBLE }
                            }

                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                super.onCaptureCompleted(session, request, result)
                                runOnUiThread { progressBar.visibility = View.GONE }
                            }
                        },
                        backgroundHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error en captureStandardPhoto", e)
                    runOnUiThread { progressBar.visibility = View.GONE }
                }
            }
        }
    }

    private suspend fun captureStandardPhotoAsBytes(): ByteArray? = suspendCancellableCoroutine { continuation ->
        val device = cameraDevice
        val reader = imageReader
        val session = captureSession

        if (device == null || reader == null || session == null) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                
                Log.d(TAG, "captureStandardPhotoAsBytes: Captured ${bytes.size} bytes")
                
                // Restaurar listener normal
                r.setOnImageAvailableListener(imageReaderListener, backgroundHandler)
                
                if (continuation.isActive) continuation.resume(bytes)
            } else {
                Log.e(TAG, "captureStandardPhotoAsBytes: Image was null")
                if (continuation.isActive) continuation.resume(null)
            }
        }, backgroundHandler)

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, getRotationCompensation())
                // Usar flash si está configurado
                set(CaptureRequest.FLASH_MODE, currentFlashMode)
            }
            session.capture(requestBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun getRotationCompensation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val sensorOrientation = cameraManager.getCameraCharacteristics(currentCameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return (sensorOrientation - degrees + 360) % 360
    }

    private fun captureAllCameras() {
        if (isProcessing) return
        isProcessing = true
        progressBar.visibility = View.VISIBLE
        val prevCameraId = currentCameraId
        val prevMode = currentMode

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // Cambiar a modo AUTO para asegurar compatibilidad en todas las cámaras
                currentMode = CameraMode.AUTO
                
                var count = 0
                for (camera in availableCameras) {
                    Log.d(TAG, "Multi-capture: opening ${camera.cameraId}")
                    currentCameraId = camera.cameraId
                    performOpenCamera(camera.cameraId)
                    
                    val bytes = captureStandardPhotoAsBytes()
                    if (bytes != null) {
                        processCapturedImage(bytes, camera.cameraId)
                        count++
                    }
                    delay(300)
                }

                // Restaurar estado original
                currentCameraId = prevCameraId
                currentMode = prevMode
                performOpenCamera(prevCameraId)

                Toast.makeText(this@MainActivity, "Capturadas $count fotos", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error en multi-capture", e)
            } finally {
                progressBar.visibility = View.GONE
                isProcessing = false
            }
        }
    }

    // ============ GUARDADO ============

    private fun saveRawAndJpeg(rawBytes: ByteArray, jpegBytes: ByteArray) {
        val timestamp = System.currentTimeMillis()

        // Guardar JPEG en Galería con filtro aplicado
        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "IMG_RAW_$timestamp.jpg"
            val file = saveImage(jpegBytes, fileName)
            applyFilterToFile(file)
        }

        // Guardar RAW (en almacenamiento privado por ahora, o podrías usar MediaStore para DNG)
        val directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        val rawFile = File(directory, "IMG_$timestamp.dng")
        rawCaptureManager?.saveRawAsDng(rawBytes, rawFile, 4032, 3024)

        Log.d(TAG, "RAW y JPEG procesados")
    }

    private fun processCapturedImage(bytes: ByteArray, cameraId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fileName = "IMG_${cameraId}_${System.currentTimeMillis()}.jpg"
            val file = saveImage(bytes, fileName)
            applyFilterToFile(file)
        }
    }

    private fun showGalleryBottomSheet() {
        Log.d(TAG, "showGalleryBottomSheet called")
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_gallery_sheet, null)
        
        val rvGallery = view.findViewById<RecyclerView>(R.id.rvGallery)
        val images = imageSaver.getSavedImages()
        
        if (images.isEmpty()) {
            Toast.makeText(this, "No hay fotos guardadas", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = GalleryAdapter(images) { file ->
            dialog.dismiss()
            showPhotoViewer(file)
        }
        
        rvGallery.layoutManager = GridLayoutManager(this, 3)
        rvGallery.adapter = adapter
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showPhotoViewer(file: File) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .create()
        val view = layoutInflater.inflate(R.layout.layout_photo_viewer, null)
        
        val ivFullPhoto = view.findViewById<ImageView>(R.id.ivFullPhoto)
        val tvPhotoInfo = view.findViewById<TextView>(R.id.tvPhotoInfo)
        val btnClose = view.findViewById<View>(R.id.btnCloseViewer)
        val btnShare = view.findViewById<Button>(R.id.btnShare)
        val btnEdit = view.findViewById<Button>(R.id.btnEdit)
        val btnDelete = view.findViewById<Button>(R.id.btnDelete)

        Glide.with(this).load(file).into(ivFullPhoto)
        
        val date = Date(file.lastModified())
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        tvPhotoInfo.text = "${file.name}\n${sdf.format(date)} - ${(file.length() / 1024)} KB"

        btnClose.setOnClickListener { dialog.dismiss() }

        btnShare.setOnClickListener {
            val uri = imageSaver.getShareUri(file.absolutePath)
            if (uri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Compartir foto"))
            } else {
                Toast.makeText(this, "Error al generar archivo para compartir", Toast.LENGTH_SHORT).show()
            }
        }

        btnEdit.setOnClickListener {
            dialog.dismiss()
            openEditingMode(file)
        }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Eliminar foto")
                .setMessage("¿Estás seguro de que quieres eliminar esta foto?")
                .setPositiveButton("Eliminar") { _, _ ->
                    if (imageSaver.deleteImage(file.absolutePath)) {
                        Toast.makeText(this, "Foto eliminada", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        updateGalleryThumbnail()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        dialog.setView(view)
        dialog.show()
    }

    private fun updateGalleryThumbnail() {
        val lastImage = imageSaver.getSavedImages().firstOrNull()
        if (lastImage != null) {
            Glide.with(this)
                .load(lastImage)
                .centerCrop()
                .circleCrop()
                .into(btnGallery)
        } else {
            btnGallery.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun saveImage(bytes: ByteArray, fileName: String): File {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/MultiCameraPro"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        imageUri?.let { uri ->
            resolver.openOutputStream(uri).use { outputStream ->
                outputStream?.write(bytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            // También guardamos una copia en el directorio privado para el procesamiento de filtros
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MultiCameraPro").apply { mkdirs() }
            val file = File(directory, fileName)
            FileOutputStream(file).use { it.write(bytes) }

            runOnUiThread {
                Toast.makeText(this, "Imagen guardada", Toast.LENGTH_SHORT).show()
                updateGalleryThumbnail()
            }
            lastCapturedFile = file
            return file
        }

        val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MultiCameraPro").apply { mkdirs() }
        val file = File(directory, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        lastCapturedFile = file
        
        runOnUiThread {
            updateGalleryThumbnail()
        }

        return file
    }

    private fun openEditingMode(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Cargar una versión escalada para la preview (evita OOM)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val maxDimension = 1500
            var sampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / sampleSize >= maxDimension && halfWidth / sampleSize >= maxDimension) {
                    sampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)

            withContext(Dispatchers.Main) {
                currentEditingBitmap = bitmap
                editingPreview.setImageBitmap(bitmap)
                advancedEditingPanel.visibility = View.VISIBLE
                advancedEditingPanel.bringToFront()
                applyEditingChanges()
            }
        }
    }

    // Advanced editing properties moved to class level
    private lateinit var advancedFilterProcessor: AdvancedFilterProcessor
    private lateinit var histogramGenerator: HistogramGenerator
    private var currentEditingBitmap: Bitmap? = null

    // Nuevas vistas
    private lateinit var histogramView: ImageView
    private lateinit var adjustmentPanel: AdjustmentPanelView

    // Estado de ajustes
    private var currentExposure = 0f
    private var currentContrast = 0f
    private var currentHighlights = 0f
    private var currentShadows = 0f
    private var currentWhites = 0f
    private var currentBlacks = 0f
    private var currentTemperature = 0f
    private var currentTint = 0f
    private var currentVibrance = 0f
    private var currentSaturation = 0f
    private var currentSharpness = 0f
    private var currentClarity = 0f
    private var currentDenoise = 0f

    private fun initializeAdvancedEditing() {
        advancedFilterProcessor = AdvancedFilterProcessor()
        histogramGenerator = HistogramGenerator()

        // Referencias a nuevas vistas
        advancedEditingPanel = findViewById(R.id.advancedEditingPanel)
        editingPreview = findViewById(R.id.editingPreview)
        histogramView = findViewById(R.id.histogramView)
        adjustmentPanel = findViewById(R.id.adjustmentPanel)
        btnApplyEdits = findViewById(R.id.btnApplyEdits)
        btnResetEdits = findViewById(R.id.btnResetEdits)

        btnResetEdits.setOnClickListener {
            adjustmentPanel.resetAll()
            // Forzar actualización de variables locales para que coincidan con el panel reseteado
            currentExposure = 0f
            currentContrast = 0f
            currentHighlights = 0f
            currentShadows = 0f
            currentWhites = 0f
            currentBlacks = 0f
            currentTemperature = 0f
            currentTint = 0f
            currentVibrance = 0f
            currentSaturation = 0f
            currentSharpness = 0f
            currentClarity = 0f
            currentDenoise = 0f
            applyEditingChanges()
        }

        btnApplyEdits.setOnClickListener {
            // Logic to save the current editing result
            lastEditingResult?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    saveImage(bitmapToBytes(it), "EDITED_${System.currentTimeMillis()}.jpg")
                    withContext(Dispatchers.Main) {
                        advancedEditingPanel.visibility = View.GONE
                    }
                }
            } ?: run {
                Toast.makeText(this, "No hay cambios para guardar", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            advancedEditingPanel.visibility = View.GONE
        }

        // Listeners para botones de efectos especiales
        findViewById<Button>(R.id.btnLightLeaks).setOnClickListener {
            applyAdvancedFilter("LIGHT_LEAKS")
        }

        findViewById<Button>(R.id.btnAnalogFilm).setOnClickListener {
            applyAdvancedFilter("ANALOG_FILM")
        }

        findViewById<Button>(R.id.btnDoubleExposure).setOnClickListener {
            // Podría usarse para Bokeh o similar por ahora
            applyAdvancedFilter("BOKEH")
        }

        adjustmentPanel.onExposureChanged =
            { exposure, contrast, highlights, shadows, whites, blacks ->
                currentExposure = exposure
                currentContrast = contrast
                currentHighlights = highlights
                currentShadows = shadows
                currentWhites = whites
                currentBlacks = blacks
                applyEditingChanges()
            }

        adjustmentPanel.onColorChanged = { temp, tint, vibrance, saturation ->
            currentTemperature = temp
            currentTint = tint
            currentVibrance = vibrance
            currentSaturation = saturation
            applyEditingChanges()
        }

        adjustmentPanel.onDetailChanged = { sharpness, clarity, denoise ->
            currentSharpness = sharpness
            currentClarity = clarity
            currentDenoise = denoise
            applyEditingChanges()
        }

        adjustmentPanel.onCurveChanged = { _ ->
            applyEditingChanges()
        }
    }

    private var editingJob: Job? = null

    private fun applyEditingChanges() {
        editingJob?.cancel()
        editingJob = lifecycleScope.launch(Dispatchers.Default) {
            delay(100)
            val baseBitmap = currentEditingBitmap ?: return@launch

            try {
                // IMPORTANTÍSIMO: Crear una COPIA del bitmap base antes de aplicar ajustes.
                // De lo contrario, estamos modificando el original una y otra vez (o nada si falla la lógica).
                var result = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val bitmapsToRecycle = mutableListOf<Bitmap>()

                // Aplicar exposición
                if (currentExposure != 0f || currentContrast != 0f ||
                    currentHighlights != 0f || currentShadows != 0f ||
                    currentWhites != 0f || currentBlacks != 0f
                ) {
                    val next = advancedFilterProcessor.applyExposureAdjustments(
                        result,
                        exposure = currentExposure,
                        contrast = currentContrast,
                        highlights = currentHighlights,
                        shadows = currentShadows,
                        whites = currentWhites,
                        blacks = currentBlacks
                    )
                    if (next != result) {
                        bitmapsToRecycle.add(result)
                        result = next
                    }
                }

                // Aplicar color
                if (currentTemperature != 0f || currentTint != 0f ||
                    currentVibrance != 0f || currentSaturation != 0f
                ) {
                    val next = advancedFilterProcessor.applyColorAdjustments(
                        result,
                        temperature = currentTemperature,
                        tint = currentTint,
                        vibrance = currentVibrance,
                        saturation = currentSaturation
                    )
                    if (next != result) {
                        bitmapsToRecycle.add(result)
                        result = next
                    }
                }

                // Aplicar curvas
                val curve = withContext(Dispatchers.Main) {
                    adjustmentPanel.findViewById<CurveEditorView>(R.id.curveEditorView)?.curve
                }
                if (curve != null) {
                    val next = advancedFilterProcessor.applyToneCurves(result, curve)
                    if (next != result) {
                        bitmapsToRecycle.add(result)
                        result = next
                    }
                }

                // Aplicar detalle
                if (currentSharpness != 0f || currentClarity != 0f || currentDenoise != 0f) {
                    val next = advancedFilterProcessor.applyDetailAdjustments(
                        result,
                        sharpness = currentSharpness,
                        clarity = currentClarity,
                        denoise = currentDenoise
                    )
                    if (next != result) {
                        bitmapsToRecycle.add(result)
                        result = next
                    }
                }

                // Actualizar histograma
                val histogram = histogramGenerator.generateHistogram(result)

                withContext(Dispatchers.Main) {
                    editingPreview.setImageBitmap(result)
                    updateHistogramView(histogram)

                    // Reciclar bitmaps intermedios después de mostrar el final
                    bitmapsToRecycle.forEach { 
                        if (it != baseBitmap && it != result && !it.isRecycled) it.recycle() 
                    }
                    
                    // Nota: 'result' NO se recicla porque es lo que está viendo el usuario ahora.
                    // Se reciclará en la próxima llamada de applyEditingChanges o al cerrar el panel.
                    lastEditingResult = result 
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM en edición", e)
                System.gc()
            } catch (e: Exception) {
                Log.e(TAG, "Error en edición", e)
            }
        }
    }

    private var lastEditingResult: Bitmap? = null

    private fun updateHistogramView(data: HistogramGenerator.HistogramData) {
        val w = histogramView.width
        val h = histogramView.height
        if (w <= 0 || h <= 0) return

        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        val bounds = Rect(0, 0, w, h)
        histogramGenerator.drawHistogram(canvas, data, bounds)
        histogramView.setImageBitmap(bitmap)
    }

    private fun applyAdvancedFilter(type: String) {
        val baseBitmap = currentEditingBitmap ?: return
        
        // Mostrar feedback visual
        progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = when (type) {
                    "LIGHT_LEAKS" -> {
                        val processor = LightLeaksProcessor()
                        val params = LightLeaksProcessor.LightLeakParams(
                            type = LightLeaksProcessor.LeakType.ORANGE_STREAK,
                            intensity = 0.7f,
                            position = LightLeaksProcessor.LeakPosition.TOP_LEFT
                        )
                        processor.applyLightLeak(baseBitmap, params)
                    }
                    "ANALOG_FILM" -> {
                        val processor = LightLeaksProcessor()
                        processor.applyAnalogFilmEffect(baseBitmap, 0.7f)
                    }
                    "BOKEH" -> {
                        advancedFilterProcessor.applyBokeh(baseBitmap, blurRadius = 25f)
                    }
                    else -> baseBitmap
                }

                withContext(Dispatchers.Main) {
                    // IMPORTANTE: Actualizar el bitmap base de edición para que los ajustes posteriores
                    // (brillo, contraste, etc.) se apliquen SOBRE el filtro especial.
                    currentEditingBitmap = result
                    applyEditingChanges()
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Efecto aplicado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error aplicando filtro avanzado", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Error al aplicar efecto", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        return stream.toByteArray()
    }

    private fun applyFilterToFile(file: File): File? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val filtered = filterProcessor.applyFilter(bitmap, currentFilter)

            val filteredFileName = "${file.nameWithoutExtension}_${currentFilter.name}.jpg"

            // Guardar la versión filtrada en la Galería también
            val stream = java.io.ByteArrayOutputStream()
            filtered.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            saveImage(stream.toByteArray(), filteredFileName)

            // Guardar en privado para referencia
            val filteredFile = File(file.parent, filteredFileName)
            FileOutputStream(filteredFile).use { out ->
                out.write(stream.toByteArray())
            }
            filteredFile
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando filtro", e)
            null
        }
    }

    // ============ CIERRE ============

    private suspend fun closeCameraAsync(cancelSetup: Boolean = true) {
        if (cancelSetup) {
            cameraSetupJob?.cancel()
        }
        setupJob?.cancel()
        setupJob = null

        try {
            withTimeout(3000) {
                cameraOpenCloseLock.withLock {
                    lastCameraId = null

                    try {
                        captureSession?.stopRepeating()
                        captureSession?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cerrando captureSession", e)
                    }
                    captureSession = null

                    rawCaptureManager?.release()
                    rawCaptureManager = null

                    hdrCaptureManager?.release()
                    hdrCaptureManager = null

                    timeLapseManager?.stopTimeLapse()
                    timeLapseManager = null

                    manualController?.release()
                    manualController = null

                    cameraDevice?.close()
                    cameraDevice = null

                    imageReader?.close()
                    imageReader = null

                    previewSurface?.release()
                    previewSurface = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando la cámara asíncronamente", e)
        }
    }

    private fun closeCamera() {
        // Versión síncrona/fuego y olvido para ciclos de vida, 
        // pero delegando a una corrutina si es necesario o haciéndolo directo si no hay riesgo
        lifecycleScope.launch {
            closeCameraAsync()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        isCameraReady = false
        closeCamera()
        glSurfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        glSurfaceView.onResume()

        // Don't open camera here - wait for GLSurfaceView surface to be ready
        // The onSurfaceReady callback will handle opening the camera
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        gpuRenderer?.release()
        advancedFilterProcessor.release()
        if (::backgroundThread.isInitialized) {
            backgroundThread.quitSafely()
        }
    }

    // ============ DATA ============

    data class CameraInfo(
        val cameraId: String,
        val type: CameraType,
        val lensFacing: Int,
        val focalLengths: List<Float>,
        val hasRaw: Boolean
    )

    enum class CameraType { MAIN, ULTRA_WIDE, TELEPHOTO, FRONT }

    private fun enterEditingMode(bitmap: Bitmap) {
        currentEditingBitmap = bitmap
        editingPreview.setImageBitmap(bitmap)
        advancedEditingPanel.visibility = View.VISIBLE
        advancedEditingPanel.bringToFront()
        applyEditingChanges()
    }

    private fun initializeAdditionalComponents() {
        collageManager = CollageGridManager()

        btnTimeLapse = findViewById(R.id.btnTimeLapse)
        btnAstro = findViewById(R.id.btnAstro)
        btnCollage = findViewById(R.id.btnCollage)

        btnTimeLapse.setOnClickListener { showTimeLapseDialog() }
        btnAstro.setOnClickListener { showAstroDialog() }
        btnCollage.setOnClickListener { showCollageDialog() }
    }

    // ==================== TIME-LAPSE ====================
    private fun showTimeLapseDialog() {
        val configs = arrayOf("1s intervalo - 1min", "2s intervalo - 5min", "5s intervalo - 10min", "Custom")

        AlertDialog.Builder(this)
            .setTitle("Time-Lapse")
            .setItems(configs) { _, which ->
                val config = when (which) {
                    0 -> TimeLapseManager.TimeLapseConfig(intervalMs = 1000, durationMinutes = 1)
                    1 -> TimeLapseManager.TimeLapseConfig(intervalMs = 2000, durationMinutes = 5)
                    2 -> TimeLapseManager.TimeLapseConfig(intervalMs = 5000, durationMinutes = 10)
                    else -> showCustomTimeLapseConfig()
                }
                startTimeLapse(config)
            }
            .show()
    }

    private fun startTimeLapse(config: TimeLapseManager.TimeLapseConfig) {
        val device = cameraDevice ?: return

        timeLapseManager = TimeLapseManager(device, backgroundHandler)

        lifecycleScope.launch(Dispatchers.IO) {
            timeLapseManager?.startTimeLapse(
                previewSurface = previewSurface!!,
                config = config,
                onProgress = { current, total ->
                    runOnUiThread {
                        tvCameraInfo.text = "Time-lapse: $current/$total"
                    }
                },
                onFrameCaptured = { bitmap ->
                    // Opcional: mostrar preview del frame
                },
                onComplete = { frames ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        // Generar archivo temporal
                        val tempFile = File(cacheDir, "temp_timelapse.mp4")

                        val success = timeLapseManager?.generateVideo(frames, tempFile, config) ?: false

                        if (success) {
                            val fileName = "timelapse_${System.currentTimeMillis()}.mp4"
                            val resolver = contentResolver
                            val contentValues = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MultiCameraPro")
                                    put(MediaStore.Video.Media.IS_PENDING, 1)
                                }
                            }

                            val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                            
                            if (videoUri != null) {
                                resolver.openOutputStream(videoUri)?.use { outputStream ->
                                    tempFile.inputStream().use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    contentValues.clear()
                                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                                    resolver.update(videoUri, contentValues, null, null)
                                }
                                
                                // Escanear para versiones antiguas si es necesario
                                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(videoUri.path), null, null)
                            }
                            
                            tempFile.delete()

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Video guardado en Galería: $fileName", Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Error al generar video", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
        }
    }

    private fun showCustomTimeLapseConfig(): TimeLapseManager.TimeLapseConfig {
        // Implementar diálogo custom
        return TimeLapseManager.TimeLapseConfig()
    }

    // ==================== ASTROFOTOGRAFÍA ====================
    private fun showAstroDialog() {
        val device = cameraDevice ?: return
        val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)

        astroManager = AstrophotographyManager(device, characteristics, backgroundHandler)

        val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        Log.d(TAG, "Astro Check - Camera: $currentCameraId, HW Level: $hwLevel, Range: ${range?.lower}..${range?.upper}")

        if (!astroManager!!.isLongExposureSupported()) {
            val msg = "Astro no soportado en esta lente. HW: $hwLevel, Max: ${range?.upper ?: 0 / 1_000_000}ms"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.w(TAG, msg)
            // No bloqueamos el inicio, permitimos que el usuario intente
            // return 
        }

        val maxExp = astroManager!!.getMaxExposureMs()

        AlertDialog.Builder(this)
            .setTitle("Astrofotografía (Beta)")
            .setMessage("Máxima exposición hardware: ${maxExp / 1000}s\n\nEl sistema capturará 10 fotos y las fusionará para reducir ruido y aumentar brillo.\n\n¿Iniciar captura?")
            .setPositiveButton("Iniciar") { _, _ ->
                startAstroCapture()
            }
            .setNegativeButton("Configurar") { _, _ ->
                showAstroConfigDialog()
            }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun startAstroCapture() {
        val maxExpSupported = astroManager?.getMaxExposureMs() ?: 1000L
        val targetExp = minOf(15000L, maxExpSupported)
        
        val config = AstrophotographyManager.AstroConfig(
            exposureTimeMs = targetExp,
            iso = 3200,
            stackingCount = 10
        )

        Log.d(TAG, "Iniciando captura Astro: ${targetExp}ms, Max supported: ${maxExpSupported}ms")

        progressBar.visibility = View.VISIBLE
        btnAstro.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            astroManager?.captureAstroSequence(
                previewSurface = previewSurface!!,
                config = config,
                onProgress = { stage, current, total ->
                    runOnUiThread {
                        tvCameraInfo.text = "$stage: $current/$total"
                    }
                },
                onComplete = { result ->
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnAstro.isEnabled = true

                        // Guardar resultado
                        val fileResult = lifecycleScope.launch(Dispatchers.IO) {
                            val resultFile = imageSaver.saveToGallery(
                                result,
                                fileName = imageSaver.generateFileName("ASTRO"),
                                quality = 100
                            )

                            withContext(Dispatchers.Main) {
                                if (resultFile.success) {
                                    Toast.makeText(this@MainActivity,
                                        "Astrofoto guardada!", Toast.LENGTH_LONG).show()
                                    enterEditingMode(result)
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun showAstroConfigDialog() {
        // Implementar configuración avanzada
    }

    // ==================== COLLAGE ====================
    private fun showCollageDialog() {
        val layouts = CollageGridManager.LayoutType.values()
        val names = layouts.map { it.name.replace("_", " ") }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Layout Collage")
            .setItems(names) { _, which ->
                captureCollage(layouts[which])
            }
            .show()
    }

    private fun captureCollage(layout: CollageGridManager.LayoutType) {
        if (isProcessing) return
        isProcessing = true
        progressBar.visibility = View.VISIBLE
        
        val prevCameraId = currentCameraId
        val prevMode = currentMode

        lifecycleScope.launch(Dispatchers.Main) {
            val images = mutableMapOf<String, Bitmap>()
            
            try {
                // Forzar modo AUTO para capturas de collage
                currentMode = CameraMode.AUTO
                
                // Usar las cámaras disponibles (Xiaomi 12 suele tener 0, 1, 2, 3)
                if (availableCameras.isEmpty()) {
                    enumerateCameras()
                }

                for (camera in availableCameras) {
                    Log.d(TAG, "Collage capture: opening camera ${camera.cameraId}")
                    currentCameraId = camera.cameraId
                    performOpenCamera(camera.cameraId)
                    
                    // Esperar un poco a que el sensor se estabilice tras abrir
                    delay(500)
                    
                    val bytes = captureStandardPhotoAsBytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            images[camera.cameraId] = bitmap
                            Log.d(TAG, "Captured image for camera ${camera.cameraId}, size: ${bitmap.width}x${bitmap.height}")
                        } else {
                            Log.e(TAG, "Failed to decode bitmap for camera ${camera.cameraId}")
                        }
                    } else {
                        Log.e(TAG, "No bytes captured for camera ${camera.cameraId}")
                    }
                    delay(200)
                }

                // Restaurar cámara original
                currentCameraId = prevCameraId
                currentMode = prevMode
                performOpenCamera(prevCameraId)

                if (images.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No se capturaron imágenes", Toast.LENGTH_SHORT).show()
                    return@launch
                } else {
                    Toast.makeText(this@MainActivity, "Capturadas ${images.size} imágenes para el collage", Toast.LENGTH_SHORT).show()
                }

                // Crear collage
                val collage = withContext(Dispatchers.Default) {
                    collageManager.createCollage(
                        images = images,
                        layout = layout,
                        outputWidth = 2160,
                        outputHeight = 2160,
                        borderWidth = 15,
                        borderColor = Color.WHITE,
                        cornerRadius = 20f
                    )
                }

                // Guardar resultado
                val resultFile = imageSaver.saveToGallery(
                    collage.bitmap,
                    fileName = imageSaver.generateFileName("COLLAGE"),
                    quality = 95
                )

                if (resultFile.success) {
                    Toast.makeText(this@MainActivity, "Collage guardado!", Toast.LENGTH_SHORT).show()
                    enterEditingMode(collage.bitmap)
                } else {
                    Toast.makeText(this@MainActivity, "Error guardando collage: ${resultFile.error}", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creando collage", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                isProcessing = false
            }
        }
    }
}
