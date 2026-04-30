package com.example.miappcamarapro3.filters

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestiona captura de time-lapse y generación de video
 */
class TimeLapseManager(
    private val cameraDevice: CameraDevice,
    private val backgroundHandler: Handler
) {

    companion object {
        private const val TAG = "TimeLapseManager"
        private const val DEFAULT_FPS = 30
        private const val DEFAULT_VIDEO_BITRATE = 8_000_000
    }

    data class TimeLapseConfig(
        val intervalMs: Long = 1000,           // Intervalo entre fotos (ms)
        val durationMinutes: Int = 1,           // Duración total de captura
        val outputFps: Int = 30,                // FPS del video resultante
        val resolution: Size = Size(1920, 1080), // Resolución
        val quality: Quality = Quality.HIGH
    )

    enum class Quality { LOW, MEDIUM, HIGH, ULTRA }

    private var isRecording = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var frameCount = 0
    private var startTime = 0L

    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null

    // Lista de frames capturados
    private val capturedFrames = mutableListOf<Bitmap>()

    /**
     * Inicia captura de time-lapse
     */
    suspend fun startTimeLapse(
        previewSurface: Surface,
        config: TimeLapseConfig,
        onProgress: (Int, Int) -> Unit,      // (frames capturados, total estimado)
        onFrameCaptured: (Bitmap) -> Unit,    // Callback por frame
        onComplete: (List<Bitmap>) -> Unit    // Callback al finalizar
    ) = withContext(Dispatchers.IO) {

        isRecording.set(true)
        frameCount = 0
        capturedFrames.clear()
        startTime = System.currentTimeMillis()

        // Configurar ImageReader
        imageReader = ImageReader.newInstance(
            config.resolution.width,
            config.resolution.height,
            ImageFormat.JPEG,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                // Procesar frame
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    capturedFrames.add(bitmap)
                    frameCount++
                    onFrameCaptured(bitmap)
                    onProgress(frameCount, calculateTotalFrames(config))
                }
            }, backgroundHandler)
        }

        // Crear sesión de captura
        setupCaptureSession(previewSurface, imageReader!!.surface)

        // Loop de captura
        val totalFrames = calculateTotalFrames(config)
        captureJob = launch {
            while (isRecording.get() && frameCount < totalFrames) {
                captureFrame()
                delay(config.intervalMs)
            }
            
            // Finalizar
            stopTimeLapse()
            onComplete(capturedFrames.toList())
        }
    }

    /**
     * Detiene la captura
     */
    fun stopTimeLapse() {
        isRecording.set(false)
        captureJob?.cancel()
        captureSession?.close()
        imageReader?.close()
    }

    /**
     * Genera video MP4 desde los frames capturados
     */
    suspend fun generateVideo(
        frames: List<Bitmap>,
        outputFile: File,
        config: TimeLapseConfig
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val width = config.resolution.width
            val height = config.resolution.height
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, getBitrate(config.quality, width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, config.outputFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var isMuxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            var presentationTimeUs = 0L
            val frameIntervalUs = 1_000_000L / config.outputFps

            // Procesar frames
            frames.forEachIndexed { index, bitmap ->
                // Dibujar bitmap en input surface
                val canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), null)
                inputSurface.unlockCanvasAndPost(canvas)

                // Obtener output buffer
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferId >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Configuración del codec
                        codec.releaseOutputBuffer(outputBufferId, false)
                    } else {
                        if (!isMuxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            isMuxerStarted = true
                        }

                        bufferInfo.presentationTimeUs = presentationTimeUs
                        outputBuffer?.let { muxer.writeSampleData(trackIndex, it, bufferInfo) }
                        presentationTimeUs += frameIntervalUs
                        
                        codec.releaseOutputBuffer(outputBufferId, false)
                    }
                }
            }

            // Finalizar
            codec.signalEndOfInputStream()
            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error generando video", e)
            false
        }
    }

    private fun setupCaptureSession(previewSurface: Surface, readerSurface: Surface) {
        val surfaces = listOf(previewSurface, readerSurface)
        
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                // Preview continuo
                val previewRequest = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
                ).apply {
                    addTarget(previewSurface)
                }.build()
                session.setRepeatingRequest(previewRequest, null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Fallo configuración sesión")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputConfigs = surfaces.map { OutputConfiguration(it) }
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                { command -> backgroundHandler.post(command) },
                callback
            )
            cameraDevice.createCaptureSession(sessionConfiguration)
        } else {
            @Suppress("DEPRECATION")
            cameraDevice.createCaptureSession(surfaces, callback, backgroundHandler)
        }
    }

    private fun captureFrame() {
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return
        
        try {
            val captureRequest = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            
            session.capture(captureRequest, null, backgroundHandler)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cámara cerrada detectada durante captura", e)
            stopTimeLapse()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturando frame", e)
        }
    }

    private fun calculateTotalFrames(config: TimeLapseConfig): Int {
        return ((config.durationMinutes * 60 * 1000) / config.intervalMs).toInt()
    }

    private fun getBitrate(quality: Quality, width: Int, height: Int): Int {
        val pixels = width * height
        return when (quality) {
            Quality.LOW -> pixels * 2
            Quality.MEDIUM -> pixels * 4
            Quality.HIGH -> pixels * 8
            Quality.ULTRA -> pixels * 16
        }
    }

    /**
     * Calcula duración del video resultante
     */
    fun calculateVideoDuration(config: TimeLapseConfig): Float {
        val totalFrames = calculateTotalFrames(config)
        return totalFrames.toFloat() / config.outputFps
    }

    /**
     * Estima tamaño de archivo
     */
    fun estimateFileSize(config: TimeLapseConfig): Long {
        val duration = calculateVideoDuration(config)
        val bitrate = getBitrate(config.quality, config.resolution.width, config.resolution.height)
        return (duration * bitrate / 8).toLong()
    }
}