package com.example.wallpaperchanger.adapters

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.wallpaperchanger.R
import com.example.wallpaperchanger.utils.MediaUtils

class FullscreenImageAdapter(
    private val images: List<Uri>
) : RecyclerView.Adapter<FullscreenImageAdapter.ImageViewHolder>() {

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.fullscreen_image)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fullscreen_image_item, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val uri = images[position]
        val context = holder.itemView.context

        if (MediaUtils.isVideo(context, uri)) {
            try {
                val thumb: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(800, 800), null)
                } else {
                    // For pre-Q, try using MediaStore (works for media library videos)
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        getVideoIdFromUri(context, uri),
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
                if (thumb != null) {
                    holder.imageView.setImageBitmap(thumb)
                } else {
                    holder.imageView.setImageResource(R.drawable.ic_video_placeholder)
                }
            } catch (e: Exception) {
                holder.imageView.setImageResource(R.drawable.ic_video_placeholder)
            }
        } else {
            Glide.with(context)
                .load(uri)
                .fitCenter()
                .into(holder.imageView)
        }
    }

    override fun getItemCount() = images.size

    /**
     * Attempts to get the video ID from a content URI for pre-Android Q thumbnail extraction.
     * Will return 0 if it fails, which may result in a null thumbnail.
     */
    private fun getVideoIdFromUri(context: Context, uri: Uri): Long {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                return cursor.getLong(idIndex)
            }
        }
        return 0L
    }
}