package com.example.wallpaperchanger.utils

import android.content.Context
import android.net.Uri

object MediaUtils {
    fun isVideo(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType?.startsWith("video") == true
    }
}