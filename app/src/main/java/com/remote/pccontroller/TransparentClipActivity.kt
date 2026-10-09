package com.remote.pccontroller

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TransparentClipActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Invisible transparent activity
    }

    override fun onResume() {
        super.onResume()
        handleSendClipboard()
    }

    private fun handleSendClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }

        if (text.isBlank()) {
            Toast.makeText(this, "📋 حافظة الهاتف فارغة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("pc_ip", "192.168.11.109") ?: "192.168.11.109"
        val port = prefs.getString("pc_port", "5050") ?: "5050"
        val url = "http://$ip:$port/api/clipboard"

        val jsonPayload = JSONObject().apply {
            put("text", text)
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        val preview = if (text.length > 30) text.take(30) + "..." else text
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "❌ فشل إرسال الحافظة للحاسوب ($ip)", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                runOnUiThread {
                    Toast.makeText(applicationContext, "📋 تم إرسال الحافظة إلى الحاسوب بنجاح:\n$preview", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        })
    }
}
