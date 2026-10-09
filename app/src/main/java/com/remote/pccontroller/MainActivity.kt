package com.remote.pccontroller

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var rvMessages: RecyclerView
    private lateinit var etCommand: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var tvConnectionStatus: TextView

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    // File Picker for sending documents/photos to PC
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadFileToPc(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)

        // Initialize views
        rvMessages = findViewById(R.id.rvMessages)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)

        // Setup RecyclerView
        adapter = ChatAdapter(messages, client) { imageUrl ->
            showFullscreenImage(imageUrl)
        }
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = adapter

        // Welcome message
        addPcMessage(
            "👋 مرحباً بك في لوحة تحكم الحاسوب!\n" +
            "• يمكنك التقاط شاشة الحاسوب عبر أمر 'screenshot' أو زر 📸 بالأعلى.\n" +
            "• يمكنك إرسال أي صورة أو ملف لحفظها في Downloads عبر زر 📎."
        )

        // Button listeners
        btnSend.setOnClickListener {
            val text = etCommand.text.toString().trim()
            if (text.isNotEmpty()) {
                sendCommand(text)
                etCommand.text.clear()
            }
        }

        btnAttach.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                btnSend.performClick()
                true
            } else {
                false
            }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnRefresh.setOnClickListener { checkConnection() }

        // Setup Quick Action Chips
        setupQuickChips()

        // Check connection on start
        checkConnection()
    }

    private fun setupQuickChips() {
        findViewById<TextView>(R.id.chipScreenshot)?.setOnClickListener { sendCommand("screenshot") }
        findViewById<TextView>(R.id.chipMessi)?.setOnClickListener { sendCommand("messi") }
        findViewById<TextView>(R.id.chipRain)?.setOnClickListener { sendCommand("sleep rain 15min") }
        findViewById<TextView>(R.id.chipRainMin)?.setOnClickListener { sendCommand("sleep rain 15m min") }
        findViewById<TextView>(R.id.chipVol15)?.setOnClickListener { sendCommand("s 15") }
        findViewById<TextView>(R.id.chipVol25)?.setOnClickListener { sendCommand("s 25") }
        findViewById<TextView>(R.id.chipVol40)?.setOnClickListener { sendCommand("s 40") }
        findViewById<TextView>(R.id.chipMute)?.setOnClickListener { sendCommand("mute") }
        findViewById<TextView>(R.id.chipSleep)?.setOnClickListener { sendCommand("sleep") }
        findViewById<TextView>(R.id.chipLock)?.setOnClickListener { sendCommand("lock") }
        findViewById<TextView>(R.id.chipStatus)?.setOnClickListener { sendCommand("status") }
        findViewById<TextView>(R.id.chipCancel)?.setOnClickListener { sendCommand("stop") }
        findViewById<TextView>(R.id.chipRestart)?.setOnClickListener {
            showConfirmationDialog("إعادة التشغيل", "هل تريد بالتأكيد إعادة تشغيل الحاسوب؟") {
                sendCommand("restart")
            }
        }
        findViewById<TextView>(R.id.chipShutdown)?.setOnClickListener {
            showConfirmationDialog("إيقاف التشغيل", "هل تريد بالتأكيد إيقاف تشغيل الحاسوب؟") {
                sendCommand("shutdown")
            }
        }
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("نعم") { _, _ -> onConfirm() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun getBaseUrl(): String {
        val ip = prefs.getString("pc_ip", "192.168.11.109") ?: "192.168.11.109"
        val port = prefs.getString("pc_port", "5050") ?: "5050"
        return "http://$ip:$port"
    }

    private fun checkConnection() {
        val baseUrl = getBaseUrl()
        tvConnectionStatus.text = "🟡 جاري الفحص... ($baseUrl)"
        tvConnectionStatus.setTextColor(Color.parseColor("#F59E0B"))

        val request = Request.Builder()
            .url("$baseUrl/ping")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvConnectionStatus.text = "🔴 غير متصل ($baseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val successful = response.isSuccessful
                response.close()
                runOnUiThread {
                    if (successful) {
                        tvConnectionStatus.text = "🟢 متصل ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        tvConnectionStatus.text = "🔴 خطأ في الاستجابة ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }
        })
    }

    private fun sendCommand(command: String) {
        addUserMessage(command)

        val baseUrl = getBaseUrl()
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val jsonPayload = JSONObject().apply {
            put("command", command)
        }.toString()

        val requestBody = jsonPayload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/command")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    addPcMessage("❌ تعذر الاتصال بالحاسوب ($baseUrl)\nتأكد أن السيرفر يعمل وعلى نفس شبكة الواي فاي.")
                    tvConnectionStatus.text = "🔴 غير متصل ($baseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var reply = body
                var imageUrl: String? = null
                try {
                    val json = JSONObject(body)
                    if (json.has("message")) {
                        reply = json.getString("message")
                    }
                    if (json.has("image_url")) {
                        val path = json.getString("image_url")
                        imageUrl = if (path.startsWith("http")) path else "$baseUrl$path"
                    }
                } catch (ignored: Exception) {
                }

                runOnUiThread {
                    tvConnectionStatus.text = "🟢 متصل ($baseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                    addPcMessage(
                        if (reply.isNotEmpty()) reply else "✅ تم استلام الأمر بنجاح.",
                        imageUrl = imageUrl
                    )
                }
            }
        })
    }

    private fun uploadFileToPc(uri: Uri) {
        var fileName = "file_${System.currentTimeMillis()}"
        var fileSize: Long = 0

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val sizeFormatted = formatFileSize(fileSize)
        addUserMessage("📎 جاري إرسال: $fileName ($sizeFormatted)")

        val baseUrl = getBaseUrl()
        val encodedName = Base64.encodeToString(fileName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength(): Long = if (fileSize > 0) fileSize else -1L
            override fun writeTo(sink: okio.BufferedSink) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/upload")
            .header("X-Filename-B64", encodedName)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    addPcMessage("❌ فشل إرسال الملف إلى الحاسوب: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var reply = body
                try {
                    val json = JSONObject(body)
                    if (json.has("message")) {
                        reply = json.getString("message")
                    }
                } catch (ignored: Exception) {
                }

                runOnUiThread {
                    addPcMessage(if (reply.isNotEmpty()) reply else "✅ تم استلام الملف بنجاح في Downloads!")
                }
            }
        })
    }

    private fun showFullscreenImage(imageUrl: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_image_preview)

        val ivFullImage = dialog.findViewById<ImageView>(R.id.ivFullImage)
        val pbLoading = dialog.findViewById<ProgressBar>(R.id.pbLoading)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnClose)

        btnClose.setOnClickListener { dialog.dismiss() }
        ivFullImage.setOnClickListener { dialog.dismiss() }

        pbLoading.visibility = View.VISIBLE
        val req = Request.Builder().url(imageUrl).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { pbLoading.visibility = View.GONE }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    response.close()
                    if (bytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        runOnUiThread {
                            pbLoading.visibility = View.GONE
                            if (bitmap != null) {
                                ivFullImage.setImageBitmap(bitmap)
                            }
                        }
                        return
                    }
                }
                runOnUiThread { pbLoading.visibility = View.GONE }
            }
        })

        dialog.show()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes >= 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        return "$bytes B"
    }

    private fun addUserMessage(text: String) {
        val time = timeFormat.format(Date())
        messages.add(ChatMessage(text, isUser = true, time = time))
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private fun addPcMessage(text: String, imageUrl: String? = null) {
        val time = timeFormat.format(Date())
        messages.add(ChatMessage(text, isUser = false, time = time, imageUrl = imageUrl))
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etIpAddress)
        val etPort = view.findViewById<EditText>(R.id.etPort)

        etIp.setText(prefs.getString("pc_ip", "192.168.11.109"))
        etPort.setText(prefs.getString("pc_port", "5050"))

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("حفظ") { _, _ ->
                val newIp = etIp.text.toString().trim()
                val newPort = etPort.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    prefs.edit()
                        .putString("pc_ip", newIp)
                        .putString("pc_port", if (newPort.isNotEmpty()) newPort else "5050")
                        .apply()
                    Toast.makeText(this, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
                    checkConnection()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
