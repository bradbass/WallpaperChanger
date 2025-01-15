package com.example.wallpaperchanger.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object StorageUtils {
    private const val MAX_STORAGE_SIZE_BYTES = 1000 * 1024 * 1024 // 100MB limit

    fun copyImageToLocalStorage(context: Context, uri: Uri): Uri? {
        try {
            if (getCurrentStorageSize(context) > MAX_STORAGE_SIZE_BYTES) {
                cleanupOldestImages(context)
            }

            val originalFileName = getOriginalFileName(context, uri)
            val outputFile = File(context.getExternalFilesDir(null), originalFileName)

            // If file already exists, return its URI
            if (outputFile.exists()) {
                return Uri.fromFile(outputFile)
            }

            // Copy new file
            FileOutputStream(outputFile).use { output ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(output)
                }
            }

            return Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e("StorageDebug", "Failed to copy image", e)
            return null
        }
    }

    private fun getOriginalFileName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
        } ?: "wallpaper_${System.currentTimeMillis()}.jpg"
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
