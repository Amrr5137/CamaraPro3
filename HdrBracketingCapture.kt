package com.example.miappcamarapro3.camara

import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.pow

/**
 * Implementa HDR mediante bracketing de exposición
 * Captura múltiples fotos con diferentes exposiciones y las fusiona
 */
class HdrBracketingCapture(
    private val cameraDevice: CameraDevice,
    private val characteristics: CameraCharacteristics,
    private val backgroundHandler: Handler
) {
    companion object {
        private const val TAG = "HdrBracketingCapture"
        private const val DEFAULT_BRACKET_STEPS = 5 // -EV, ..., +EV
    }

    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    var currentFlashMode: Int = CaptureRequest.FLASH_MODE_OFF

    fun setFlashMode(mode: Int, previewSurface: Surface) {
        currentFlashMode = mode
        val session = captureSession ?: return
        try {
            val previewRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequest.addTarget(previewSurface)
            previewRequest.set(CaptureRequest.FLASH_MODE, mode)
            session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating flash in HDR mode", e)
        }
    }

    // Rangos de exposición soportados
    private val exposureRange: Range<Long>? = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
    )

    private val isoRange: Range<Int>? = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
    )

    /**
     * Configura sesión para bracketing
     */
    suspend fun setupSession(
        previewSurface: Surface,
        captureSize: android.util.Size
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        imageReader = ImageReader.newInstance(
            captureSize.width, captureSize.height,
            android.graphics.ImageFormat.JPEG, DEFAULT_BRACKET_STEPS
        )

        val surfaces = listOf(previewSurface, imageReader!!.surface)

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
                        try {
                            // Iniciar el preview para HDR
                            val previewRequest =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequest.addTarget(previewSurface)
                            previewRequest.set(CaptureRequest.FLASH_MODE, currentFlashMode)
                            session.setRepeatingRequest(
                                previewRequest.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(TAG, "Preview de HDR iniciado")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando preview en modo HDR", e)
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.cancel(Exception("Fallo configuración HDR"))
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
                        try {
                            // Iniciar el preview para HDR
                            val previewRequest =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequest.addTarget(previewSurface)
                            previewRequest.set(CaptureRequest.FLASH_MODE, currentFlashMode)
                            session.setRepeatingRequest(
                                previewRequest.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(TAG, "Preview de HDR iniciado")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando preview en modo HDR", e)
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.cancel(Exception("Fallo configuración HDR"))
                    }
                },
                backgroundHandler
            )
        }
    }

    /**
     * Ejecuta captura HDR con bracketing
     * @param steps Número de pasos (impar recomendado: 3, 5, 7)
     * @param evStep Tamaño del paso EV (ej: 1.0f = 1 stop)
     */
    suspend fun captureHdrBracketing(
        steps: Int = DEFAULT_BRACKET_STEPS,
        evStep: Float = 1.5f
    ): List<ByteArray> = suspendCancellableCoroutine { continuation ->

        val session = captureSession ?: run {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val images = mutableListOf<ByteArray>()
        val captureList = mutableListOf<CaptureRequest>()

        // Calcular exposiciones
        val baseExposure = 33333333L // ~1/30s en nanosegundos
        val evValues = calculateEvSteps(steps, evStep)

        // Crear requests para cada exposición
        evValues.forEach { ev ->
            val request = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(imageReader!!.surface)

                // Modo manual para controlar exposición
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

                // Calcular tiempo de exposición para este EV
                val exposureTime = calculateExposureForEv(baseExposure, ev)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)

                // ISO fijo para bracketing consistente
                set(CaptureRequest.SENSOR_SENSITIVITY, 100)

                // Estabilización
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }.build()

            captureList.add(request)
        }

        // Listener para capturas
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            images.add(bytes)

            if (images.size >= steps) {
                continuation.resume(images.toList())
            }
        }, backgroundHandler)

        // Ejecutar burst capture
        session.captureBurst(
            captureList,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val ev = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    Log.d(TAG, "Capturado: ${ev}ns")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "Captura fallida: ${failure.reason}")
                }
            },
            backgroundHandler
        )
    }

    /**
     * Fusiona imágenes HDR (simplificación - en producción usar algoritmo profesional)
     */
    fun mergeHdrImages(images: List<ByteArray>): ByteArray {
        // Placeholder - en implementación real:
        // 1. Decodificar todas las imágenes
        // 2. Alinear (anti-ghosting)
        // 3. Fusionar con algoritmo Debevec, Robertson o similar
        // 4. Tone mapping (Reinhard, Drago, etc.)
        // 5. Retornar JPEG final

        // Por ahora retornar la imagen de exposición media
        return images.getOrNull(images.size / 2) ?: images.firstOrNull() ?: ByteArray(0)
    }

    private fun calculateEvSteps(steps: Int, evStep: Float): List<Float> {
        val half = steps / 2
        return (-half..half).map { it * evStep }
    }

    private fun calculateExposureForEv(baseExposure: Long, ev: Float): Long {
        // EV = log2(N²/t) - log2(L*S/100)
        // Simplificación: multiplicar/dividir tiempo por 2^EV
        val factor = 2.0.pow(ev.toDouble()).toFloat()
        return (baseExposure * factor).toLong().coerceIn(
            exposureRange?.lower ?: 1000L,
            exposureRange?.upper ?: 1000000000L
        )
    }

    fun release() {
        captureSession?.close()
        imageReader?.close()
    }
}

