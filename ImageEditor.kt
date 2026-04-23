package com.example.miappcamarapro3.editing

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.example.miappcamarapro3.filters.AdvancedFilterProcessor
import com.example.miappcamarapro3.filters.FilterProcessor
import com.example.miappcamarapro3.filters.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Editor de imágenes principal que coordina todos los ajustes
 * Implementa patrón non-destructive editing
 */
class ImageEditor(
    private val filterProcessor: FilterProcessor,
    private val advancedProcessor: AdvancedFilterProcessor
) {

    data class EditSettings(
        // Filtro base
        val baseFilter: FilterType = FilterType.NORMAL,

        // Exposición
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val whites: Float = 0f,
        val blacks: Float = 0f,

        // Color
        val temperature: Float = 0f,
        val tint: Float = 0f,
        val vibrance: Float = 0f,
        val saturation: Float = 0f,

        // Detalle
        val sharpness: Float = 0f,
        val clarity: Float = 0f,
        val denoise: Float = 0f,

        // Curvas
        val toneCurve: AdvancedFilterProcessor.ToneCurve? = null,

        // HSL
        val hslAdjustments: Map<AdvancedFilterProcessor.ColorRange, AdvancedFilterProcessor.HslAdjustment> = emptyMap(),

        // Efectos especiales
        val vignetteParams: AdvancedFilterProcessor.VignetteParams? = null,
        val filmGrainIntensity: Float = 0f,
        val bokehParams: BokehParams? = null,
        val tiltShiftParams: TiltShiftParams? = null
    )

    data class BokehParams(
        val blurRadius: Float = 15f,
        val focalX: Float = 0.5f,
        val focalY: Float = 0.5f,
        val focalRadius: Float = 0.3f
    )

    data class TiltShiftParams(
        val blurRadius: Float = 20f,
        val focusY: Float = 0.5f,
        val focusWidth: Float = 0.2f,
        val angle: Float = 0f
    )

    // Historial de ediciones para undo/redo
    private val history = mutableListOf<EditSettings>()
    private var historyIndex = -1
    private val maxHistorySize = 20

    private var originalBitmap: Bitmap? = null
    private var currentSettings = EditSettings()

    /**
     * Inicializa el editor con una imagen
     */
    fun loadImage(bitmap: Bitmap) {
        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        history.clear()
        historyIndex = -1
        currentSettings = EditSettings()
    }

    /**
     * Aplica settings y retorna bitmap procesado
     */
    suspend fun applySettings(settings: EditSettings): Bitmap = withContext(Dispatchers.Default) {
        val base = originalBitmap ?: throw IllegalStateException("No image loaded")

        var result = base

        // 1. Aplicar filtro base
        if (settings.baseFilter != FilterType.NORMAL) {
            result = filterProcessor.applyFilter(result, settings.baseFilter)
        }

        // 2. Aplicar exposición
        if (hasExposureChanges(settings)) {
            result = advancedProcessor.applyExposureAdjustments(
                result,
                exposure = settings.exposure,
                contrast = settings.contrast,
                highlights = settings.highlights,
                shadows = settings.shadows,
                whites = settings.whites,
                blacks = settings.blacks
            )
        }

        // 3. Aplicar color
        if (hasColorChanges(settings)) {
            result = advancedProcessor.applyColorAdjustments(
                result,
                temperature = settings.temperature,
                tint = settings.tint,
                vibrance = settings.vibrance,
                saturation = settings.saturation
            )
        }

        // 4. Aplicar curvas
        settings.toneCurve?.let { curve ->
            if (curve.rgb.isNotEmpty()) {
                result = advancedProcessor.applyToneCurves(result, curve)
            }
        }

        // 5. Aplicar HSL
        if (settings.hslAdjustments.isNotEmpty()) {
            result = advancedProcessor.applyHslAdjustment(result, settings.hslAdjustments)
        }

        // 6. Aplicar detalle
        if (hasDetailChanges(settings)) {
            result = advancedProcessor.applyDetailAdjustments(
                result,
                sharpness = settings.sharpness,
                clarity = settings.clarity,
                denoise = settings.denoise
            )
        }

        // 7. Aplicar efectos especiales
        if (settings.filmGrainIntensity > 0) {
            result = advancedProcessor.applyFilmGrain(result, settings.filmGrainIntensity)
        }

        settings.vignetteParams?.let { vignette ->
            result = advancedProcessor.applyVignette(result, vignette)
        }

        settings.bokehParams?.let { bokeh ->
            result = advancedProcessor.applyBokeh(
                result,
                blurRadius = bokeh.blurRadius,
                focalPoint = android.graphics.PointF(bokeh.focalX, bokeh.focalY),
                focalRadius = bokeh.focalRadius
            )
        }

        settings.tiltShiftParams?.let { tiltShift ->
            result = advancedProcessor.applyTiltShift(
                result,
                blurRadius = tiltShift.blurRadius,
                focusY = tiltShift.focusY,
                focusWidth = tiltShift.focusWidth,
                angle = tiltShift.angle
            )
        }

        result
    }

    /**
     * Actualiza settings y guarda en historial
     */
    fun updateSettings(newSettings: EditSettings) {
        // Guardar estado anterior en historial
        if (historyIndex < history.size - 1) {
            // Eliminar estados futuros si estamos en medio del historial
            history.subList(historyIndex + 1, history.size).clear()
        }

        history.add(currentSettings)
        if (history.size > maxHistorySize) {
            history.removeAt(0)
        }

        currentSettings = newSettings
        historyIndex = history.size - 1
    }

    /**
     * Undo - retrocede un paso en el historial
     */
    fun undo(): EditSettings? {
        if (historyIndex > 0) {
            historyIndex--
            currentSettings = history[historyIndex]
            return currentSettings
        }
        return null
    }

    /**
     * Redo - avanza un paso en el historial
     */
    fun redo(): EditSettings? {
        if (historyIndex < history.size - 1) {
            historyIndex++
            currentSettings = history[historyIndex]
            return currentSettings
        }
        return null
    }

    /**
     * Reset - vuelve a los valores por defecto
     */
    fun reset(): EditSettings {
        currentSettings = EditSettings()
        return currentSettings
    }

    /**
     * Compara si hay cambios respecto a defaults
     */
    fun hasChanges(): Boolean {
        return currentSettings != EditSettings()
    }

    /**
     * Exporta imagen final con calidad especificada
     */
    suspend fun exportImage(
        outputFile: File,
        quality: Int = 95,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val processed = applySettings(currentSettings)
            FileOutputStream(outputFile).use { out ->
                processed.compress(format, quality, out)
            }

            // Copiar EXIF si es JPEG
            if (format == Bitmap.CompressFormat.JPEG) {
                copyExifData(outputFile)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Obtiene preview rápido (calidad reducida para UI)
     */
    suspend fun getPreview(maxDimension: Int = 1080): Bitmap = withContext(Dispatchers.Default) {
        val fullRes = applySettings(currentSettings)

        if (maxOf(fullRes.width, fullRes.height) > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(fullRes.width, fullRes.height)
            val newWidth = (fullRes.width * scale).toInt()
            val newHeight = (fullRes.height * scale).toInt()

            fullRes.scale(newWidth, newHeight).also {
                if (it != fullRes) fullRes.recycle()
            }
        } else {
            fullRes
        }
    }

    private fun hasExposureChanges(settings: EditSettings): Boolean {
        return settings.exposure != 0f || settings.contrast != 0f ||
                settings.highlights != 0f || settings.shadows != 0f ||
                settings.whites != 0f || settings.blacks != 0f
    }

    private fun hasColorChanges(settings: EditSettings): Boolean {
        return settings.temperature != 0f || settings.tint != 0f ||
                settings.vibrance != 0f || settings.saturation != 0f
    }

    private fun hasDetailChanges(settings: EditSettings): Boolean {
        return settings.sharpness != 0f || settings.clarity != 0f || settings.denoise != 0f
    }

    private fun copyExifData(outputFile: File) {
        // Implementar copia de metadatos EXIF si es necesario
    }

    fun release() {
        originalBitmap?.recycle()
        originalBitmap = null
    }
}
