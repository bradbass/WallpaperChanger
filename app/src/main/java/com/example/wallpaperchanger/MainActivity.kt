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
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
//import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.util.concurrent.Service
import java.io.IOException

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) {
            val serviceIntent = Intent(context, WallpaperService::class.java)
            context?.startService(serviceIntent)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, WallpaperService::class.java)
            ContextCompat.startForegroundService(context!!, serviceIntent)
        }
    }
}

class WallpaperService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val wallpaperRunnable = object : Runnable {
        override fun run() {
            changeWallpaper()
            handler.postDelayed(this, interval)
        }
    }
    private val imageUris = mutableListOf<Uri>()
    private var interval: Long = 5 * 60 * 1000 // Default to 5 minutes
    //private var currentIndex = 0
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WallpaperService::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val imageUrisList = it.getParcelableArrayListExtra<Uri>("imageUris")
            if (imageUrisList != null) {
                imageUris.clear()
                imageUris.addAll(imageUrisList)
            }
            interval = it.getLongExtra("interval", 5 * 60 * 1000) // Default to 5 minutes if not provided
            //currentIndex = it.getIntExtra("currentIndex", 0)
        }

        wakeLock.acquire()

        startForeground(1, createNotification())
        handler.post(wallpaperRunnable)

        // Change wallpaper immediately when the service starts
        changeWallpaper()

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(wallpaperRunnable)
        wakeLock.release()
        super.onDestroy()
    }

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
    }
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
    private val imageUris = mutableListOf<Uri>()
    private lateinit var imageAdapter: ImageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        imageAdapter = ImageAdapter(imageUris, this) { uri -> removeImage(uri) }
        recyclerView.adapter = imageAdapter

        val (savedWallpapers, savedInterval) = loadSettings()
        imageUris.addAll(savedWallpapers)
        imageAdapter.notifyDataSetChanged()

        val intervalInput: EditText = findViewById(R.id.interval_input)
        intervalInput.setText((savedInterval / 60 / 1000).toString()) // Convert milliseconds to minutes

        val pickImagesButton: Button = findViewById(R.id.pick_images_button)
        pickImagesButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(intent, pickImages)
        }

        val startServiceButton: Button = findViewById(R.id.start_service_button)
        startServiceButton.setOnClickListener {
            val intervalInput: EditText = findViewById(R.id.interval_input)
            val interval = intervalInput.text.toString().toIntOrNull()

            if (interval == null || interval <= 0) {
                Toast.makeText(this, "Please enter a valid interval in minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (imageUris.isNotEmpty()) {
                saveSettings(imageUris, interval * 60 * 1000L) // Convert minutes to milliseconds

                val serviceIntent = Intent(this, WallpaperService::class.java)
                serviceIntent.putParcelableArrayListExtra("imageUris", ArrayList(imageUris))
                serviceIntent.putExtra("interval", interval * 60 * 1000L) // Convert minutes to milliseconds

                // Request exclusion from battery optimization
                //requestBatteryOptimizationExclusion()

                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            }
        }

        val stopServiceButton: Button = findViewById(R.id.stop_service_button)
        stopServiceButton.setOnClickListener {
            val serviceIntent = Intent(this, WallpaperService::class.java)
            stopService(serviceIntent)
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
                        newImageUris.add(imageUri)
                    }
                } else if (it.data != null) {
                    val imageUri = it.data!!
                    newImageUris.add(imageUri)
                }

                // Avoid duplications by adding only new images
                val uniqueImageUris = newImageUris.filter { newUri -> !imageUris.contains(newUri) }
                imageUris.addAll(uniqueImageUris)
            }
            imageAdapter.notifyDataSetChanged()
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        val intent = Intent()
        val packageName = packageName
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun saveSettings(wallpapers: List<Uri>, interval: Long) {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // Convert List<Uri> to Set<String> in chunks
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
        val allUris = mutableListOf<Uri>()
        var index = 0
        while (true) {
            val key = "wallpapers_$index"
            val uriStrings = sharedPreferences.getStringSet(key, null) ?: break
            allUris.addAll(uriStrings.map { Uri.parse(it) })
            index++
        }
        val interval = sharedPreferences.getLong("interval", 5 * 60 * 1000)  // Default to 5 minutes in milliseconds
        return Pair(allUris, interval)
    }

    private fun removeImage(uri: Uri) {
        imageUris.remove(uri)
        imageAdapter.notifyDataSetChanged()
    }
}



