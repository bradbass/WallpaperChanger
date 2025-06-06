package com.example.wallpaperchanger.services

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.io.IOException

class CrossfadeLiveWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine {
        // Load settings from SharedPreferences and pass to engine
        val (uris, interval) = loadSettingsFromPreferences(applicationContext)
        return CrossfadeEngine(uris, interval)
    }

    // Helper to load persisted settings
    private fun loadSettingsFromPreferences(context: Context): Pair<List<Uri>, Long> {
        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val expectedCount = prefs.getInt("wallpaper_count", 0)
        val interval = prefs.getLong("interval", 5 * 60 * 1000)
        val uris = mutableListOf<Uri>()
        var index = 0
        while (uris.size < expectedCount) {
            val key = "wallpapers_$index"
            val uriStrings = prefs.getStringSet(key, null) ?: break
            uris.addAll(uriStrings.map { Uri.parse(it) })
            index++
        }
        return Pair(uris, interval)
    }

    inner class CrossfadeEngine(
        initialUris: List<Uri>,
        initialInterval: Long
    ) : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var imageUris = mutableListOf<Uri>().apply { addAll(initialUris) }
        private var changeInterval: Long = initialInterval

        private var currentBitmap: Bitmap? = null
        private var nextBitmap: Bitmap? = null
        private var currentImageIndex = 0

        private var isTransitioning = false
        private var transitionProgress = 0f
        private val transitionDuration = 2000L // 2 seconds crossfade
        private var transitionStartTime = 0L

        private val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
        }

        private val wallpaperChangeRunnable = object : Runnable {
            override fun run() {
                if (imageUris.isNotEmpty() && !isTransitioning) {
                    startTransition()
                }
                handler.postDelayed(this, changeInterval)
            }
        }

        private val transitionRunnable = object : Runnable {
            override fun run() {
                if (isTransitioning) {
                    val currentTime = System.currentTimeMillis()
                    val elapsed = currentTime - transitionStartTime
                    transitionProgress = (elapsed.toFloat() / transitionDuration).coerceIn(0f, 1f)

                    drawFrame()

                    if (transitionProgress >= 1f) {
                        // Complete transition
                        isTransitioning = false
                        currentBitmap?.recycle()
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        drawFrame()
                        Log.d("CrossfadeLiveWallpaper", "Transition completed")
                    } else {
                        handler.postDelayed(this, 16) // ~60 FPS
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            Log.d("CrossfadeLiveWallpaper", "Engine created")
            loadInitialImage()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                handler.post(wallpaperChangeRunnable)
                if (isTransitioning) {
                    handler.post(transitionRunnable)
                }
                Log.d("CrossfadeLiveWallpaper", "Wallpaper visible, starting updates")
            } else {
                handler.removeCallbacks(wallpaperChangeRunnable)
                handler.removeCallbacks(transitionRunnable)
                if (isTransitioning) {
                    // Complete transition to a stable state
                    isTransitioning = false
                    currentBitmap?.recycle()
                    currentBitmap = nextBitmap
                    nextBitmap = null
                    transitionProgress = 0f
                    drawFrame()
                    Log.d("CrossfadeLiveWallpaper", "Wallpaper hidden, transition forced complete")
                }
                Log.d("CrossfadeLiveWallpaper", "Wallpaper hidden, stopping updates")
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.d("CrossfadeLiveWallpaper", "Surface changed: ${width}x${height}")
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            handler.removeCallbacks(wallpaperChangeRunnable)
            handler.removeCallbacks(transitionRunnable)
            cleanup()
        }

        // To update settings from outside (e.g., on user change)
        fun updateSettings(context: Context, uris: List<Uri>, interval: Long) {
            imageUris.clear()
            imageUris.addAll(uris)
            changeInterval = interval

            saveSettingsToPreferences(context, uris, interval)
            Log.d("CrossfadeLiveWallpaper", "Settings updated: ${imageUris.size} images, interval: ${changeInterval}ms")

            if (imageUris.isEmpty()) {
                handler.removeCallbacks(wallpaperChangeRunnable)
                currentBitmap?.recycle()
                nextBitmap?.recycle()
                currentBitmap = null
                nextBitmap = null
                drawFrame()
                return
            }

            if (currentBitmap == null) {
                loadInitialImage()
            }

            handler.removeCallbacks(wallpaperChangeRunnable)
            handler.postDelayed(wallpaperChangeRunnable, changeInterval)
        }

        private fun saveSettingsToPreferences(context: Context, uris: List<Uri>, interval: Long) {
            val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putInt("wallpaper_count", uris.size)
            uris.chunked(100).forEachIndexed { index, chunk ->
                val key = "wallpapers_$index"
                val uriStrings = chunk.map { it.toString() }.toSet()
                editor.putStringSet(key, uriStrings)
            }
            editor.putLong("interval", interval)
            editor.apply()
        }

        private fun loadInitialImage() {
            Log.d("CrossfadeLiveWallpaper", "loadInitialImage called")
            if (imageUris.isNotEmpty()) {
                currentImageIndex = 0
                val uri = imageUris[currentImageIndex]
                loadBitmap(uri) { bitmap ->
                    currentBitmap = bitmap
                    drawFrame()
                }
            } else {
                Log.e("CrossfadeLiveWallpaper", "No images to load! User needs to select images.")
                drawFrame()
            }
        }

        private fun startTransition() {
            if (imageUris.isEmpty()) return

            // Get next image
            currentImageIndex = (currentImageIndex + 1) % imageUris.size
            val nextUri = imageUris[currentImageIndex]

            loadBitmap(nextUri) { bitmap ->
                nextBitmap = bitmap
                isTransitioning = true
                transitionProgress = 0f
                transitionStartTime = System.currentTimeMillis()
                handler.post(transitionRunnable)
            }
        }

        private fun loadBitmap(uri: Uri, callback: (Bitmap?) -> Unit) {
            try {
                val surfaceHolder = surfaceHolder
                val surfaceFrame = surfaceHolder.surfaceFrame
                var targetWidth = surfaceFrame.width()
                var targetHeight = surfaceFrame.height()

                if (targetWidth <= 0 || targetHeight <= 0) {
                    // Use screen dimensions as fallback
                    val displayMetrics = applicationContext.resources.displayMetrics
                    targetWidth = displayMetrics.widthPixels
                    targetHeight = displayMetrics.heightPixels
                }

                val inputStream = applicationContext.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)

                    options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                    options.inJustDecodeBounds = false

                    val inputStream2 = applicationContext.contentResolver.openInputStream(uri)
                    inputStream2?.use { stream2 ->
                        val bitmap = BitmapFactory.decodeStream(stream2, null, options)
                        if (bitmap != null) {
                            val scaledBitmap = scaleBitmapToFit(bitmap, targetWidth, targetHeight)
                            callback(scaledBitmap)
                            if (scaledBitmap != bitmap) bitmap.recycle()
                        } else {
                            callback(null)
                        }
                    } ?: callback(null)
                } ?: callback(null)
            } catch (e: IOException) {
                Log.e("CrossfadeLiveWallpaper", "IOException loading bitmap: $uri", e)
                callback(null)
            } catch (e: Exception) {
                Log.e("CrossfadeLiveWallpaper", "Exception loading bitmap: $uri", e)
                callback(null)
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (width, height) = options.outWidth to options.outHeight
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        private fun scaleBitmapToFit(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
            if (targetWidth <= 0 || targetHeight <= 0) return bitmap
            val (sourceWidth, sourceHeight) = bitmap.width to bitmap.height
            val scale = maxOf(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
            val scaledWidth = (sourceWidth * scale).toInt()
            val scaledHeight = (sourceHeight * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            val startX = (scaledWidth - targetWidth) / 2
            val startY = (scaledHeight - targetHeight) / 2
            return if (startX != 0 || startY != 0) {
                val croppedBitmap = Bitmap.createBitmap(
                    scaledBitmap,
                    startX.coerceAtLeast(0),
                    startY.coerceAtLeast(0),
                    targetWidth,
                    targetHeight
                )
                if (scaledBitmap != bitmap) scaledBitmap.recycle()
                croppedBitmap
            } else {
                scaledBitmap
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    c.drawColor(Color.BLACK)
                    if (isTransitioning && currentBitmap != null && nextBitmap != null) {
                        paint.alpha = ((1f - transitionProgress) * 255).toInt()
                        c.drawBitmap(currentBitmap!!, 0f, 0f, paint)
                        paint.alpha = (transitionProgress * 255).toInt()
                        c.drawBitmap(nextBitmap!!, 0f, 0f, paint)
                        paint.alpha = 255
                    } else if (currentBitmap != null) {
                        paint.alpha = 255
                        c.drawBitmap(currentBitmap!!, 0f, 0f, paint)
                    } else {
                        // Fallback: draw test pattern
                        paint.color = Color.RED
                        c.drawRect(0f, 0f, c.width / 2f, c.height / 2f, paint)
                        paint.color = Color.GREEN
                        c.drawRect(c.width / 2f, 0f, c.width.toFloat(), c.height / 2f, paint)
                        paint.color = Color.BLUE
                        c.drawRect(0f, c.height / 2f, c.width / 2f, c.height.toFloat(), paint)
                        paint.color = Color.YELLOW
                        c.drawRect(c.width / 2f, c.height / 2f, c.width.toFloat(), c.height.toFloat(), paint)
                    }
                }
            } catch (e: Exception) {
                Log.e("CrossfadeLiveWallpaper", "Error drawing frame", e)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            currentBitmap?.recycle()
            currentBitmap = null
            nextBitmap?.recycle()
            nextBitmap = null
        }
    }
}
