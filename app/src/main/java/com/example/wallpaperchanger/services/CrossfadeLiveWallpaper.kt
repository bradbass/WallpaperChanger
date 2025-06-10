package com.example.wallpaperchanger.services

import GLFrameExtractor
import android.content.Context
import android.graphics.*
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import kotlin.random.Random

class CrossfadeLiveWallpaper : WallpaperService() {

    companion object {
        var currentEngine: CrossfadeEngine? = null
    }

    override fun onCreateEngine(): Engine {
        val (uris, interval, transitionType) = loadSettingsFromPreferences(applicationContext)
        val engine = CrossfadeEngine(uris, interval, transitionType)
        currentEngine = engine
        return engine
    }

    private fun loadSettingsFromPreferences(context: Context): Triple<List<Uri>, Long, Int> {
        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val expectedCount = prefs.getInt("wallpaper_count", 0)
        val interval = prefs.getLong("interval", 5 * 60 * 1000)
        val transitionType = prefs.getInt("transition_type", 0)
        val uris = mutableListOf<Uri>()
        var index = 0
        while (uris.size < expectedCount) {
            val key = "wallpapers_$index"
            val uriStrings = prefs.getStringSet(key, null) ?: break
            uris.addAll(uriStrings.map { Uri.parse(it) })
            index++
        }
        return Triple(uris, interval, transitionType)
    }

    inner class CrossfadeEngine(
        initialUris: List<Uri>,
        initialInterval: Long,
        initialTransitionType: Int
    ) : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var imageUris = mutableListOf<Uri>().apply { addAll(initialUris) }
        private var changeInterval: Long = initialInterval
        private var transitionType: Int = initialTransitionType

        private var currentBitmap: Bitmap? = null
        private var nextBitmap: Bitmap? = null
        private var currentImageIndex = 0

        private var isTransitioning = false
        private var transitionProgress = 0f
        private val transitionDuration = 2000L // 2 seconds default crossfade
        private var transitionStartTime = 0L

        private val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
        }

        // --- ExoPlayer video wallpaper variables ---
        private var exoPlayer: ExoPlayer? = null
        private var surfaceTexture: SurfaceTexture? = null
        private var playerSurface: Surface? = null
        private var glRenderer: GLFrameExtractor? = null
        private var videoBitmap: Bitmap? = null
        private var frameAvailable = false
        private var videoWidth = 0
        private var videoHeight = 0
        private var pendingVideoUri: Uri? = null

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
                    when (transitionType) {
                        1 -> { /* Pixelate handles its own animation */ }
                        else -> {
                            val currentTime = System.currentTimeMillis()
                            val elapsed = currentTime - transitionStartTime
                            transitionProgress = (elapsed.toFloat() / transitionDuration).coerceIn(0f, 1f)

                            drawFrame()

                            if (transitionProgress >= 1f) {
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
                if (isTransitioning && transitionType != 1) {
                    handler.post(transitionRunnable)
                }
                if (pendingVideoUri != null) {
                    val width = surfaceHolder.surfaceFrame.width()
                    val height = surfaceHolder.surfaceFrame.height()
                    if (width > 0 && height > 0) {
                        startVideoWallpaper(pendingVideoUri!!, width, height)
                        pendingVideoUri = null
                    }
                }
                Log.d("CrossfadeLiveWallpaper", "Wallpaper visible, starting updates")
            } else {
                handler.removeCallbacks(wallpaperChangeRunnable)
                handler.removeCallbacks(transitionRunnable)
                stopVideoWallpaper()
                if (isTransitioning) {
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

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (pendingVideoUri != null) {
                startVideoWallpaper(pendingVideoUri!!, width, height)
                pendingVideoUri = null
            }
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            stopVideoWallpaper()
            handler.removeCallbacks(wallpaperChangeRunnable)
            handler.removeCallbacks(transitionRunnable)
            cleanup()
        }

        private fun startVideoWallpaper(uri: Uri, width: Int, height: Int) {
            stopVideoWallpaper()
            videoWidth = if (width > 0) width else 720
            videoHeight = if (height > 0) height else 1280

            glRenderer = GLFrameExtractor(videoWidth, videoHeight)
            val oesTexId = glRenderer!!.createOESTexture()
            surfaceTexture = SurfaceTexture(oesTexId)
            surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
            playerSurface = Surface(surfaceTexture)

            exoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                setVideoSurface(playerSurface)
                // Do NOT repeat. Instead, advance when finished.
                playWhenReady = true
                prepare()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == ExoPlayer.STATE_ENDED) {
                            handler.post { startTransition() }
                        }
                    }
                })
            }
            surfaceTexture?.setOnFrameAvailableListener {
                frameAvailable = true
                // Only clear currentBitmap when first video frame is ready
                if (videoBitmap == null) {
                    currentBitmap = null
                }
                drawFrame()
            }
        }

        private fun stopVideoWallpaper() {
            exoPlayer?.release()
            exoPlayer = null
            playerSurface?.release()
            playerSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            glRenderer?.release()
            glRenderer = null
            videoBitmap = null
            frameAvailable = false
        }

        fun updateSettings(context: Context, uris: List<Uri>, interval: Long, transitionType: Int) {
            imageUris.clear()
            imageUris.addAll(uris)
            changeInterval = interval
            this.transitionType = transitionType

            saveSettingsToPreferences(context, uris, interval, transitionType)
            Log.d("CrossfadeLiveWallpaper", "Settings updated: ${imageUris.size} images, interval: ${changeInterval}ms, transition: $transitionType")

            if (imageUris.isEmpty()) {
                handler.removeCallbacks(wallpaperChangeRunnable)
                currentBitmap?.recycle()
                nextBitmap?.recycle()
                currentBitmap = null
                nextBitmap = null
                stopVideoWallpaper()
                drawFrame()
                return
            }

            if (currentBitmap == null && videoBitmap == null) {
                loadInitialImage()
            }

            handler.removeCallbacks(wallpaperChangeRunnable)
            handler.postDelayed(wallpaperChangeRunnable, changeInterval)
        }

        private fun saveSettingsToPreferences(context: Context, uris: List<Uri>, interval: Long, transitionType: Int) {
            val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putInt("wallpaper_count", uris.size)
            uris.chunked(100).forEachIndexed { index, chunk ->
                val key = "wallpapers_$index"
                val uriStrings = chunk.map { it.toString() }.toSet()
                editor.putStringSet(key, uriStrings)
            }
            editor.putLong("interval", interval)
            editor.putInt("transition_type", transitionType)
            editor.apply()
        }

        private fun isVideoUri(uri: Uri): Boolean {
            val mimeType = applicationContext.contentResolver.getType(uri)
            if (mimeType != null) {
                return mimeType.startsWith("video")
            }
            val path = uri.toString().lowercase()
            return path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".3gp") ||
                    path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov")
        }

        private fun loadMedia(uri: Uri, setAsCurrent: Boolean = true) {
            if (isVideoUri(uri)) {
                val holder = surfaceHolder
                val width = holder.surfaceFrame.width()
                val height = holder.surfaceFrame.height()
                if (width > 0 && height > 0) {
                    startVideoWallpaper(uri, width, height)
                } else {
                    pendingVideoUri = uri
                }
                // Don't clear currentBitmap until first video frame is ready!
            } else {
                loadBitmap(uri) { bitmap ->
                    // Only clear videoBitmap after image is loaded
                    if (setAsCurrent) {
                        if (bitmap != null) {
                            currentBitmap = bitmap
                            drawFrame()
                            stopVideoWallpaper()
                        } else {
                            // Optionally, keep the video frame, log, or retry
                            Log.e("CrossfadeLiveWallpaper", "Failed to load image, keeping video frame.")
                        }
                    } else {
                        nextBitmap = bitmap
                    }
                }
            }
        }

        private fun loadInitialImage() {
            Log.d("CrossfadeLiveWallpaper", "loadInitialImage called")
            if (imageUris.isNotEmpty()) {
                currentImageIndex = 0
                val uri = imageUris[currentImageIndex]
                loadMedia(uri, setAsCurrent = true)
            } else {
                Log.e("CrossfadeLiveWallpaper", "No images to load! User needs to select images.")
                drawFrame()
            }
        }

        private fun startTransition() {
            if (imageUris.isEmpty()) return

            var newIndex: Int
            do {
                newIndex = Random.nextInt(imageUris.size)
            } while (imageUris.size > 1 && newIndex == currentImageIndex)
            currentImageIndex = newIndex
            val nextUri = imageUris[currentImageIndex]
            Log.d("CrossfadeLiveWallpaper", "startTransition: nextUri=$nextUri")

            if (isVideoUri(nextUri)) {
                Log.d("CrossfadeLiveWallpaper", "Next media is video. No transition, just play video.")
                loadMedia(nextUri, setAsCurrent = true)
                isTransitioning = false
                transitionProgress = 0f
                return
            }

            // If we're currently playing a video, stop it and decode the image
            if (videoBitmap != null) {
                Log.d("CrossfadeLiveWallpaper", "Currently playing video. Switching to image wallpaper.")
                stopVideoWallpaper()
            }

            loadMedia(nextUri, setAsCurrent = false)
            isTransitioning = true
            transitionProgress = 0f
            transitionStartTime = System.currentTimeMillis()
            when (transitionType) {
                1 -> animatePixelateTransition()
                2 -> animateDissolveTransition()
                3 -> animateSlideLeftToRightTransition()
                4 -> animateSlideTopToBottomTransition()
                else -> handler.post(transitionRunnable)
            }
        }

        // ---- DRAW FRAME ----
        private fun drawFrame(bitmapOverride: Bitmap? = null) {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    c.drawColor(Color.BLACK)
                    when {
                        videoBitmap != null -> c.drawBitmap(videoBitmap!!, 0f, 0f, paint)
                        bitmapOverride != null -> c.drawBitmap(bitmapOverride, 0f, 0f, paint)
                        isTransitioning && currentBitmap != null && nextBitmap != null && transitionType == 0 -> { /* crossfade logic */ }
                        currentBitmap != null -> c.drawBitmap(currentBitmap!!, 0f, 0f, paint)
                        // Do not draw fallback pattern!
                        // else: simply leave the last frame, don't clear/draw black
                    }
                }
            } catch (e: Exception) {
                Log.e("CrossfadeLiveWallpaper", "Error drawing frame", e)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }

            // If we're displaying video, update from GL every frame
            if (exoPlayer != null && surfaceTexture != null && glRenderer != null) {
                if (frameAvailable) {
                    videoBitmap = glRenderer?.getFrameBitmap(surfaceTexture!!)
                    frameAvailable = false
                }
            }
        }

        private fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            currentBitmap?.recycle()
            currentBitmap = null
            nextBitmap?.recycle()
            nextBitmap = null
            stopVideoWallpaper()
        }

        // --- Image and transition methods below here (unchanged, as in your previous code) ---

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

        // --- Transition logic (unchanged except for media cleanups) ---

        private fun pixelateBitmap(source: Bitmap, pixelSize: Int): Bitmap {
            val width = source.width
            val height = source.height
            val scaledWidth = maxOf(1, width / pixelSize)
            val scaledHeight = maxOf(1, height / pixelSize)
            val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, false)
            return Bitmap.createScaledBitmap(scaled, width, height, false)
        }

        private fun animatePixelateTransition() {

            if (currentBitmap == null || nextBitmap == null) {
                Log.w("CrossfadeLiveWallpaper", "Transition aborted: one of the bitmaps is null (could be video).")
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }

            val bitmap = nextBitmap ?: return
            val pixelSizes = listOf(128, 96, 64, 32, 16, 8, 1)
            handler.post(object : Runnable {
                var idx = 0
                override fun run() {
                    if (idx < pixelSizes.size) {
                        val workingBitmap = pixelateBitmap(bitmap, pixelSizes[idx])
                        drawFrame(workingBitmap)
                        idx++
                        handler.postDelayed(this, 200)
                    } else {
                        isTransitioning = false
                        currentBitmap?.recycle()
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        drawFrame()
                    }
                }
            })
        }

        // Dissolve
        private var dissolveBlockOrder: IntArray? = null
        private var dissolveBlocksPerRow = 16
        private var dissolveBlocksPerCol = 32

        private fun animateDissolveTransition() {

            if (currentBitmap == null || nextBitmap == null) {
                Log.w("CrossfadeLiveWallpaper", "Transition aborted: one of the bitmaps is null (could be video).")
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }

            val from = currentBitmap
            val to = nextBitmap
            if (from == null || to == null) {
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }
            val blockCount = dissolveBlocksPerRow * dissolveBlocksPerCol
            dissolveBlockOrder = IntArray(blockCount) { it }.also { it.shuffle() }
            val steps = 20
            val handler = handler
            handler.post(object : Runnable {
                var idx = 0
                override fun run() {
                    if (idx <= steps) {
                        val progress = idx.toFloat() / steps
                        drawDissolveFrame(from, to, progress)
                        idx++
                        handler.postDelayed(this, 60)
                    } else {
                        isTransitioning = false
                        currentBitmap?.recycle()
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        drawFrame()
                    }
                }
            })
        }

        private fun drawDissolveFrame(from: Bitmap, to: Bitmap, progress: Float) {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    c.drawBitmap(from, 0f, 0f, null)
                    val width = c.width
                    val height = c.height
                    val blockW = width / dissolveBlocksPerRow
                    val blockH = height / dissolveBlocksPerCol
                    val totalBlocks = dissolveBlocksPerRow * dissolveBlocksPerCol
                    val blocksToShow = (progress * totalBlocks).toInt().coerceIn(0, totalBlocks)
                    dissolveBlockOrder?.let { order ->
                        for (i in 0 until blocksToShow) {
                            val blockIdx = order[i]
                            val row = blockIdx / dissolveBlocksPerRow
                            val col = blockIdx % dissolveBlocksPerRow
                            val left = col * blockW
                            val top = row * blockH
                            val right = if (col == dissolveBlocksPerRow - 1) width else left + blockW
                            val bottom = if (row == dissolveBlocksPerCol - 1) height else top + blockH
                            val srcRect = Rect(left, top, right, bottom)
                            val dstRect = srcRect
                            c.drawBitmap(to, srcRect, dstRect, null)
                        }
                    }
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun animateSlideLeftToRightTransition() {

            if (currentBitmap == null || nextBitmap == null) {
                Log.w("CrossfadeLiveWallpaper", "Transition aborted: one of the bitmaps is null (could be video).")
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }

            val from = currentBitmap
            val to = nextBitmap
            if (from == null || to == null) {
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }
            val steps = 20
            handler.post(object : Runnable {
                var idx = 0
                override fun run() {
                    if (idx <= steps) {
                        val progress = idx.toFloat() / steps
                        drawSlideLeftToRightFrame(from, to, progress)
                        idx++
                        handler.postDelayed(this, 60)
                    } else {
                        isTransitioning = false
                        currentBitmap?.recycle()
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        drawFrame()
                    }
                }
            })
        }

        private fun drawSlideLeftToRightFrame(from: Bitmap, to: Bitmap, progress: Float) {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    val width = c.width
                    val offset = (width * progress).toInt()
                    c.drawBitmap(from, (-offset).toFloat(), 0f, null)
                    c.drawBitmap(to, (width - offset).toFloat(), 0f, null)
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun animateSlideTopToBottomTransition() {

            if (currentBitmap == null || nextBitmap == null) {
                Log.w("CrossfadeLiveWallpaper", "Transition aborted: one of the bitmaps is null (could be video).")
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }

            val from = currentBitmap
            val to = nextBitmap
            if (from == null || to == null) {
                isTransitioning = false
                currentBitmap = nextBitmap
                nextBitmap = null
                drawFrame()
                return
            }
            val steps = 20
            handler.post(object : Runnable {
                var idx = 0
                override fun run() {
                    if (idx <= steps) {
                        val progress = idx.toFloat() / steps
                        drawSlideTopToBottomFrame(from, to, progress)
                        idx++
                        handler.postDelayed(this, 60)
                    } else {
                        isTransitioning = false
                        currentBitmap?.recycle()
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        drawFrame()
                    }
                }
            })
        }

        private fun drawSlideTopToBottomFrame(from: Bitmap, to: Bitmap, progress: Float) {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    val height = c.height
                    val offset = (height * progress).toInt()
                    c.drawBitmap(from, 0f, (-offset).toFloat(), null)
                    c.drawBitmap(to, 0f, (height - offset).toFloat(), null)
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }
    }
}
