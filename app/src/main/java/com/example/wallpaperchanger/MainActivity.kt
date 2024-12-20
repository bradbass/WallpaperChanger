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
import android.app.Dialog
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
import android.util.Log
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Switch
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
        //changeWallpaper()
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

    private fun changeWallpaper() {
        if (imageUris.isNotEmpty()) {
            setWallpaper(imageUris.random())
        }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupRecyclerView()
        loadSettings()
        setupButtons()
    }

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
                saveSettings(imageUris, getIntervalFromInput())
            }
            .setNegativeButton("No", null)
            .show()
    }


    private fun getIntervalFromInput(): Long {
        val intervalInput: EditText = findViewById(R.id.interval_input)
        return (intervalInput.text.toString().toIntOrNull() ?: 5) * 60 * 1000L
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all { permission: String ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    private fun launchImagePicker() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100)
        }
        startActivityForResult(intent, pickImages)
    }

    private fun startWallpaperService() {
        if (imageUris.isEmpty()) {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            return
        }

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickImages && resultCode == Activity.RESULT_OK) {
            data?.let {
                val newImageUris = mutableListOf<Uri>()
                if (it.clipData != null) {
                    val count = it.clipData!!.itemCount
                    for (i in 0 until count) {
                        val imageUri = it.clipData!!.getItemAt(i).uri
                        contentResolver.takePersistableUriPermission(
                            imageUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        newImageUris.add(imageUri)
                    }
                } else if (it.data != null) {
                    val imageUri = it.data!!
                    contentResolver.takePersistableUriPermission(
                        imageUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    newImageUris.add(imageUri)
                }

                val uniqueImageUris = newImageUris.filter { newUri -> !imageUris.contains(newUri) }
                imageUris.addAll(uniqueImageUris)
                imageAdapter.notifyDataSetChanged()
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

                // Save settings
                val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("transitions_enabled", switchTransitions?.isChecked ?: true)
                    putLong("interval", (editInterval?.text.toString().toIntOrNull() ?: 5) * 60 * 1000L)
                    apply()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Load current settings
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        dialog.findViewById<Switch>(R.id.enable_transitions)?.isChecked =
            prefs.getBoolean("transitions_enabled", true)
        dialog.findViewById<EditText>(R.id.interval_input)?.setText(
            (prefs.getLong("interval", 5 * 60 * 1000) / 60 / 1000).toString()
        )
    }

    private fun saveSettings(wallpapers: List<Uri>, interval: Long) {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // Clear previous settings first
        editor.clear()

        // Save the total count for validation during loading
        editor.putInt("wallpaper_count", wallpapers.size)

        wallpapers.chunked(100).forEachIndexed { index, chunk ->
            val key = "wallpapers_$index"
            val uriStrings = chunk.map { it.toString() }.toSet()
            editor.putStringSet(key, uriStrings)
        }
        editor.putLong("interval", interval)
        editor.apply()
    }

    private fun loadSettings(): List<Uri> {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val expectedCount = sharedPreferences.getInt("wallpaper_count", 0)
        val allUris = mutableListOf<Uri>()

        var index = 0
        while (allUris.size < expectedCount) {
            val key = "wallpapers_$index"
            val uriStrings = sharedPreferences.getStringSet(key, null) ?: break
            allUris.addAll(uriStrings.map { Uri.parse(it) })
            index++
        }

        return allUris
    }

    private fun removeImage(uri: Uri) {
        imageUris.remove(uri)
        imageAdapter.notifyDataSetChanged()
    }
}



