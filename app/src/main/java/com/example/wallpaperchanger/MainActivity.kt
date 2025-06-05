package com.example.wallpaperchanger

//import CrossfadeLiveWallpaper
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import androidx.annotation.RequiresExtension
import com.example.wallpaperchanger.R
import com.example.wallpaperchanger.adapters.ImageAdapter
import com.example.wallpaperchanger.services.CrossfadeLiveWallpaper
import com.example.wallpaperchanger.services.WallpaperService
import com.example.wallpaperchanger.utils.StorageUtils

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
        setupPeriodicCleanup()
    }

    private fun setupPeriodicCleanup() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                StorageUtils.cleanupUnusedImages(applicationContext, imageUris)
                handler.postDelayed(this, 7 * 24 * 60 * 60 * 1000) // Run weekly
            }
        }, 7 * 24 * 60 * 60 * 1000)
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

    private fun setupRecyclerView() {
        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        imageAdapter = ImageAdapter(this, imageUris) { selectedImages ->
            // Handle selected images here if needed
        }
        recyclerView.adapter = imageAdapter

        val savedWallpapers: List<Uri> = loadSettings()
        imageUris.clear()
        imageUris.addAll(savedWallpapers)
        imageAdapter.notifyDataSetChanged()
        updateImageCounter()
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
                saveSettings(imageUris)
                StorageUtils.cleanupUnusedImages(this, imageUris)
                updateImageCounter()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layoutInflater.inflate(R.layout.dialog_settings, null))
            .setPositiveButton("Save") { dialog, _ ->
                val switchTransitions = (dialog as AlertDialog).findViewById<Switch>(R.id.enable_transitions)
                val editInterval = dialog.findViewById<EditText>(R.id.interval_input)
                val transitionSpinner = dialog.findViewById<Spinner>(R.id.transition_type)
                val screenGroup = dialog.findViewById<RadioGroup>(R.id.wallpaper_screen_group)

                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("transitions_enabled", switchTransitions?.isChecked ?: true)
                    putLong("interval", (editInterval?.text.toString().toIntOrNull() ?: 5) * 60 * 1000L)
                    putInt("transition_type", transitionSpinner?.selectedItemPosition ?: 0)
                    putInt("wallpaper_screen", screenGroup?.checkedRadioButtonId ?: R.id.both_screens)
                    apply()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.findViewById<TextView>(R.id.image_counter)?.text = "Images: ${imageUris.size}"

        val spinner = dialog.findViewById<Spinner>(R.id.transition_type)
        ArrayAdapter.createFromResource(
            this,
            R.array.transition_types,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner?.adapter = adapter
        }

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        dialog.findViewById<RadioGroup>(R.id.wallpaper_screen_group)?.check(
            prefs.getInt("wallpaper_screen", R.id.both_screens)
        )
        dialog.findViewById<Switch>(R.id.enable_transitions)?.isChecked =
            prefs.getBoolean("transitions_enabled", true)
        dialog.findViewById<EditText>(R.id.interval_input)?.setText(
            (prefs.getLong("interval", 5 * 60 * 1000) / 60 / 1000).toString()
        )
        spinner?.setSelection(prefs.getInt("transition_type", 0))
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all { permission ->
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

    // Add these methods to your MainActivity class

    private fun startWallpaperService() {
        if (imageUris.isEmpty()) {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if our live wallpaper is already set
        val wallpaperManager = WallpaperManager.getInstance(this)
        val currentWallpaper = wallpaperManager.wallpaperInfo

        if (currentWallpaper?.packageName == packageName) {
            // Our live wallpaper is already active, just update it
            val sharedPreferences = getSharedPreferences("AppSettings", MODE_PRIVATE)
            val interval = sharedPreferences.getLong("interval", 5 * 60 * 1000)

            // Fixed: Changed selectedUris to imageUris
            CrossfadeLiveWallpaper.updateWallpaperSettings(this, imageUris, interval)
            Toast.makeText(this, "Wallpaper settings updated", Toast.LENGTH_SHORT).show()
        } else {
            // Need to set our live wallpaper - but first save the images for the wallpaper to use
            val sharedPreferences = getSharedPreferences("AppSettings", MODE_PRIVATE)
            val interval = sharedPreferences.getLong("interval", 5 * 60 * 1000)
            CrossfadeLiveWallpaper.updateWallpaperSettings(this, imageUris, interval)
            showLiveWallpaperDialog()
        }
    }

    private fun showLiveWallpaperDialog() {
        AlertDialog.Builder(this)
            .setTitle("Set Live Wallpaper")
            .setMessage("To enable smooth crossfade transitions, you need to set this app as your live wallpaper. This will open the wallpaper picker.")
            .setPositiveButton("Set Wallpaper") { _, _ ->
                openLiveWallpaperSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openLiveWallpaperSettings() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, CrossfadeLiveWallpaper::class.java)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general wallpaper settings
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open wallpaper settings", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopWallpaperService() {
        // For live wallpaper, we can't directly stop it
        // User needs to change wallpaper manually or we can clear the images
        CrossfadeLiveWallpaper.updateWallpaperSettings(this, emptyList(), 0)
        Toast.makeText(this, "Wallpaper changes stopped. Change your wallpaper to fully disable.", Toast.LENGTH_LONG).show()
    }

// Add this import at the top of your file
// import android.content.ComponentName

    /*private fun startWallpaperService() {
        stopWallpaperService()
        if (imageUris.isEmpty()) {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Service not started - no images")
            return
        }

        val sharedPreferences = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val interval = sharedPreferences.getLong("interval", 5 * 60 * 1000)

        Log.d("MainActivity", "Starting service with ${imageUris.size} images")
        val serviceIntent = Intent(this, WallpaperService::class.java).apply {
            putParcelableArrayListExtra("imageUris", ArrayList(imageUris))
            putExtra("interval", interval)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("MainActivity", "Service start requested")
    }

    private fun stopWallpaperService() {
        stopService(Intent(this, WallpaperService::class.java))
    }*/

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

    private fun processNewImages(data: Intent?) {
        data?.let {
            val newImageUris = mutableListOf<Uri>()
            if (it.clipData != null) {
                val count = it.clipData!!.itemCount
                for (i in 0 until count) {
                    val imageUri = it.clipData!!.getItemAt(i).uri
                    StorageUtils.copyImageToLocalStorage(this, imageUri)?.let { localUri ->
                        Log.d("DuplicateCheck", "Checking new image: ${localUri.path}")
                        imageUris.forEach { existing ->
                            Log.d("DuplicateCheck", "Against existing: ${existing.path}")
                        }

                        val isDuplicate = imageUris.any { existing ->
                            val isDup = existing.path == localUri.path
                            Log.d("DuplicateCheck", "Is duplicate: $isDup")
                            isDup
                        }

                        if (!isDuplicate) {
                            newImageUris.add(localUri)
                            Log.d("StorageDebug", "Added local image: $localUri")
                        }
                    }
                }
            } else if (it.data != null) {
                val imageUri = it.data!!
                StorageUtils.copyImageToLocalStorage(this, imageUri)?.let { localUri ->
                    val isDuplicate = imageUris.any { existing -> existing.path == localUri.path }
                    if (!isDuplicate) {
                        newImageUris.add(localUri)
                        Log.d("StorageDebug", "Added single local image: $localUri")
                    }
                }
            }

            imageUris.addAll(newImageUris)
            imageAdapter.notifyDataSetChanged()
            saveSettings(imageUris)
            updateImageCounter()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickImages && resultCode == Activity.RESULT_OK) {
            processNewImages(data)
        }
    }

    private fun updateImageCounter() {
        findViewById<TextView>(R.id.image_counter)?.let { counter ->
            counter.text = "Images: ${imageUris.size}"
        }
    }

    private fun saveSettings(wallpapers: List<Uri>) {
        val sharedPreferences = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        editor.putInt("wallpaper_count", wallpapers.size)
        wallpapers.chunked(100).forEachIndexed { index, chunk ->
            val key = "wallpapers_$index"
            val uriStrings = chunk.map { it.toString() }.toSet()
            editor.putStringSet(key, uriStrings)
        }
        editor.apply()
    }

    private fun loadSettings(): List<Uri> {
        val sharedPreferences = getSharedPreferences("AppSettings", MODE_PRIVATE)
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

    override fun onDestroy() {
        super.onDestroy()
        StorageUtils.cleanupUnusedImages(this, imageUris)
    }
}

