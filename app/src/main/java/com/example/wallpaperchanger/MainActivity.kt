package com.example.wallpaperchanger

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import androidx.annotation.RequiresExtension
import androidx.recyclerview.widget.GridLayoutManager
import java.io.IOException

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

class WallpaperService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val imageUris = mutableListOf<Uri>()
    private var interval: Long = 5 * 60 * 1000
    private lateinit var wakeLock: PowerManager.WakeLock

    private val wallpaperRunnable = object : Runnable {
        override fun run() {
            changeWallpaper()
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeWakeLock()
    }

    private fun initializeWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WallpaperService::WakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { processIntent(it) }
        startForegroundService()
        return START_STICKY
    }

    private fun processIntent(intent: Intent) {
        intent.getParcelableArrayListExtra<Uri>("imageUris")?.let {
            imageUris.clear()
            imageUris.addAll(it)
        }
        interval = intent.getLongExtra("interval", 5 * 60 * 1000)
    }

    private fun startForegroundService() {
        wakeLock.acquire(10*60*1000L /*10 minutes*/)
        startForeground(1, createNotification())
        handler.post(wallpaperRunnable)
    }

    private fun setWallpaper(imageUri: Uri) {
        try {
            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                WallpaperManager.getInstance(applicationContext).setBitmap(bitmap)
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

                // Create working copy
                //var workingBitmap = originalBitmap.copy(originalBitmap.config, true)

                // Pixelation steps (from most pixelated to clear)
                // More gradual steps for smoother transition
                val pixelSizes = listOf(64, 32, 16, 8, 1)

                for (pixelSize in pixelSizes) {
                    val workingBitmap = pixelateBitmap(originalBitmap, pixelSize)
                    wallpaperManager.setBitmap(workingBitmap)
                    Thread.sleep(1) // Adjust timing for effect
                }
            }
        } catch (e: IOException) {
            Log.e("WallpaperService", "Failed to set wallpaper", e)
        }
    }

    private fun pixelateBitmap(source: Bitmap, pixelSize: Int): Bitmap {
        val width = source.width
        val height = source.height

        // Create scaled down version
        val scaledWidth = width / pixelSize
        val scaledHeight = height / pixelSize

        // Scale down and up to create pixelation
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, false)
        return Bitmap.createScaledBitmap(scaled, width, height, false)
    }

    private fun setWallpaperWithDissolve(imageUri: Uri) {
        try {
            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                val newBitmap = BitmapFactory.decodeStream(inputStream)
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)

                // Get current wallpaper
                val currentBitmap = newBitmap

                // Create working bitmap for blending
                val workingBitmap = Bitmap.createBitmap(
                    currentBitmap.width,
                    currentBitmap.height,
                    Bitmap.Config.ARGB_8888
                )

                // Perform dissolve effect
                val canvas = Canvas(workingBitmap)
                val paint = Paint().apply {
                    isAntiAlias = true
                }

                // Gradually increase opacity of new image
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
        if (imageUris.isNotEmpty()) {
            val validUris = imageUris.filter { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { true } ?: false
                } catch (e: Exception) {
                    Log.d("PermissionDebug", "Removing invalid URI: $uri")
                    false
                }
            }

            if (validUris.isEmpty()) {
                Log.e("PermissionDebug", "No valid URIs remaining")
                return
            }

            val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val transitionsEnabled = sharedPreferences.getBoolean("transitions_enabled", true)
            val transitionEffect = sharedPreferences.getInt("transition_type", Context.MODE_PRIVATE)

            // Update the stored list with only valid URIs
            imageUris.clear()
            imageUris.addAll(validUris)

            // Save the updated list
            saveSettings(validUris)

            val nextUri = validUris.random()

            if (!hasValidPermission(nextUri)) {
                verifyAndRefreshPermissions(nextUri)
                if (!hasValidPermission(nextUri)) {
                    Log.e("PermissionDebug", "Unable to refresh permissions for $nextUri")
                    // Skip this image and try another one
                    imageUris.remove(nextUri)
                    if (imageUris.isNotEmpty()) {
                        changeWallpaper()
                    }
                    return
                }
            }

            if (transitionsEnabled) {
                when (transitionEffect) {
                    0 -> setWallpaper(nextUri) // No transition effect
                    1 -> setWallpaperWithPixelation(nextUri) // Pixelate effect
                    2 -> setWallpaperWithDissolve(nextUri)
                    //2 -> setWallpaperWithFade(nextUri) // Fade effect
                    //3 -> setWallpaperWithSlide(nextUri) // Slide effect
                    //4 -> setWallpaperWithZoom(nextUri) // Zoom effect
                    else -> setWallpaper(nextUri)
                }
            } else {
                setWallpaper(nextUri)
            }
        }
    }

    private fun hasValidPermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    private fun verifyAndRefreshPermissions(uri: Uri) {
        Log.d("PermissionDebug", "Starting permission refresh for $uri")

        // Log current state
        val currentPermissions = contentResolver.persistedUriPermissions
        Log.d("PermissionDebug", "Current persisted permissions count: ${currentPermissions.size}")
        currentPermissions.forEach { permission ->
            Log.d("PermissionDebug", "Existing permission: ${permission.uri}, Read: ${permission.isReadPermission}")
        }

        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            Log.d("PermissionDebug", "Successfully requested permission for $uri")

            // Verify the permission was actually granted
            val verified = hasValidPermission(uri)
            Log.d("PermissionDebug", "Permission verification result: $verified")
        } catch (e: SecurityException) {
            Log.e("PermissionDebug", "Permission refresh failed for $uri: ${e.message}")
        }
    }

    private fun saveSettings(wallpapers: List<Uri>) {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // Remove only wallpaper-related keys
        val keysToRemove = mutableListOf<String>()
        sharedPreferences.all.keys.forEach { key ->
            if (key.startsWith("wallpapers_") || key == "wallpaper_count") {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { editor.remove(it) }

        // Save the new wallpaper data
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
        wakeLock.release()
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
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, channelId)
            .setContentTitle("Wallpaper Service")
            .setContentText("Changing wallpapers in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }
}

class ImageAdapter(
    private val imageUris: MutableList<Uri>,
    private val context: Context,
    private val removeCallback: (List<Uri>) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    private val selectedImages = mutableSetOf<Uri>()

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.image_view)
        val checkbox: CheckBox = view.findViewById(R.id.image_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.image_item, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val uri = imageUris[position]
        Glide.with(context)
            .load(uri)
            .into(holder.imageView)

        holder.checkbox.isChecked = selectedImages.contains(uri)
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedImages.add(uri)
            } else {
                selectedImages.remove(uri)
            }
        }

        holder.imageView.setOnClickListener {
            showImagePreview(uri)
        }
    }

    private fun showImagePreview(uri: Uri) {
        val dialog = Dialog(context, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        dialog.setContentView(R.layout.image_preview_dialog)

        // Fade in animation
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation

        val previewImage = dialog.findViewById<ImageView>(R.id.preview_image)
        Glide.with(context)
            .load(uri)
            .into(previewImage)

        previewImage.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun getItemCount(): Int = imageUris.size

    fun getSelectedImages(): List<Uri> = selectedImages.toList()

    @SuppressLint("NotifyDataSetChanged")
    fun clearSelections() {
        selectedImages.clear()
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {
    private val pickImages = 1
    private val PERMISSION_REQUEST_CODE = 100
    private val imageUris = mutableListOf<Uri>()
    private lateinit var imageAdapter: ImageAdapter

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.R, version = 2)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupRecyclerView()
        setupButtons()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupRecyclerView() {
        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        imageAdapter = ImageAdapter(imageUris, this) { selectedImages ->
            // Handle selected images removal here
        }
        recyclerView.adapter = imageAdapter

        // Load saved wallpapers
        val savedWallpapers: List<Uri> = loadSettings()
        imageUris.clear()
        imageUris.addAll(savedWallpapers)
        imageAdapter.notifyDataSetChanged()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.R, version = 2)
    private fun setupButtons() {
        findViewById<Button>(R.id.pick_images_button).setOnClickListener {
            if (hasPermissions()) {
                launchImagePicker()
            } else {
                checkAndRequestPermissions()
            }
        }

        findViewById<Button>(R.id.start_service_button).setOnClickListener {
            startWallpaperService()
        }

        findViewById<Button>(R.id.stop_service_button).setOnClickListener {
            stopWallpaperService()
        }

        findViewById<Button>(R.id.clear_all_button).setOnClickListener {
            showClearDialog()
        }

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            showSettingsDialog()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showClearDialog() {
        val selectedImages = imageAdapter.getSelectedImages()
        val message = if (selectedImages.isEmpty()) {
            "Are you sure you want to remove all images?"
        } else {
            "Are you sure you want to remove ${selectedImages.size} selected images?"
        }

        AlertDialog.Builder(this)
            .setTitle("Remove Images")
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ ->
                if (selectedImages.isEmpty()) {
                    imageUris.clear()
                } else {
                    imageUris.removeAll(selectedImages.toSet())
                    imageAdapter.clearSelections()
                }
                imageAdapter.notifyDataSetChanged()
                saveSettings(imageUris)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all { permission: String ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.R, version = 2)
    private fun launchImagePicker() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100)
        }
        startActivityForResult(intent, pickImages)
    }

    private fun startWallpaperService() {
        stopWallpaperService()
        if (imageUris.isEmpty()) {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            return
        }

        verifyAndRefreshPermissions(imageUris)

        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val interval = sharedPreferences.getLong("interval", 5 * 60 * 1000)

        val serviceIntent = Intent(this, WallpaperService::class.java).apply {
            putParcelableArrayListExtra("imageUris", ArrayList(imageUris))
            putExtra("interval", interval)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopWallpaperService() {
        stopService(Intent(this, WallpaperService::class.java))
    }

    @RequiresExtension(extension = Build.VERSION_CODES.R, version = 2)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            launchImagePicker()
        } else {
            Toast.makeText(this, "Permissions required to access images", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickImages && resultCode == Activity.RESULT_OK) {
            data?.let {
                val newImageUris = mutableListOf<Uri>()
                if (it.clipData != null) {
                    val count = it.clipData!!.itemCount
                    for (i in 0 until count) {
                        val imageUri = it.clipData!!.getItemAt(i).uri
                        try {
                            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            contentResolver.takePersistableUriPermission(imageUri, flags)
                            newImageUris.add(imageUri)
                            Log.d("PermissionDebug", "Added new image: $imageUri")
                        } catch (e: SecurityException) {
                            Log.e("PermissionDebug", "Failed to take permission: $imageUri", e)
                        }
                    }
                } else if (it.data != null) {
                    val imageUri = it.data!!
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(imageUri, flags)
                        newImageUris.add(imageUri)
                        Log.d("PermissionDebug", "Added single image: $imageUri")
                    } catch (e: SecurityException) {
                        Log.e("PermissionDebug", "Failed to take permission: $imageUri", e)
                    }
                }

                val uniqueImageUris = newImageUris.filter { newUri -> !imageUris.contains(newUri) }
                imageUris.addAll(uniqueImageUris)
                imageAdapter.notifyDataSetChanged()
                saveSettings(imageUris)
            }
        }
    }

    private fun showSettingsDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layoutInflater.inflate(R.layout.dialog_settings, null))
            .setPositiveButton("Save") { dialog, _ ->
                val switchTransitions = (dialog as AlertDialog).findViewById<Switch>(R.id.enable_transitions)
                val editInterval = dialog.findViewById<EditText>(R.id.interval_input)
                val transitionSpinner = dialog.findViewById<Spinner>(R.id.transition_type)

                // Save settings
                val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("transitions_enabled", switchTransitions?.isChecked ?: true)
                    putLong("interval", (editInterval?.text.toString().toIntOrNull() ?: 5) * 60 * 1000L)
                    putInt("transition_type", transitionSpinner?.selectedItemPosition ?: 0)
                    apply()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Setup spinner
        val spinner = dialog.findViewById<Spinner>(R.id.transition_type)
        ArrayAdapter.createFromResource(
            this,
            R.array.transition_types,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner?.adapter = adapter
        }

        // Load current settings
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        dialog.findViewById<Switch>(R.id.enable_transitions)?.isChecked =
            prefs.getBoolean("transitions_enabled", true)
        dialog.findViewById<EditText>(R.id.interval_input)?.setText(
            (prefs.getLong("interval", 5 * 60 * 1000) / 60 / 1000).toString()
        )
        spinner?.setSelection(prefs.getInt("transition_type", 0))
    }

    private fun saveSettings(wallpapers: List<Uri>) {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // Remove only wallpaper-related keys
        val keysToRemove = mutableListOf<String>()
        sharedPreferences.all.keys.forEach { key ->
            if (key.startsWith("wallpapers_") || key == "wallpaper_count") {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { editor.remove(it) }

        // Save the new wallpaper data
        editor.putInt("wallpaper_count", wallpapers.size)
        wallpapers.chunked(100).forEachIndexed { index, chunk ->
            val key = "wallpapers_$index"
            val uriStrings = chunk.map { it.toString() }.toSet()
            editor.putStringSet(key, uriStrings)
        }
        editor.apply()
    }

    private fun loadSettings(): List<Uri> {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val expectedCount = sharedPreferences.getInt("wallpaper_count", 0)
        val allUris = mutableListOf<Uri>()

        verifyAndRefreshPermissions(allUris)

        var index = 0
        while (allUris.size < expectedCount) {
            val key = "wallpapers_$index"
            val uriStrings = sharedPreferences.getStringSet(key, null) ?: break
            allUris.addAll(uriStrings.map { Uri.parse(it) })
            index++
        }

        return allUris
    }

    private fun hasValidPermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    private fun verifyAndRefreshPermissions(uris: List<Uri>) {
        uris.forEach { uri ->
            Log.d("PermissionDebug", "Starting permission refresh for $uri")

            // Log current state
            val currentPermissions = contentResolver.persistedUriPermissions
            Log.d("PermissionDebug", "Current persisted permissions count: ${currentPermissions.size}")
            currentPermissions.forEach { permission ->
                Log.d("PermissionDebug", "Existing permission: ${permission.uri}, Read: ${permission.isReadPermission}")
            }

            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                Log.d("PermissionDebug", "Successfully requested permission for $uri")

                // Verify the permission was actually granted
                val verified = hasValidPermission(uri)
                Log.d("PermissionDebug", "Permission verification result: $verified")
            } catch (e: SecurityException) {
                Log.e("PermissionDebug", "Permission refresh failed for $uri: ${e.message}")
            }
        }
    }
}
