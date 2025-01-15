package com.example.wallpaperchanger.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object StorageUtils {
    private const val MAX_STORAGE_SIZE_BYTES = 100 * 1024 * 1024 // 100MB limit
    private const val COMPRESSION_QUALITY = 85 // Good balance of quality and size

    fun copyImageToLocalStorage(context: Context, uri: Uri): Uri? {
        try {
            if (getCurrentStorageSize(context) > MAX_STORAGE_SIZE_BYTES) {
                cleanupOldestImages(context)
            }

            val fileName = "wallpaper_${System.currentTimeMillis()}.jpg"
            val outputFile = File(context.getExternalFilesDir(null), fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                FileOutputStream(outputFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, output)
                }
            }

            Log.d("StorageDebug", "Successfully copied image to: ${outputFile.absolutePath}")
            return Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e("StorageDebug", "Failed to copy image", e)
            return null
        }
    }

    private fun getCurrentStorageSize(context: Context): Long {
        return context.getExternalFilesDir(null)?.walkTopDown()
            ?.filter { it.name.startsWith("wallpaper_") }
            ?.map { it.length() }
            ?.sum() ?: 0
    }

    private fun cleanupOldestImages(context: Context) {
        val files = context.getExternalFilesDir(null)
            ?.listFiles { file -> file.name.startsWith("wallpaper_") }
            ?.sortedBy { it.lastModified() }

        files?.take(5)?.forEach { file ->
            if (file.delete()) {
                Log.d("StorageDebug", "Cleaned up old image: ${file.name}")
            }
        }
    }

    fun cleanupUnusedImages(context: Context, savedUris: List<Uri>) {
        val storageDir = context.getExternalFilesDir(null)

        storageDir?.listFiles()?.forEach { file ->
            if (file.name.startsWith("wallpaper_")) {
                val fileUri = Uri.fromFile(file)
                if (fileUri !in savedUris) {
                    if (file.delete()) {
                        Log.d("StorageDebug", "Deleted unused image: ${file.name}")
                    }
                }
            }
        }
    }
}
