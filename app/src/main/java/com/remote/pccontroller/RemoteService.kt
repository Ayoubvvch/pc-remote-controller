package com.remote.pccontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteService : Service() {

    companion object {
        const val CHANNEL_ID = "pc_remote_bg_service"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "RemoteService"
    }

    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastKnownText: String = ""
    private var lastPcClipVersion: Int = 0

    private val longPollClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleLocalClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering clip listener", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("pc_ip", "192.168.11.109") ?: "192.168.11.109"

        val notification = buildNotification("🟢 PcConnected - متصل بالحاسوب", "مزامنة الحافظة والتحكم بالخلفية نشطة ($ip)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isRunning) {
            isRunning = true
            startPcClipboardLongPoll()
        }

        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: Send phone clipboard to PC
        val sendClipIntent = Intent(this, TransparentClipActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sendClipPendingIntent = PendingIntent.getActivity(
            this, 1, sendClipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Quick Screenshot of PC
        val screenshotIntent = Intent(this, ServiceActionReceiver::class.java).apply {
            action = ServiceActionReceiver.ACTION_SCREENSHOT
        }
        val screenshotPendingIntent = PendingIntent.getBroadcast(
            this, 2, screenshotIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 3: Stop background service
        val stopIntent = Intent(this, ServiceActionReceiver::class.java).apply {
            action = ServiceActionReceiver.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 3, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pc_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_refresh, "📋 إرسال الحافظة", sendClipPendingIntent)
            .addAction(R.drawable.ic_pc_launcher, "📸 لقطة شاشة", screenshotPendingIntent)
            .addAction(R.drawable.ic_close, "⏹️ إيقاف", stopPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startPcClipboardLongPoll() {
        Thread {
            while (isRunning) {
                val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
                val ip = prefs.getString("pc_ip", "192.168.11.109") ?: "192.168.11.109"
                val port = prefs.getString("pc_port", "5050") ?: "5050"
                val url = "http://$ip:$port/api/clipboard?version=$lastPcClipVersion&wait=25"

                try {
                    val request = Request.Builder().url(url).get().build()
                    val response = longPollClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        response.close()

                        val json = JSONObject(body)
                        val version = json.optInt("version", lastPcClipVersion)
                        val text = json.optString("text", "")

                        if (version > lastPcClipVersion) {
                            lastPcClipVersion = version
                            if (text.isNotEmpty() && text != lastKnownText) {
                                lastKnownText = text
                                mainHandler.post {
                                    applyTextToPhoneClipboard(text)
                                }
                            }
                        }
                    } else {
                        response.close()
                        Thread.sleep(3000)
                    }
                } catch (e: Exception) {
                    // Server might be sleeping or unreachable
                    try {
                        Thread.sleep(3000)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }.start()
    }

    private fun applyTextToPhoneClipboard(text: String) {
        try {
            val clip = ClipData.newPlainText("PC Remote", text)
            clipboardManager?.setPrimaryClip(clip)
            val preview = if (text.length > 30) text.take(30) + "..." else text
            Toast.makeText(applicationContext, "📋 تم نسخ نص من الحاسوب:\n$preview", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting phone clipboard", e)
        }
    }

    private fun handleLocalClipboardChanged() {
        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank() && text != lastKnownText) {
                    lastKnownText = text
                    sendLocalClipboardToPc(text)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun sendLocalClipboardToPc(text: String) {
        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("pc_ip", "192.168.11.109") ?: "192.168.11.109"
        val port = prefs.getString("pc_port", "5050") ?: "5050"
        val url = "http://$ip:$port/api/clipboard"

        val jsonPayload = JSONObject().apply {
            put("text", text)
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        val preview = if (text.length > 25) text.take(25) + "..." else text
        apiClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Failed to auto-send clipboard to PC: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val successful = response.isSuccessful
                response.close()
                if (successful) {
                    mainHandler.post {
                        Toast.makeText(applicationContext, "📋 تم نسخ النص إلى الحاسوب:\n$preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PC Remote Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة الاتصال الدائم ومزامنة الحافظة مع الحاسوب"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
