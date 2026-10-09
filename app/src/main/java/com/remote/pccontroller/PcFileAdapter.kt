package com.remote.pccontroller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PcFileAdapter(
    private val items: List<PcFileItem>,
    private val onItemClick: (PcFileItem) -> Unit,
    private val onDownloadClick: (PcFileItem) -> Unit
) : RecyclerView.Adapter<PcFileAdapter.FileViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pc_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = items[position]
        holder.tvFileName.text = item.name

        if (item.isDirectory) {
            holder.tvFileIcon.text = "📁"
            holder.tvFileDetails.text = "مجلد"
            holder.btnDownload.visibility = View.GONE
        } else {
            holder.tvFileIcon.text = getFileIcon(item.ext)
            holder.tvFileDetails.text = item.sizeStr
            holder.btnDownload.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        holder.btnDownload.setOnClickListener {
            onDownloadClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun getFileIcon(ext: String): String {
        return when (ext.lowercase()) {
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp" -> "🖼️"
            ".mp4", ".mkv", ".avi", ".mov", ".wmv" -> "🎬"
            ".mp3", ".wav", ".flac", ".ogg", ".m4a" -> "🎵"
            ".pdf" -> "📕"
            ".zip", ".rar", ".7z", ".tar", ".gz" -> "📦"
            ".doc", ".docx", ".txt", ".md", ".pdf" -> "📄"
            ".exe", ".msi", ".bat", ".ps1", ".cmd" -> "⚙️"
            else -> "📄"
        }
    }

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvFileIcon: TextView = itemView.findViewById(R.id.tvFileIcon)
        val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        val tvFileDetails: TextView = itemView.findViewById(R.id.tvFileDetails)
        val btnDownload: ImageButton = itemView.findViewById(R.id.btnDownload)
    }
}
