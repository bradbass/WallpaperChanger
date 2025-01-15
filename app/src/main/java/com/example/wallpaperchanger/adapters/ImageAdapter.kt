package com.example.wallpaperchanger.adapters

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.wallpaperchanger.R

class ImageAdapter(
    private val context: Context,
    private val imageUris: MutableList<Uri>,
    private val onSelectionChanged: (List<Uri>) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ViewHolder>()
 {
    private val selectedImages = mutableSetOf<Uri>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.image_view)
        val checkBox: CheckBox = itemView.findViewById(R.id.image_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.image_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = imageUris[position]

        Glide.with(context)
            .load(uri)
            .centerCrop()
            .into(holder.imageView)

        holder.checkBox.isChecked = selectedImages.contains(uri)

        // Add preview click listener
        holder.imageView.setOnClickListener {
            showImagePreview(uri)
        }

        holder.checkBox.setOnClickListener {
            if (holder.checkBox.isChecked) {
                selectedImages.add(uri)
            } else {
                selectedImages.remove(uri)
            }
            onSelectionChanged(selectedImages.toList())
        }
    }

     private fun showImagePreview(uri: Uri) {
         val position = imageUris.indexOf(uri)
         val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
         val view = LayoutInflater.from(context).inflate(R.layout.fullscreen_image_viewer, null)

         val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)
         val closeButton = view.findViewById<ImageButton>(R.id.close_button)
         val deleteButton = view.findViewById<ImageButton>(R.id.delete_button)
         val imageCounter = view.findViewById<TextView>(R.id.imageCounter)

         val adapter = FullscreenImageAdapter(imageUris)
         viewPager.adapter = adapter
         viewPager.setCurrentItem(position, false)

         updateImageCounter(imageCounter, position)

         viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
             override fun onPageSelected(position: Int) {
                 updateImageCounter(imageCounter, position)
             }
         })

         closeButton.setOnClickListener { dialog.dismiss() }

         deleteButton.setOnClickListener {
             val currentPosition = viewPager.currentItem
             AlertDialog.Builder(context)
                 .setTitle("Delete Image")
                 .setMessage("Are you sure you want to delete this image?")
                 .setPositiveButton("Delete") { _, _ ->
                     imageUris.removeAt(currentPosition)
                     notifyDataSetChanged()
                     if (imageUris.isEmpty()) {
                         dialog.dismiss()
                     } else {
                         adapter.notifyDataSetChanged()
                         updateImageCounter(imageCounter, viewPager.currentItem)
                     }
                     onSelectionChanged(selectedImages.toList())
                 }
                 .setNegativeButton("Cancel", null)
                 .show()
         }

         dialog.setContentView(view)
         dialog.show()
     }

     private fun updateImageCounter(counterView: TextView, position: Int) {
         counterView.text = "${position + 1}/${imageUris.size}"
     }


     override fun getItemCount() = imageUris.size

    fun getSelectedImages(): List<Uri> = selectedImages.toList()

    fun clearSelections() {
        selectedImages.clear()
        notifyDataSetChanged()
    }
}
