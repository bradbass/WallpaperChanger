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

    companion object {
        private var imageUris = mutableListOf<Uri>()
        private var changeInterval: Long = 5 * 60 * 1000 // 5 minutes default
        private var activeEngine: CrossfadeEngine? = null

        fun updateWallpaperSettings(context: Context, uris: List<Uri>, interval: Long) {
            imageUris.clear()
            imageUris.addAll(uris)
            changeInterval = interval

            // Save to SharedPreferences for persistence - using same key as MainActivity
            val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            // Use the same format as MainActivity's saveSettings method
            editor.putInt("wallpaper_count", imageUris.size)
            imageUris.chunked(100).forEachIndexed { index, chunk ->
                val key = "wallpapers_$index"
                val uriStrings = chunk.map { it.toString() }.toSet()
                editor.putStringSet(key, uriStrings)
            }

            // Also save interval
            editor.putLong("interval", changeInterval)
            editor.apply()

            Log.d("CrossfadeLiveWallpaper", "Updated wallpaper settings: ${uris.size} images, interval: ${interval}ms")

            // Update active engine if it exists
            activeEngine?.updateSettings()
        }
    }

    override fun onCreateEngine(): Engine {
        val engine = CrossfadeEngine()
        activeEngine = engine
        return engine
    }

    inner class CrossfadeEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var currentBitmap: Bitmap? = null
        private var nextBitmap: Bitmap? = null
        private var currentImageIndex = 0
        private var isTransitioning = false
        private var transitionProgress = 0f
        private val transitionDuration = 2000L // 2 seconds for crossfade
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
                        // Transition complete
                        isTransitioning = false
                        currentBitmap?.recycle()
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        Log.d("CrossfadeLiveWallpaper", "Transition completed")
                    } else {
                        // Continue transition
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
                Log.d("CrossfadeLiveWallpaper", "Wallpaper visible, starting updates")
            } else {
                handler.removeCallbacks(wallpaperChangeRunnable)
                handler.removeCallbacks(transitionRunnable)
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

        fun updateSettings() {
            Log.d("CrossfadeLiveWallpaper", "Settings updated: ${imageUris.size} images, interval: ${changeInterval}ms")
            if (imageUris.isEmpty()) {
                handler.removeCallbacks(wallpaperChangeRunnable)
                return
            }

            // Reset to first image if we have new images
            if (currentBitmap == null) {
                loadInitialImage()
            }

            // Restart the timer with new interval
            handler.removeCallbacks(wallpaperChangeRunnable)
            handler.postDelayed(wallpaperChangeRunnable, changeInterval)
        }

        private fun loadInitialImage() {
            Log.d("CrossfadeLiveWallpaper", "loadInitialImage called")

            // If imageUris is empty, try loading from SharedPreferences
            if (imageUris.isEmpty()) {
                Log.d("CrossfadeLiveWallpaper", "imageUris empty, loading from SharedPreferences")
                loadSettingsFromPreferences()
            }

            Log.d("CrossfadeLiveWallpaper", "imageUris.size: ${imageUris.size}")

            if (imageUris.isNotEmpty()) {
                currentImageIndex = 0
                val uri = imageUris[currentImageIndex]
                Log.d("CrossfadeLiveWallpaper", "Loading initial image: $uri")

                loadBitmap(uri) { bitmap ->
                    Log.d("CrossfadeLiveWallpaper", "Initial bitmap loaded: ${bitmap != null}")
                    if (bitmap != null) {
                        Log.d("CrossfadeLiveWallpaper", "Bitmap size: ${bitmap.width}x${bitmap.height}")
                    }
                    currentBitmap = bitmap
                    drawFrame()
                }
            } else {
                Log.e("CrossfadeLiveWallpaper", "No images to load! User needs to select images in the app first")
                // Show test pattern so user knows wallpaper is working
                drawFrame()
            }
        }

        private fun loadSettingsFromPreferences() {
            // Use same SharedPreferences key and format as MainActivity
            val prefs = applicationContext.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val expectedCount = prefs.getInt("wallpaper_count", 0)
            changeInterval = prefs.getLong("interval", 5 * 60 * 1000)

            val allUris = mutableListOf<Uri>()
            var index = 0
            while (allUris.size < expectedCount) {
                val key = "wallpapers_$index"
                val uriStrings = prefs.getStringSet(key, null) ?: break
                allUris.addAll(uriStrings.map { Uri.parse(it) })
                index++
            }

            imageUris.clear()
            imageUris.addAll(allUris)

            Log.d("CrossfadeLiveWallpaper", "Loaded ${imageUris.size} images from SharedPreferences")
        }

        private fun startTransition() {
            if (imageUris.isEmpty()) return

            // Get next image
            currentImageIndex = (currentImageIndex + 1) % imageUris.size
            val nextUri = imageUris[currentImageIndex]

            Log.d("CrossfadeLiveWallpaper", "Starting transition to image $currentImageIndex: $nextUri")

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

                Log.d("CrossfadeLiveWallpaper", "Surface dimensions: ${targetWidth}x${targetHeight}")

                if (targetWidth <= 0 || targetHeight <= 0) {
                    Log.e("CrossfadeLiveWallpaper", "Invalid surface dimensions, using screen defaults")
                    // Use screen dimensions as fallback
                    val displayMetrics = applicationContext.resources.displayMetrics
                    targetWidth = displayMetrics.widthPixels
                    targetHeight = displayMetrics.heightPixels
                    Log.d("CrossfadeLiveWallpaper", "Using fallback dimensions: ${targetWidth}x${targetHeight}")
                }

                val inputStream = applicationContext.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(stream, null, options)

                    Log.d("CrossfadeLiveWallpaper", "Original image size: ${options.outWidth}x${options.outHeight}")

                    // Calculate sample size to fit screen
                    options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                    options.inJustDecodeBounds = false

                    Log.d("CrossfadeLiveWallpaper", "Using inSampleSize: ${options.inSampleSize}")

                    // Decode the actual bitmap
                    val inputStream2 = applicationContext.contentResolver.openInputStream(uri)
                    inputStream2?.use { stream2 ->
                        val bitmap = BitmapFactory.decodeStream(stream2, null, options)
                        if (bitmap != null) {
                            Log.d("CrossfadeLiveWallpaper", "Decoded bitmap size: ${bitmap.width}x${bitmap.height}")
                            val scaledBitmap = scaleBitmapToFit(bitmap, targetWidth, targetHeight)
                            Log.d("CrossfadeLiveWallpaper", "Final scaled bitmap size: ${scaledBitmap.width}x${scaledBitmap.height}")
                            callback(scaledBitmap)
                            if (scaledBitmap != bitmap) {
                                bitmap.recycle()
                            }
                        } else {
                            Log.e("CrossfadeLiveWallpaper", "Failed to decode bitmap from stream")
                            callback(null)
                        }
                    } ?: run {
                        Log.e("CrossfadeLiveWallpaper", "Failed to open input stream for decoding")
                        callback(null)
                    }
                } ?: run {
                    Log.e("CrossfadeLiveWallpaper", "Failed to open input stream: $uri")
                    callback(null)
                }
            } catch (e: IOException) {
                Log.e("CrossfadeLiveWallpaper", "IOException loading bitmap: $uri", e)
                callback(null)
            } catch (e: Exception) {
                Log.e("CrossfadeLiveWallpaper", "Exception loading bitmap: $uri", e)
                callback(null)
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
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

            val sourceWidth = bitmap.width
            val sourceHeight = bitmap.height

            // Calculate scale to fill the screen (crop if necessary)
            val scaleX = targetWidth.toFloat() / sourceWidth
            val scaleY = targetHeight.toFloat() / sourceHeight
            val scale = maxOf(scaleX, scaleY)

            val scaledWidth = (sourceWidth * scale).toInt()
            val scaledHeight = (sourceHeight * scale).toInt()

            // Create scaled bitmap
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

            // Center crop to fit exact dimensions
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
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
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
                    // Clear canvas
                    c.drawColor(Color.BLACK)

                    Log.d("CrossfadeLiveWallpaper", "Drawing frame - currentBitmap: ${currentBitmap != null}, isTransitioning: $isTransitioning")

                    if (isTransitioning && currentBitmap != null && nextBitmap != null) {
                        // Draw crossfade transition
                        paint.alpha = ((1f - transitionProgress) * 255).toInt()
                        c.drawBitmap(currentBitmap!!, 0f, 0f, paint)

                        paint.alpha = (transitionProgress * 255).toInt()
                        c.drawBitmap(nextBitmap!!, 0f, 0f, paint)

                        paint.alpha = 255
                    } else if (currentBitmap != null) {
                        // Draw current image normally
                        paint.alpha = 255
                        c.drawBitmap(currentBitmap!!, 0f, 0f, paint)
                    } else {
                        // Fallback: draw a test pattern to verify drawing works
                        Log.w("CrossfadeLiveWallpaper", "No bitmap to draw, showing test pattern")
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
            currentBitmap?.recycle()
            nextBitmap?.recycle()
            currentBitmap = null
            nextBitmap = null
        }
    }
}
