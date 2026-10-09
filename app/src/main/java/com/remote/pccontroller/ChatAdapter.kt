package com.remote.pccontroller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val client: OkHttpClient,
    private val onImageClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_PC = 2
        private val imageMemoryCache = LruCache<String, Bitmap>(16 * 1024 * 1024)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_PC
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_pc, parent, false)
            PcViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = messages[position]
        if (holder is UserViewHolder) {
            holder.tvMessage.text = item.message
            holder.tvTime.text = item.time
        } else if (holder is PcViewHolder) {
            holder.tvMessage.text = item.message
            holder.tvTime.text = item.time

            val imageUrl = item.imageUrl
            if (!imageUrl.isNullOrEmpty()) {
                holder.layoutImageContainer.visibility = View.VISIBLE
                holder.ivScreenshot.tag = imageUrl

                val cached = imageMemoryCache.get(imageUrl)
                if (cached != null) {
                    holder.ivScreenshot.setImageBitmap(cached)
                    holder.pbImageLoading.visibility = View.GONE
                } else {
                    holder.ivScreenshot.setImageDrawable(null)
                    holder.pbImageLoading.visibility = View.VISIBLE

                    val req = Request.Builder().url(imageUrl).build()
                    client.newCall(req).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            holder.ivScreenshot.post {
                                if (holder.ivScreenshot.tag == imageUrl) {
                                    holder.pbImageLoading.visibility = View.GONE
                                }
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                response.close()
                                if (bytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        imageMemoryCache.put(imageUrl, bitmap)
                                        holder.ivScreenshot.post {
                                            if (holder.ivScreenshot.tag == imageUrl) {
                                                holder.ivScreenshot.setImageBitmap(bitmap)
                                                holder.pbImageLoading.visibility = View.GONE
                                            }
                                        }
                                        return
                                    }
                                }
                            }
                            holder.ivScreenshot.post {
                                if (holder.ivScreenshot.tag == imageUrl) {
                                    holder.pbImageLoading.visibility = View.GONE
                                }
                            }
                        }
                    })
                }

                holder.ivScreenshot.setOnClickListener {
                    onImageClick?.invoke(imageUrl)
                }
            } else {
                holder.layoutImageContainer.visibility = View.GONE
                holder.ivScreenshot.setOnClickListener(null)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
    }

    class PcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        val layoutImageContainer: View = itemView.findViewById(R.id.layoutImageContainer)
        val ivScreenshot: ImageView = itemView.findViewById(R.id.ivScreenshot)
        val pbImageLoading: ProgressBar = itemView.findViewById(R.id.pbImageLoading)
    }
}
