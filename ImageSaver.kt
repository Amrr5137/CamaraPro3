package com.example.miappcamarapro3.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utilidad mejorada para guardar imágenes con soporte para:
 * - MediaStore (Android 10+)
 * - Directorio de Pictures
 * - Metadatos EXIF
 * - Múltiples formatos
 */
class ImageSaver(private val context: Context) {

    companion object {
        private const val DIRECTORY_NAME = "MultiCameraPro"
        private const val DEFAULT_QUALITY = 95
    }

    data class SaveResult(
        val success: Boolean,
        val filePath: String? = null,
        val uri: Uri? = null,
        val error: String? = null
    )

    /**
     * Guarda bitmap en almacenamiento público usando MediaStore (Android 10+)
     * o directorio de Pictures para versiones anteriores
     */
    suspend fun saveToGallery(
        bitmap: Bitmap,
        fileName: String = generateFileName(),
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_QUALITY
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            val mimeType = when (format) {
                Bitmap.CompressFormat.JPEG -> "image/jpeg"
                Bitmap.CompressFormat.PNG -> "image/png"
                Bitmap.CompressFormat.WEBP -> "image/webp"
                else -> "image/jpeg"
            }

            val extension = when (format) {
                Bitmap.CompressFormat.JPEG -> "jpg"
                Bitmap.CompressFormat.PNG -> "png"
                Bitmap.CompressFormat.WEBP -> "webp"
                else -> "jpg"
            }

            val finalFileName =
                if (fileName.endsWith(".$extension")) fileName else "$fileName.$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Usar MediaStore para Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$DIRECTORY_NAME"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { imageUri ->
                    resolver.openOutputStream(imageUri)?.use { stream ->
                        bitmap.compress(format, quality, stream)
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)

                    SaveResult(success = true, uri = imageUri, filePath = imageUri.toString())
                } ?: SaveResult(success = false, error = "No se pudo crear URI en MediaStore")
            } else {
                // Usar directorio de Pictures para Android 9 y anteriores
                val picturesDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, DIRECTORY_NAME).apply { mkdirs() }
                val imageFile = File(appDir, finalFileName)

                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(format, quality, out)
                }

                // Escanear archivo para que aparezca en galería
                scanFile(imageFile)

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    imageFile
                )

                SaveResult(success = true, filePath = imageFile.absolutePath, uri = contentUri)
            }
        } catch (e: Exception) {
            SaveResult(success = false, error = e.message ?: "Error desconocido")
        }
    }

    /**
     * Guarda en directorio privado de la app
     */
    suspend fun saveToAppDirectory(
        bitmap: Bitmap,
        fileName: String = generateFileName(),
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_QUALITY
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            val directory =
                File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIRECTORY_NAME)
                    .apply { mkdirs() }

            val extension = when (format) {
                Bitmap.CompressFormat.JPEG -> "jpg"
                Bitmap.CompressFormat.PNG -> "png"
                Bitmap.CompressFormat.WEBP -> "webp"
                else -> "jpg"
            }

            val finalFileName =
                if (fileName.endsWith(".$extension")) fileName else "$fileName.$extension"
            val file = File(directory, finalFileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(format, quality, out)
            }

            SaveResult(success = true, filePath = file.absolutePath)
        } catch (e: Exception) {
            SaveResult(success = false, error = e.message)
        }
    }

    /**
     * Guarda archivo RAW/DNG
     */
    suspend fun saveRawFile(
        rawData: ByteArray,
        fileName: String = generateFileName("RAW")
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            val directory =
                File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIRECTORY_NAME)
                    .apply { mkdirs() }

            val finalFileName = if (fileName.endsWith(".raw")) fileName else "$fileName.raw"
            val file = File(directory, finalFileName)

            FileOutputStream(file).use { out ->
                out.write(rawData)
            }

            SaveResult(success = true, filePath = file.absolutePath)
        } catch (e: Exception) {
            SaveResult(success = false, error = e.message)
        }
    }

    /**
     * Guarda con metadatos EXIF (orientación, etc.)
     */
    suspend fun saveWithExif(
        bitmap: Bitmap,
        exifOrientation: Int = 1, // Normal orientation
        fileName: String = generateFileName(),
        quality: Int = DEFAULT_QUALITY
    ): SaveResult = withContext(Dispatchers.IO) {
        val result = saveToGallery(bitmap, fileName, Bitmap.CompressFormat.JPEG, quality)

        if (result.success && result.filePath != null && !result.filePath.startsWith("content://")) {
            try {
                val exif = androidx.exifinterface.media.ExifInterface(result.filePath)
                exif.setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    exifOrientation.toString()
                )
                exif.saveAttributes()
            } catch (e: Exception) {
                // EXIF no crítico, continuar
            }
        }

        result
    }

    /**
     * Genera nombre de archivo con timestamp
     */
    fun generateFileName(prefix: String = "IMG"): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "${prefix}_$timestamp"
    }

    /**
     * Obtiene lista de imágenes guardadas
     */
    fun getSavedImages(): List<File> {
        val directory =
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIRECTORY_NAME)
        return directory.listFiles { file ->
            file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "raw")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Elimina imagen guardada
     */
    fun deleteImage(filePath: String): Boolean {
        return File(filePath).delete()
    }

    /**
     * Comparte imagen
     */
    fun getShareUri(filePath: String): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(filePath)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun scanFile(file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
            null
        )
    }
}
