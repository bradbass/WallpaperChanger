package com.example.wallpaperchanger.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.app.WallpaperManager
import com.example.wallpaperchanger.MainActivity
import com.example.wallpaperchanger.R
import java.io.File
import java.io.IOException

class WallpaperService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val imageUris = mutableListOf<Uri>()
    private var interval: Long = 5 * 60 * 1000

    private val wallpaperRunnable = object : Runnable {
        override fun run() {
            changeWallpaper()
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("WallpaperService", "onCreate called")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("WallpaperService", "onStartCommand called")
        intent?.let { processIntent(it) }
        startForegroundService()
        return START_STICKY
    }

    private fun processIntent(intent: Intent) {
        intent.getParcelableArrayListExtra<Uri>("imageUris")?.let {
            imageUris.clear()
            imageUris.addAll(it)
            Log.d("WallpaperService", "Received ${imageUris.size} images: $imageUris")
        }
        interval = intent.getLongExtra("interval", 5 * 60 * 1000)
        Log.d("WallpaperService", "Set interval to: $interval ms")
    }

    private fun startForegroundService() {
        startForeground(1, createNotification())
        handler.post(wallpaperRunnable)
    }

    private fun setWallpaper(imageUri: Uri) {
        try {
            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

                when (sharedPreferences.getInt("wallpaper_screen", R.id.both_screens)) {
                    R.id.both_screens -> {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    }
                    R.id.home_screen_only -> {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    }
                    R.id.lock_screen_only -> {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    }
                    else -> {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("WallpaperService", "Failed to set wallpaper", e)
        }
    }

    private fun setWallpaperWithPixelation(imageUri: Uri) {
        try {
            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                val pixelSizes = listOf(64, 32, 16, 8, 1)

                for (pixelSize in pixelSizes) {
                    val workingBitmap = pixelateBitmap(originalBitmap, pixelSize)
                    wallpaperManager.setBitmap(workingBitmap)
                    Thread.sleep(1)
                }
            }
        } catch (e: IOException) {
            Log.e("WallpaperService", "Failed to set wallpaper", e)
        }
    }

    private fun pixelateBitmap(source: Bitmap, pixelSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        val scaledWidth = width / pixelSize
        val scaledHeight = height / pixelSize
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, false)
        return Bitmap.createScaledBitmap(scaled, width, height, false)
    }

    private fun setWallpaperWithDissolve(imageUri: Uri) {
        try {
            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val newBitmap = BitmapFactory.decodeStream(inputStream)
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                val currentBitmap = newBitmap
                val workingBitmap = Bitmap.createBitmap(
                    currentBitmap.width,
                    currentBitmap.height,
                    Bitmap.Config.ARGB_8888
                )

                val canvas = Canvas(workingBitmap)
                val paint = Paint().apply {
                    isAntiAlias = true
                }

                for (alpha in 0..255 step 5) {
                    canvas.drawBitmap(currentBitmap, 0f, 0f, paint)
                    paint.alpha = alpha
                    canvas.drawBitmap(newBitmap, 0f, 0f, paint)
                    wallpaperManager.setBitmap(workingBitmap)
                    Thread.sleep(1)
                }
            }
        } catch (e: IOException) {
            Log.e("WallpaperService", "Failed to set wallpaper", e)
        }
    }

    private fun changeWallpaper() {
        Log.d("WallpaperService", "Starting changeWallpaper method")
        if (imageUris.isNotEmpty()) {
            Log.d("WallpaperService", "Found ${imageUris.size} images to process")
            val validUris = imageUris.filter { uri ->
                try {
                    val file = File(uri.path!!)
                    val exists = file.exists()
                    val canRead = file.canRead()
                    Log.d("WallpaperService", "Checking URI: $uri - Exists: $exists, Readable: $canRead")
                    exists && canRead
                } catch (e: Exception) {
                    Log.e("WallpaperService", "Error checking file: $uri", e)
                    false
                }
            }

            Log.d("WallpaperService", "Valid URIs found: ${validUris.size}")
            if (validUris.isEmpty()) {
                Log.e("WallpaperService", "No valid images found")
                return
            }

            val nextUri = validUris.random()
            Log.d("WallpaperService", "Selected wallpaper: $nextUri")

            imageUris.clear()
            imageUris.addAll(validUris)
            saveSettings(validUris)

            val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val transitionsEnabled = sharedPreferences.getBoolean("transitions_enabled", true)
            val transitionEffect = sharedPreferences.getInt("transition_type", Context.MODE_PRIVATE)

            if (transitionsEnabled) {
                when (transitionEffect) {
                    0 -> setWallpaper(nextUri)
                    1 -> setWallpaperWithPixelation(nextUri)
                    2 -> setWallpaperWithDissolve(nextUri)
                    else -> setWallpaper(nextUri)
                }
            } else {
                setWallpaper(nextUri)
            }
        }else {
            Log.d("WallpaperService", "No images in imageUris list")
        }
    }

    private fun saveSettings(wallpapers: List<Uri>) {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        editor.putInt("wallpaper_count", wallpapers.size)
        wallpapers.chunked(100).forEachIndexed { index, chunk ->
            val key = "wallpapers_$index"
            val uriStrings = chunk.map { it.toString() }.toSet()
            editor.putStringSet(key, uriStrings)
        }
        editor.apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(wallpaperRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "WallpaperServiceChannel"
        val channelName = "Wallpaper Service"
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("Wallpaper Service")
            .setContentText("Changing wallpapers in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }
}
