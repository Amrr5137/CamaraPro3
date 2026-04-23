package com.example.miappcamarapro3.filters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Gestiona la carga y aplicación de LUTs (Look-Up Tables) 3D
 * para grading cinematográfico profesional
 */
class LutsManager(private val context: Context) {

    companion object {
        private const val LUTS_FOLDER = "luts"
        private const val DEFAULT_LUT_SIZE = 64
    }

    private val lutsFolder: File by lazy {
        File(context.filesDir, LUTS_FOLDER).apply { mkdirs() }
    }

    data class LutInfo(
        val name: String,
        val filePath: String,
        val thumbnailPath: String? = null,
        val category: String = "Custom"
    )

    /**
     * Carga un LUT desde un archivo PNG/CUBE
     */
    suspend fun loadLutFromUri(uri: Uri, name: String): Result<LutInfo> =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    return@withContext Result.failure(IOException("No se pudo decodificar la imagen"))
                }

                // Validar dimensiones (debe ser 64x4096 o similar)
                if (!isValidLutDimensions(bitmap.width, bitmap.height)) {
                    bitmap.recycle()
                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "Dimensiones inválidas. Se esperaba ${DEFAULT_LUT_SIZE}x${DEFAULT_LUT_SIZE * DEFAULT_LUT_SIZE}"
                        )
                    )
                }

                // Guardar en almacenamiento interno
                val lutFile = File(lutsFolder, "$name.png")
                FileOutputStream(lutFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()

                val lutInfo = LutInfo(
                    name = name,
                    filePath = lutFile.absolutePath
                )

                Result.success(lutInfo)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Carga LUTs predefinidos desde assets
     */
    suspend fun loadPresetLuts(): List<LutInfo> = withContext(Dispatchers.IO) {
        val presets = mutableListOf<LutInfo>()

        try {
            val assetFiles = context.assets.list("luts") ?: emptyArray()

            assetFiles.filter { it.endsWith(".png") || it.endsWith(".cube") }
                .forEach { fileName ->
                    val name = fileName.substringBeforeLast(".")
                    presets.add(
                        LutInfo(
                            name = name,
                            filePath = "assets://luts/$fileName",
                            category = "Preset"
                        )
                    )
                }
        } catch (e: Exception) {
            // No hay LUTs en assets
        }

        presets
    }

    /**
     * Obtiene todos los LUTs disponibles
     */
    fun getAvailableLuts(): List<LutInfo> {
        val luts = mutableListOf<LutInfo>()

        // LUTs guardados por el usuario
        lutsFolder.listFiles { file ->
            file.extension == "png" || file.extension == "cube"
        }?.forEach { file ->
            luts.add(
                LutInfo(
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    category = "Custom"
                )
            )
        }

        return luts
    }

    /**
     * Carga el bitmap de un LUT
     */
    fun loadLutBitmap(lutInfo: LutInfo): Bitmap? {
        return when {
            lutInfo.filePath.startsWith("assets://") -> {
                val assetPath = lutInfo.filePath.removePrefix("assets://")
                try {
                    context.assets.open(assetPath).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }
            }

            else -> {
                BitmapFactory.decodeFile(lutInfo.filePath)
            }
        }
    }

    /**
     * Elimina un LUT
     */
    fun deleteLut(lutInfo: LutInfo): Boolean {
        return if (!lutInfo.filePath.startsWith("assets://")) {
            File(lutInfo.filePath).delete()
        } else {
            false
        }
    }

    /**
     * Renombra un LUT
     */
    fun renameLut(lutInfo: LutInfo, newName: String): LutInfo? {
        if (lutInfo.filePath.startsWith("assets://")) return null

        val oldFile = File(lutInfo.filePath)
        val newFile = File(oldFile.parent, "$newName.png")

        return if (oldFile.renameTo(newFile)) {
            lutInfo.copy(name = newName, filePath = newFile.absolutePath)
        } else null
    }

    private fun isValidLutDimensions(width: Int, height: Int): Boolean {
        // Formato estándar: 64x4096 (64x64x64 aplanado)
        // O variantes: 512x512, 1024x32, etc.
        return width == DEFAULT_LUT_SIZE && height == DEFAULT_LUT_SIZE * DEFAULT_LUT_SIZE
    }

    /**
     * Aplica LUT a un bitmap usando el procesador avanzado
     */
    fun applyLutToBitmap(
        bitmap: Bitmap,
        lutBitmap: Bitmap,
        processor: AdvancedFilterProcessor
    ): Bitmap {
        return processor.applyLut(bitmap, lutBitmap)
    }
}
