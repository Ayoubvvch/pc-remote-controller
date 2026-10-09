package com.remote.pccontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ServiceActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SCREENSHOT = "com.remote.pccontroller.ACTION_SCREENSHOT"
        const val ACTION_STOP_SERVICE = "com.remote.pccontroller.ACTION_STOP_SERVICE"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("pc_ip", "192.168.11.109") ?: "192.168.11.109"
        val port = prefs.getString("pc_port", "5050") ?: "5050"

        when (action) {
            ACTION_SCREENSHOT -> {
                val url = "http://$ip:$port/command"
                val jsonPayload = JSONObject().apply {
                    put("command", "screenshot all")
                }.toString()

                val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "❌ تعذر التقاط الشاشة ($ip)", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "📸 تم التقاط لقطة لجميع شاشات الحاسوب بنجاح!", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }

            ACTION_STOP_SERVICE -> {
                val serviceIntent = Intent(context, RemoteService::class.java)
                context.stopService(serviceIntent)
                Toast.makeText(context, "⏹️ تم إيقاف خدمة التحكم والمزامنة", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
