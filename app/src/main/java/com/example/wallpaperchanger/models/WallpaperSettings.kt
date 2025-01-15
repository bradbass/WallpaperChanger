package com.example.wallpaperchanger.models

import android.net.Uri

data class WallpaperSettings(
    val imageUris: List<Uri>,
    val interval: Long = 5 * 60 * 1000,
    val transitionsEnabled: Boolean = true,
    val transitionType: Int = 0,
    val wallpaperCount: Int = 0
) {
    companion object {
        const val DEFAULT_INTERVAL = 5 * 60 * 1000L
        const val PREFS_NAME = "AppSettings"
        const val KEY_TRANSITION_ENABLED = "transitions_enabled"
        const val KEY_TRANSITION_TYPE = "transition_type"
        const val KEY_INTERVAL = "interval"
        const val KEY_WALLPAPER_COUNT = "wallpaper_count"
    }
}
