package com.example.miappcamarapro3.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.miappcamarapro3.R
import java.io.File

class GalleryAdapter(
    private var images: List<File>,
    private val onImageClick: (File) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.ivGalleryItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = images[position]
        Glide.with(holder.imageView.context)
            .load(file)
            .centerCrop()
            .into(holder.imageView)

        holder.itemView.setOnClickListener { onImageClick(file) }
    }

    override fun getItemCount() = images.size

    fun updateImages(newImages: List<File>) {
        images = newImages
        notifyDataSetChanged()
    }
}
