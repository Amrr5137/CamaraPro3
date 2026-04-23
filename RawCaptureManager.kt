package com.example.miappcamarapro3.camara

import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gestiona captura RAW (DNG) para máxima calidad de post-procesado
 * Requiere cámara con soporte RAW (Xiaomi 12 5G lo soporta)
 */
class RawCaptureManager(
    private val cameraDevice: CameraDevice,
    private val backgroundHandler: Handler
) {
    companion object {
        private const val TAG = "RawCaptureManager"
    }

    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null

    // Pendientes para sincronizar RAW y JPEG
    private var pendingRawBytes: ByteArray? = null
    private var pendingJpegBytes: ByteArray? = null
    private val pendingLock = Any()

    /**
     * Verifica si la cámara soporta captura RAW
     */
    fun isRawSupported(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return false

        return capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        )
    }

    /**
     * Configura sesión para captura RAW + JPEG simultáneo
     */
    suspend fun setupRawCaptureSession(
        previewSurface: Surface,
        jpegSize: Size,
        rawSize: Size,
        onRawCaptured: (ByteArray, ByteArray) -> Unit // RAW, JPEG
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        // ImageReader para JPEG
        jpegImageReader = ImageReader.newInstance(
            jpegSize.width, jpegSize.height,
            android.graphics.ImageFormat.JPEG, 2 // Reducido a 2 para ahorrar memoria
        )

        // ImageReader para RAW (DNG)
        rawImageReader = ImageReader.newInstance(
            rawSize.width, rawSize.height,
            android.graphics.ImageFormat.RAW_SENSOR, 2 // Reducido a 2
        )

        val rawListener = ImageReader.OnImageAvailableListener { reader ->
            val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
            val rawBytes = imageToByteArray(image)
            image.close()

            // Sincronizar con JPEG
            synchronized(pendingLock) {
                pendingRawBytes = rawBytes
                if (pendingJpegBytes != null) {
                    val jpeg = pendingJpegBytes!!
                    val raw = pendingRawBytes!!
                    pendingRawBytes = null
                    pendingJpegBytes = null
                    onRawCaptured(raw, jpeg)
                }
            }
        }

        val jpegListener = ImageReader.OnImageAvailableListener { reader ->
            val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
            val jpegBytes = imageToByteArray(image)
            image.close()

            synchronized(pendingLock) {
                pendingJpegBytes = jpegBytes
                if (pendingRawBytes != null) {
                    val raw = pendingRawBytes!!
                    val jpeg = pendingJpegBytes!!
                    pendingRawBytes = null
                    pendingJpegBytes = null
                    onRawCaptured(raw, jpeg)
                }
            }
        }

        rawImageReader?.setOnImageAvailableListener(rawListener, backgroundHandler)
        jpegImageReader?.setOnImageAvailableListener(jpegListener, backgroundHandler)

        val surfaces = listOf(
            previewSurface,
            jpegImageReader!!.surface,
            rawImageReader!!.surface
        )

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
                            // Iniciar el preview para que el usuario vea qué captura
                            val previewRequest =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequest.addTarget(previewSurface)
                            session.setRepeatingRequest(
                                previewRequest.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(TAG, "Preview de RAW iniciado")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando preview en modo RAW", e)
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.cancel(Exception("Fallo configuración RAW"))
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
                            // Iniciar el preview para que el usuario vea qué captura
                            val previewRequest =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequest.addTarget(previewSurface)
                            session.setRepeatingRequest(
                                previewRequest.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(TAG, "Preview de RAW iniciado")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando preview en modo RAW", e)
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.cancel(Exception("Fallo configuración RAW"))
                    }
                },
                backgroundHandler
            )
        }
    }

    /**
     * Captura RAW + JPEG
     */
    fun captureRawDng(orientation: Int) {
        val session = captureSession ?: run {
            Log.e(TAG, "No hay sesión de captura activa para RAW")
            return
        }

        try {
            val requestBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                // JPEG
                addTarget(jpegImageReader!!.surface)
                // RAW
                addTarget(rawImageReader!!.surface)

                // Configuración para RAW
                set(
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                )

                // Usar auto-exposición para evitar fotos negras/quemadas
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )

                // Orientación
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
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
                        Log.d(TAG, "Iniciando captura RAW+JPEG...")
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "RAW+JPEG capturado exitosamente")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Fallo en captura RAW: ${failure.reason}")
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error durante la solicitud de captura RAW", e)
        }
    }

    /**
     * Convierte Image a ByteArray
     */
    private fun imageToByteArray(image: Image): ByteArray {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    /**
     * Guarda RAW como archivo DNG
     * En una implementación real, aquí se usaría DngCreator
     */
    fun saveRawAsDng(rawBytes: ByteArray, file: File, width: Int, height: Int) {
        try {
            FileOutputStream(file).use { it.write(rawBytes) }
            Log.d(TAG, "RAW guardado en ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando RAW", e)
        }
    }

    fun release() {
        captureSession?.close()
        rawImageReader?.close()
        jpegImageReader?.close()
    }
}

