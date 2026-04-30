package com.example.miappcamarapro3.filters

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gestiona astrofotografía: exposiciones largas + apilado de fotos
 */
class AstrophotographyManager(
    private val cameraDevice: CameraDevice,
    private val characteristics: CameraCharacteristics,
    private val backgroundHandler: Handler
) {

    companion object {
        private const val TAG = "AstroPhotoManager"
        private const val DEFAULT_EXPOSURE_MS = 15000L // 15 segundos
        private const val MAX_EXPOSURE_MS = 30000L     // 30 segundos límite
        const val STACKING_ITERATIONS = 10      // Fotos a apilar
    }

    data class AstroConfig(
        val exposureTimeMs: Long = DEFAULT_EXPOSURE_MS,
        val iso: Int = 3200,
        val focusDistance: Float = 0f, // 0 = infinito
        val stackingCount: Int = STACKING_ITERATIONS,
        val darkFrameEnabled: Boolean = true, // Restar ruido de sensor
        val alignmentEnabled: Boolean = true   // Alinear estrellas entre fotos
    )

    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false

    // Frames para apilado
    private val lightFrames = mutableListOf<Bitmap>()   // Fotos de la escena
    private var darkFrame: Bitmap? = null                 // Foto de ruido de sensor

    /**
     * Inicia secuencia de astrofotografía
     */
    suspend fun captureAstroSequence(
        previewSurface: Surface,
        config: AstroConfig,
        onProgress: (String, Int, Int) -> Unit,  // (etapa, actual, total)
        onComplete: (Bitmap) -> Unit             // Resultado final
    ) = withContext(Dispatchers.IO) {

        isCapturing = true

        try {
            // 1. Configurar sesión en modo manual
            setupAstroSession(previewSurface, config)

            // 2. Capturar dark frame (ruido de sensor) si está habilitado
            if (config.darkFrameEnabled) {
                onProgress("Capturando dark frame...", 0, 1)
                darkFrame = captureSingleFrame(config, withLensCap = true)
            }

            // 3. Capturar light frames (fotos de la escena)
            lightFrames.clear()
            repeat(config.stackingCount) { index ->
                if (!isCapturing) return@withContext

                onProgress("Capturando foto ${index + 1}/${config.stackingCount}", index + 1, config.stackingCount)
                val frame = captureSingleFrame(config, withLensCap = false)
                
                if (frame != null) {
                    lightFrames.add(frame)
                }
                
                // Pequeña pausa entre capturas para evitar sobrecalentamiento
                delay(500)
            }

            // 4. Apilar fotos
            onProgress("Procesando apilado...", 0, 1)
            val stackedImage = if (config.alignmentEnabled) {
                alignAndStack(lightFrames, darkFrame)
            } else {
                simpleStack(lightFrames, darkFrame)
            }

            // 5. Post-procesado astro
            onProgress("Aplicando post-procesado...", 0, 1)
            val finalImage = postProcessAstro(stackedImage)

            withContext(Dispatchers.Main) {
                onComplete(finalImage)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en astrofotografía", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Detiene la captura
     */
    fun stopCapture() {
        isCapturing = false
    }

    /**
     * Captura un solo frame con settings astro
     */
    private suspend fun captureSingleFrame(
        config: AstroConfig,
        withLensCap: Boolean
    ): Bitmap? = suspendCancellableCoroutine { continuation ->

        val reader = imageReader ?: run {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val listener = ImageReader.OnImageAvailableListener { r ->
            val image = r.acquireLatestImage() ?: return@OnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            continuation.resume(bitmap)
        }

        reader.setOnImageAvailableListener(listener, backgroundHandler)

        // Configurar request de captura
        val captureRequest = cameraDevice.createCaptureRequest(
            CameraDevice.TEMPLATE_MANUAL
        ).apply {
            addTarget(reader.surface)
            
            // Obtener rangos para evitar valores ilegales
            val expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

            // Settings astro
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            
            val targetIso = sensitivityRange?.clamp(config.iso) ?: config.iso
            set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            
            val targetExpNs = expRange?.clamp(config.exposureTimeMs * 1_000_000) ?: (config.exposureTimeMs * 1_000_000)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExpNs)
            
            set(CaptureRequest.LENS_FOCUS_DISTANCE, config.focusDistance)
            
            Log.d(TAG, "Capturando frame con ISO: $targetIso, Exposure: ${targetExpNs / 1_000_000}ms")
            
            // Reducir ruido
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            
            // Si es dark frame, tapar lente (simulado con ISO mínimo y exposición corta)
            if (withLensCap) {
                set(CaptureRequest.SENSOR_SENSITIVITY, 100)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000) // 1ms
            }
        }.build()

        captureSession?.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                continuation.resume(null)
            }
        }, backgroundHandler)
    }

    /**
     * Apilado simple (promedio de frames)
     */
    private fun simpleStack(frames: List<Bitmap>, dark: Bitmap?): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("No hay frames para apilar")

        val width = frames[0].width
        val height = frames[0].height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Sumar todos los frames
        val sumR = Array(width) { IntArray(height) }
        val sumG = Array(width) { IntArray(height) }
        val sumB = Array(width) { IntArray(height) }

        for (frame in frames) {
            val pixels = IntArray(width * height)
            frame.getPixels(pixels, 0, width, 0, 0, width, height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    sumR[x][y] += android.graphics.Color.red(pixel)
                    sumG[x][y] += android.graphics.Color.green(pixel)
                    sumB[x][y] += android.graphics.Color.blue(pixel)
                }
            }
        }

        // Restar dark frame si existe
        dark?.let { darkFrame ->
            val darkPixels = IntArray(width * height)
            darkFrame.getPixels(darkPixels, 0, width, 0, 0, width, height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val darkPixel = darkPixels[y * width + x]
                    sumR[x][y] -= android.graphics.Color.red(darkPixel)
                    sumG[x][y] -= android.graphics.Color.green(darkPixel)
                    sumB[x][y] -= android.graphics.Color.blue(darkPixel)
                }
            }
        }

        // Promediar y crear resultado
        val count = frames.size
        val resultPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = max(0, min(255, sumR[x][y] / count))
                val g = max(0, min(255, sumG[x][y] / count))
                val b = max(0, min(255, sumB[x][y] / count))
                resultPixels[y * width + x] = android.graphics.Color.rgb(r, g, b)
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Apilado con alineación de estrellas (simplificado)
     */
    private fun alignAndStack(frames: List<Bitmap>, dark: Bitmap?): Bitmap {
        // Simplificación: usar apilado simple
        // En producción usar algoritmos de alineación como:
        // - Phase correlation
        // - Feature matching (ORB, SIFT)
        // - Star detection + centroid matching
        
        return simpleStack(frames, dark)
    }

    /**
     * Post-procesado específico para astrofotografía
     */
    private fun postProcessAstro(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Stretch de histograma (mejorar contraste)
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val lum = luminance(pixel)
            histogram[lum]++
        }

        // Encontrar percentiles 1% y 99%
        val totalPixels = width * height
        var minLum = 0
        var maxLum = 255
        var accumulated = 0

        for (i in 0..255) {
            accumulated += histogram[i]
            if (accumulated > totalPixels * 0.01) {
                minLum = i
                break
            }
        }

        accumulated = 0
        for (i in 255 downTo 0) {
            accumulated += histogram[i]
            if (accumulated > totalPixels * 0.01) {
                maxLum = i
                break
            }
        }

        // Aplicar stretch
        val range = maxLum - minLum
        val resultPixels = IntArray(width * height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = stretch(android.graphics.Color.red(pixel), minLum, range)
            val g = stretch(android.graphics.Color.green(pixel), minLum, range)
            val b = stretch(android.graphics.Color.blue(pixel), minLum, range)
            resultPixels[i] = android.graphics.Color.rgb(r, g, b)
        }

        // 2. Reducir fondo de cielo (gradiente)
        val bgSubtracted = subtractSkyGradient(resultPixels, width, height)

        // 3. Aumentar saturación de nebulosas
        val saturated = boostNebulaColors(bgSubtracted, width, height)

        result.setPixels(saturated, 0, width, 0, 0, width, height)
        return result
    }

    private fun luminance(pixel: Int): Int {
        return (0.299 * android.graphics.Color.red(pixel) +
                0.587 * android.graphics.Color.green(pixel) +
                0.114 * android.graphics.Color.blue(pixel)).toInt()
    }

    private fun stretch(value: Int, min: Int, range: Int): Int {
        return if (range > 0) {
            max(0, min(255, ((value - min) * 255 / range)))
        } else {
            value
        }
    }

    private fun subtractSkyGradient(pixels: IntArray, width: Int, height: Int): IntArray {
        // Simplificación: restar gradiente lineal del fondo
        // En producción usar modelos más complejos
        
        val result = IntArray(pixels.size)
        
        for (y in 0 until height) {
            val gradientFactor = y.toFloat() / height // Gradiente vertical simple
            
            for (x in 0 until width) {
                val idx = y * width + x
                val pixel = pixels[idx]
                
                val r = max(0, android.graphics.Color.red(pixel) - (gradientFactor * 20).toInt())
                val g = max(0, android.graphics.Color.green(pixel) - (gradientFactor * 20).toInt())
                val b = max(0, android.graphics.Color.blue(pixel) - (gradientFactor * 15).toInt())
                
                result[idx] = android.graphics.Color.rgb(r, g, b)
            }
        }
        
        return result
    }

    private fun boostNebulaColors(pixels: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(pixels.size)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(pixel, hsv)
            
            // Boost saturación en tonos rojos/azules (nebulosas H-alpha/OIII)
            val isNebulaColor = hsv[0] in 0f..30f || hsv[0] in 180f..240f
            
            if (isNebulaColor && hsv[1] > 0.1f) {
                hsv[1] = min(1f, hsv[1] * 1.3f)
                hsv[2] = min(1f, hsv[2] * 1.1f)
            }
            
            result[i] = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(pixel), hsv)
        }
        
        return result
    }

    private suspend fun setupAstroSession(previewSurface: Surface, config: AstroConfig) = suspendCancellableCoroutine<Unit> { continuation ->
        // Obtener la mejor resolución soportada para JPEG, pero limitada a ~16MP para evitar OOM durante el apilado
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val bestSize = map?.getOutputSizes(ImageFormat.JPEG)
            ?.filter { it.width * it.height <= 16_000_000 }
            ?.maxByOrNull { it.width * it.height } 
            ?: Size(4032, 3024)

        imageReader = ImageReader.newInstance(
            bestSize.width, bestSize.height, ImageFormat.JPEG, 2
        )

        val surfaces = listOf(previewSurface, imageReader!!.surface)

        Log.d(TAG, "Configurando sesión Astro con resolución optimizada: ${bestSize.width}x${bestSize.height}")

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                
                // Preview con settings astro
                try {
                    val previewRequest = cameraDevice.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW
                    ).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY, config.iso)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100_000_000) // 100ms preview
                    }.build()
                    
                    session.setRepeatingRequest(previewRequest, null, backgroundHandler)
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Error configurando el preview de astro", e)
                }

                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Fallo configuración sesión astro")
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    { command -> backgroundHandler.post(command) },
                    callback
                )
                cameraDevice.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                cameraDevice.createCaptureSession(surfaces, callback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error al crear la sesión de captura", e)
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private fun cleanup() {
        captureSession?.close()
        imageReader?.close()
        lightFrames.forEach { it.recycle() }
        lightFrames.clear()
        darkFrame?.recycle()
        darkFrame = null
        isCapturing = false
    }

    /**
     * Verifica si el dispositivo soporta astrofotografía
     */
    fun isLongExposureSupported(): Boolean {
        val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxExposureNs = range?.upper ?: 0L
        
        // Xiaomi 12 es un dispositivo de gama alta (LEVEL_3 o FULL). 
        // Forzamos el soporte para este dispositivo y similares.
        val isXiaomi12Series = Build.MODEL.contains("2201123G", ignoreCase = true) || 
                              Build.DEVICE.contains("cupid", ignoreCase = true)

        Log.d(TAG, "HW Level: $hwLevel, Max Exp: ${maxExposureNs / 1_000_000}ms, Model: ${Build.MODEL}")

        // Si es un Xiaomi 12 o tiene nivel de hardware alto, permitimos el modo
        if (isXiaomi12Series || 
            hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 || 
            hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) {
            return true
        }

        // Fallback: al menos 500ms de exposición
        return maxExposureNs >= 500_000_000L
    }

    /**
     * Obtiene máxima exposición soportada
     */
    fun getMaxExposureMs(): Long {
        val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return (range?.upper ?: 1_000_000_000L) / 1_000_000
    }
}