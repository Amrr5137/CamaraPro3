package com.example.miappcamarapro3.camara

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.exp
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
            previewRequest[CaptureRequest.FLASH_MODE] = mode
            session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating flash in HDR mode", e)
        }
    }

    // Rangos de exposición soportados
    private val exposureRange: Range<Long>? = characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]

    private val isoRange: Range<Int>? = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]

    /**
     * Configura sesión para bracketing
     */
    suspend fun setupSession(
        previewSurface: Surface,
        captureSize: Size,
        captureCallback: CameraCaptureSession.CaptureCallback? = null
    ) = suspendCancellableCoroutine { continuation ->

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
                { command -> backgroundHandler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // Iniciar el preview para HDR
                            val previewRequest =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequest.addTarget(previewSurface)
                            previewRequest[CaptureRequest.FLASH_MODE] = currentFlashMode
                            session.setRepeatingRequest(
                                previewRequest.build(),
                                captureCallback,
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
                            previewRequest[CaptureRequest.FLASH_MODE] = currentFlashMode
                            session.setRepeatingRequest(
                                previewRequest.build(),
                                captureCallback,
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
     * @param baseExposure Tiempo de exposición base (AE)
     * @param baseIso Sensibilidad base (AE)
     */
    suspend fun captureHdrBracketing(
        steps: Int = DEFAULT_BRACKET_STEPS,
        evStep: Float = 1.2f,
        baseExposure: Long = 33333333L,
        baseIso: Int = 400
    ): List<ByteArray> = suspendCancellableCoroutine { continuation ->

        val session = captureSession ?: run {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val images = mutableListOf<ByteArray>()
        val captureList = mutableListOf<CaptureRequest>()

        // Calcular exposiciones
        val evValues = calculateEvSteps(steps, evStep)

        // Crear requests para cada exposición
        try {
            evValues.forEach { ev ->
                val requestBuilder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                ).apply {
                    addTarget(imageReader!!.surface)

                    // Modo manual para controlar exposición
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

                    // Calcular tiempo de exposición para este EV
                    val exposureTime = calculateExposureForEv(baseExposure, ev)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)

                    // ISO fijo para bracketing consistente
                    set(CaptureRequest.SENSOR_SENSITIVITY, baseIso)

                    // Estabilización
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                    
                    set(CaptureRequest.FLASH_MODE, currentFlashMode)
                }

                captureList.add(requestBuilder.build())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creando solicitudes de captura HDR", e)
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        // Listener para capturas
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                synchronized(images) {
                    images.add(bytes)
                    Log.d(TAG, "Imagen HDR capturada: ${images.size}/$steps")

                    if (images.size >= steps) {
                        if (continuation.isActive) {
                            continuation.resume(images.toList())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando imagen HDR", e)
            }
        }, backgroundHandler)

        // Ejecutar burst capture
        try {
            session.captureBurst(
                captureList,
                object : CameraCaptureSession.CaptureCallback() {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error en captureBurst HDR", e)
            continuation.resume(emptyList())
        }
    }

    /**
     * Fusiona imágenes HDR usando un algoritmo de ponderación por luminancia
     */
    suspend fun mergeHdrImages(images: List<ByteArray>): ByteArray = withContext(Dispatchers.Default) {
        if (images.isEmpty()) return@withContext ByteArray(0)
        if (images.size == 1) return@withContext images[0]

        try {
            Log.d(TAG, "Iniciando fusión HDR de ${images.size} fotos...")

            // Decodificar todas las imágenes
            val bitmaps = images.map { 
                BitmapFactory.decodeByteArray(it, 0, it.size).copy(Bitmap.Config.ARGB_8888, true) 
            }
            
            val width = bitmaps[0].width
            val height = bitmaps[0].height
            val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            val size = width * height
            val accumR = FloatArray(size)
            val accumG = FloatArray(size)
            val accumB = FloatArray(size)
            val accumW = FloatArray(size)
            
            // Función de peso (Gaussiana centrada en 128)
            val weightTable = FloatArray(256) { i ->
                val dist = (i - 128f) / 128f
                exp(-dist * dist * 4.0f) // Mayor peso al centro
            }

            for (bmp in bitmaps) {
                val pixels = IntArray(size)
                bmp.getPixels(pixels, 0, width, 0, 0, width, height)
                
                for (i in 0 until size) {
                    val p = pixels[i]
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    
                    // Luminancia para el peso
                    val lum = ((0.299f * r) + (0.587f * g) + (0.114f * b)).toInt().coerceIn(0, 255)
                    val w = weightTable[lum]
                    
                    accumR[i] += r * w
                    accumG[i] += g * w
                    accumB[i] += b * w
                    accumW[i] += w
                }
                bmp.recycle()
            }

            val resultPixels = IntArray(size)
            for (i in 0 until size) {
                val w = if (accumW[i] > 0) accumW[i] else 1.0f
                val r = (accumR[i] / w).toInt().coerceIn(0, 255)
                val g = (accumG[i] / w).toInt().coerceIn(0, 255)
                val b = (accumB[i] / w).toInt().coerceIn(0, 255)
                resultPixels[i] = Color.rgb(r, g, b)
            }

            resultBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
            
            // Comprimir a JPEG
            val outputStream = ByteArrayOutputStream()
            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            val finalBytes = outputStream.toByteArray()
            
            resultBitmap.recycle()
            Log.d(TAG, "Fusión HDR completada")
            return@withContext finalBytes

        } catch (e: Exception) {
            Log.e(TAG, "Error fusionando HDR", e)
            return@withContext images[images.size / 2]
        }
    }

    private fun calculateEvSteps(steps: Int, evStep: Float): List<Float> {
        val half = steps / 2
        return (-half..half).map { it * evStep }
    }

    private fun calculateExposureForEv(baseExposure: Long, ev: Float): Long {
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
