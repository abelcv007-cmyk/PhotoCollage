package com.abelcarballeira.photocollage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abelcarballeira.photocollage.databinding.ItemPhotoBinding

/**
 * Adaptador de la cuadrícula. Cada celda muestra la foto (ya editada) sin deformarla,
 * su número identificativo y, si está seleccionada, un borde y una marca.
 */
class PhotoAdapter(
    private val onPhotoClick: (photoId: Long) -> Unit
) : ListAdapter<Photo, PhotoAdapter.PhotoViewHolder>(PhotoDiff) {

    class PhotoViewHolder(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = PhotoViewHolder(binding)
        binding.root.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onPhotoClick(getItem(position).id)
        }
        return holder
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = getItem(position)
        val binding = holder.binding
        val context = binding.root.context
        binding.image.setImageBitmap(photo.display)
        binding.number.text = photo.number.toString()
        binding.cell.isSelected = photo.selected
        binding.check.isVisible = photo.selected
        binding.root.contentDescription = if (photo.selected) {
            context.getString(R.string.photo_description_selected, photo.number)
        } else {
            context.getString(R.string.photo_description, photo.number)
        }
    }
}

private object PhotoDiff : DiffUtil.ItemCallback<Photo>() {
    override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem == newItem
}
