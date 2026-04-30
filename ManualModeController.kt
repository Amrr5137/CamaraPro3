package com.example.miappcamarapro3.camara

import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ln
import kotlin.math.pow

/**
 * Control manual completo de parámetros de cámara
 * ISO, Shutter Speed (Exposure Time), Focus Manual, WB
 */
class ManualModeController(
    private val cameraDevice: CameraDevice,
    private val characteristics: CameraCharacteristics,
    private val backgroundHandler: Handler
) {
    companion object {
        private const val TAG = "ManualModeController"
    }

    // Rangos soportados
    val isoRange: Range<Int>? = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
    )

    val exposureTimeRange: Range<Long>? = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
    )

    val focusDistanceRange: Range<Float>? = characteristics.get(
        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
    )?.let {
        Range(0f, it) // 0 = infinito, max = macro cercano
    }

    val wbRange: Range<Int>? = Range(2000, 8000) // Kelvin aproximado

    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    // Estado actual
    var currentIso: Int = isoRange?.lower ?: 100
    var currentExposureTime: Long = 33333333L // 1/30s
    var currentFocusDistance: Float = 0f // 0 = autofocus/infinito
    var currentWb: Int = 5500 // Kelvin
    var isManualFocus: Boolean = false
    var currentFlashMode: Int = CaptureRequest.FLASH_MODE_OFF

    /**
     * Configura sesión en modo manual
     */
    suspend fun setupManualSession(
        previewSurface: Surface,
        captureSurface: Surface? = null
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        val surfaces = mutableListOf(previewSurface)
        captureSurface?.let { surfaces.add(it) }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val outputConfigs =
                surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) }
            val sessionConfiguration = android.hardware.camera2.params.SessionConfiguration(
                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                java.util.concurrent.Executor { command -> backgroundHandler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        createManualPreviewRequest(previewSurface)
                        // Iniciar preview inmediatamente
                        updatePreview()
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.cancel(Exception("Fallo configuración manual"))
                    }
                }
            )
            cameraDevice.createCaptureSession(sessionConfiguration)
        } else {
            @Suppress("DEPRECATION")
            cameraDevice.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        createManualPreviewRequest(previewSurface)
                        // Iniciar preview inmediatamente
                        updatePreview()
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.cancel(Exception("Fallo configuración manual"))
                    }
                },
                backgroundHandler
            )
        }
    }

    /**
     * Crea request de preview con controles manuales
     */
    private fun createManualPreviewRequest(previewSurface: Surface) {
        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)

                // Desactivar AE/AF automático
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    if (isManualFocus) CaptureRequest.CONTROL_AF_MODE_OFF
                    else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )

                // Aplicar valores manuales
                applyManualSettings(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creando preview request manual", e)
        }
    }

    private fun applyManualSettings(builder: CaptureRequest.Builder) {
        // ISO
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

        // Shutter Speed (Exposure Time en nanosegundos)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)

        // Focus Distance (dioptrías, 0 = infinito)
        if (isManualFocus) {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
        }

        // White Balance manual
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        val wbGains = calculateWbGains(currentWb)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, wbGains)

        // Anti-banding
        builder.set(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
        )

        // Flash
        builder.set(CaptureRequest.FLASH_MODE, currentFlashMode)
    }

    /**
     * Actualiza ISO en tiempo real
     */
    fun setIso(iso: Int) {
        val range = isoRange ?: Range(100, 1600)
        currentIso = iso.coerceIn(range.lower, range.upper)
        updatePreview()
        Log.d(TAG, "ISO: $currentIso")
    }

    /**
     * Actualiza Shutter Speed (tiempo de exposición)
     * @param exposureTimeMs Tiempo en milisegundos (ej: 1000 = 1 segundo)
     */
    fun setShutterSpeed(exposureTimeMs: Long) {
        currentExposureTime = (exposureTimeMs * 1_000_000).coerceIn( // Convertir a nanosegundos
            exposureTimeRange?.lower ?: 1000L,
            exposureTimeRange?.upper ?: 1000000000L
        )
        updatePreview()
        Log.d(TAG, "Shutter: ${currentExposureTime}ns (${exposureTimeMs}ms)")
    }

    /**
     * Activa/desactiva focus manual y setea distancia
     * @param distance 0.0f = infinito, valor alto = macro cercano
     */
    fun setManualFocus(enabled: Boolean, distance: Float = 0f) {
        isManualFocus = enabled
        val range = focusDistanceRange ?: Range(0f, 10f)
        currentFocusDistance = distance.coerceIn(range.lower, range.upper)
        updatePreview()
        Log.d(TAG, "Manual Focus: $isManualFocus, distance: $currentFocusDistance")
    }

    /**
     * Setea temperatura de color (White Balance)
     * @param kelvin 2000-8000 (cálido a frío)
     */
    fun setWhiteBalance(kelvin: Int) {
        currentWb = kelvin.coerceIn(2000, 8000)
        updatePreview()
        Log.d(TAG, "WB: ${currentWb}K")
    }

    /**
     * Actualiza modo de flash
     */
    fun setFlashMode(mode: Int) {
        currentFlashMode = mode
        updatePreview()
    }

    /**
     * Convierte temperatura Kelvin a gains RGB
     * Aproximación simplificada
     */
    private fun calculateWbGains(kelvin: Int): RggbChannelVector {
        // Aproximación de conversión Kelvin a RGB gains
        val temperature = kelvin / 100.0

        val red: Float
        val green: Float = 1.0f
        val blue: Float

        if (temperature <= 66) {
            red = 1.0f
            blue = if (temperature <= 19) {
                0.0f
            } else {
                (0.0729 * ln(temperature - 10) - 0.9655).toFloat().coerceIn(0f, 1f)
            }
        } else {
            red = (1.2929 * (temperature - 60).pow(-0.1332)).toFloat().coerceIn(0f, 1f)
            blue = 1.0f
        }

        return RggbChannelVector(red, green, green, blue)
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            applyManualSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando preview manual", e)
        }
    }

    /**
     * Captura foto con settings manuales actuales
     */
    fun captureManual(imageReaderSurface: Surface) {
        val session = captureSession ?: return

        try {
            val captureRequest = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(imageReaderSurface)
                applyManualSettings(this)
            }.build()

            session.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Captura manual completada")
                    Log.d(TAG, "ISO usado: ${result.get(CaptureResult.SENSOR_SENSITIVITY)}")
                    Log.d(TAG, "Exposure: ${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error captura manual", e)
        }
    }

    /**
     * Formatea tiempo de exposición para display
     */
    fun formatExposureTime(timeNs: Long): String {
        val timeMs = timeNs / 1_000_000.0
        return when {
            timeMs >= 1000 -> "%.1fs".format(timeMs / 1000)
            timeMs >= 1 -> "%.0fms".format(timeMs)
            else -> "1/%d".format((1000.0 / timeMs).toInt())
        }
    }

    fun release() {
        captureSession?.close()
    }
}

