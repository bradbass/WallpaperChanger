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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import android.graphics.BitmapFactory
import android.util.Log
import java.io.IOException

/*
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) {
            val serviceIntent = Intent(context, WallpaperService::class.java)
            context?.startService(serviceIntent)
        }
    }
}*/

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

    /*
    private fun setWallpaper(imageUri: Uri) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            val wallpaperManager = WallpaperManager.getInstance(applicationContext)
            wallpaperManager.setBitmap(bitmap)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun changeWallpaper() {
        if (imageUris.isNotEmpty()) {
            //setWallpaper(imageUris[currentIndex])
            //currentIndex = (currentIndex + 1) % imageUris.size
            val randomImageUri = imageUris.random()
            setWallpaper(randomImageUri)
        }
    }*/
}

class ImageAdapter(private val imageUris: MutableList<Uri>, private val context: Context, private val removeCallback: (Uri) -> Unit) :
    RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.image_item, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val uri = imageUris[position]
        Glide.with(context)
            .load(uri)
            .into(holder.imageView)

        holder.imageView.setOnClickListener {
            showRemoveDialog(uri)
        }
    }

    override fun getItemCount(): Int {
        return imageUris.size
    }

    private fun showRemoveDialog(uri: Uri) {
        AlertDialog.Builder(context)
            .setTitle("Remove Image")
            .setMessage("Are you sure you want to remove this image?")
            .setPositiveButton("Yes") { dialog, which ->
                removeCallback(uri)
            }
            .setNegativeButton("No", null)
            .show()
    }

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.image_view)
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
        recyclerView.layoutManager = LinearLayoutManager(this)
        imageAdapter = ImageAdapter(imageUris, this) { uri -> removeImage(uri) }
        recyclerView.adapter = imageAdapter
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
        val intervalInput: EditText = findViewById(R.id.interval_input)
        val interval = intervalInput.text.toString().toIntOrNull()

        when {
            interval == null || interval <= 0 -> {
                Toast.makeText(this, "Please enter a valid interval in minutes", Toast.LENGTH_SHORT).show()
            }
            imageUris.isEmpty() -> {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val intervalMillis = interval * 60 * 1000L
                saveSettings(imageUris, intervalMillis)

                val serviceIntent = Intent(this, WallpaperService::class.java).apply {
                    putParcelableArrayListExtra("imageUris", ArrayList(imageUris))
                    putExtra("interval", intervalMillis)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }
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

    /*
    private fun requestBatteryOptimizationExclusion() {
        val intent = Intent()
        val packageName = packageName
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }*/

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

    private fun loadSettings(): Pair<List<Uri>, Long> {
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

        val interval = sharedPreferences.getLong("interval", 5 * 60 * 1000)
        return Pair(allUris, interval)
    }

    private fun removeImage(uri: Uri) {
        imageUris.remove(uri)
        imageAdapter.notifyDataSetChanged()
    }
}



