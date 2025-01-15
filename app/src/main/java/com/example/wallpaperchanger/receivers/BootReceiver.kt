package com.example.wallpaperchanger.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.wallpaperchanger.services.WallpaperService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPreferences = context?.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val expectedCount = sharedPreferences?.getInt("wallpaper_count", 0) ?: 0
            val allUris = mutableListOf<Uri>()
            var index = 0

            while (allUris.size < expectedCount) {
                val key = "wallpapers_$index"
                val uriStrings = sharedPreferences?.getStringSet(key, null) ?: break
                allUris.addAll(uriStrings.map { Uri.parse(it) })
                index++
            }

            val interval = sharedPreferences?.getLong("interval", 5 * 60 * 1000) ?: (5 * 60 * 1000)

            if (allUris.isNotEmpty()) {
                val serviceIntent = Intent(context, WallpaperService::class.java).apply {
                    putParcelableArrayListExtra("imageUris", ArrayList(allUris))
                    putExtra("interval", interval)
                }
                context?.let {
                    ContextCompat.startForegroundService(it, serviceIntent)
                }
            }
        }
    }
}
