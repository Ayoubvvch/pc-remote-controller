package com.remote.pccontroller

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import android.widget.LinearLayout
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var rvMessages: RecyclerView
    private lateinit var etCommand: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnBrowseFiles: ImageButton
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
        btnBrowseFiles = findViewById(R.id.btnBrowseFiles)
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
            "• 📁 لتصفح وسحب ملفات الحاسوب: اضغط زر المجلد بالأعلى.\n" +
            "• 📸 لتصوير الشاشة: يدعم الشاشتين معاً، أو شاشة 1 أو 2.\n" +
            "• 📎 لإرسال ملفات للحاسوب: اضغط زر المشبك بجانب الكتابة."
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

        btnBrowseFiles.setOnClickListener {
            showFileBrowserDialog()
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
        findViewById<TextView>(R.id.chipBrowseFiles)?.setOnClickListener { showFileBrowserDialog() }
        findViewById<TextView>(R.id.chipScreenshotAll)?.setOnClickListener { sendCommand("screenshot all") }
        findViewById<TextView>(R.id.chipScreenshot1)?.setOnClickListener { sendCommand("screenshot 1") }
        findViewById<TextView>(R.id.chipScreenshot2)?.setOnClickListener { sendCommand("screenshot 2") }
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

    // ==========================================
    // PC File Explorer & Downloader
    // ==========================================
    private fun showFileBrowserDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_file_browser)

        val btnBackFolder = dialog.findViewById<ImageButton>(R.id.btnBackFolder)
        val btnCloseBrowser = dialog.findViewById<ImageButton>(R.id.btnCloseBrowser)
        val tvCurrentPath = dialog.findViewById<TextView>(R.id.tvCurrentPath)
        val layoutShortcuts = dialog.findViewById<LinearLayout>(R.id.layoutShortcuts)
        val rvPcFiles = dialog.findViewById<RecyclerView>(R.id.rvPcFiles)
        val pbBrowserLoading = dialog.findViewById<ProgressBar>(R.id.pbBrowserLoading)
        val tvEmptyFiles = dialog.findViewById<TextView>(R.id.tvEmptyFiles)

        rvPcFiles.layoutManager = LinearLayoutManager(this)

        var currentFolder = ""
        var parentFolder: String? = null
        val fileList = mutableListOf<PcFileItem>()
        lateinit var loadFolder: (String?) -> Unit

        val fileAdapter = PcFileAdapter(
            items = fileList,
            onItemClick = { item ->
                if (item.isDirectory) {
                    loadFolder(item.path)
                } else {
                    downloadFileFromPc(item)
                }
            },
            onDownloadClick = { item ->
                downloadFileFromPc(item)
            }
        )
        rvPcFiles.adapter = fileAdapter

        loadFolder = { folderPath: String? ->
            pbBrowserLoading.visibility = View.VISIBLE
            tvEmptyFiles.visibility = View.GONE
            val baseUrl = getBaseUrl()
            val encoded = if (!folderPath.isNullOrEmpty()) URLEncoder.encode(folderPath, "UTF-8") else ""
            val url = "$baseUrl/api/browse" + if (encoded.isNotEmpty()) "?path=$encoded" else ""

            val req = Request.Builder().url(url).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        pbBrowserLoading.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "تعذر تحميل المجلد: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        if (json.optString("status") == "success") {
                            currentFolder = json.optString("current_path")
                            parentFolder = if (json.has("parent_path") && !json.isNull("parent_path")) json.optString("parent_path") else null

                            val foldersJson = json.optJSONArray("folders") ?: JSONArray()
                            val filesJson = json.optJSONArray("files") ?: JSONArray()
                            val shortcutsJson = json.optJSONArray("shortcuts") ?: JSONArray()
                            val drivesJson = json.optJSONArray("drives") ?: JSONArray()

                            val newItems = mutableListOf<PcFileItem>()
                            for (i in 0 until foldersJson.length()) {
                                val f = foldersJson.getJSONObject(i)
                                newItems.add(
                                    PcFileItem(
                                        name = f.getString("name"),
                                        path = f.getString("path"),
                                        isDirectory = true
                                    )
                                )
                            }
                            for (i in 0 until filesJson.length()) {
                                val f = filesJson.getJSONObject(i)
                                newItems.add(
                                    PcFileItem(
                                        name = f.getString("name"),
                                        path = f.getString("path"),
                                        isDirectory = false,
                                        sizeStr = f.optString("size_str", ""),
                                        sizeBytes = f.optLong("size", 0),
                                        ext = f.optString("ext", "")
                                    )
                                )
                            }

                            runOnUiThread {
                                pbBrowserLoading.visibility = View.GONE
                                tvCurrentPath.text = currentFolder
                                btnBackFolder.isEnabled = !parentFolder.isNullOrEmpty()
                                btnBackFolder.alpha = if (!parentFolder.isNullOrEmpty()) 1.0f else 0.3f

                                fileList.clear()
                                fileList.addAll(newItems)
                                fileAdapter.notifyDataSetChanged()
                                tvEmptyFiles.visibility = if (newItems.isEmpty()) View.VISIBLE else View.GONE

                                // Populate shortcuts and drives
                                layoutShortcuts.removeAllViews()
                                for (i in 0 until shortcutsJson.length()) {
                                    val s = shortcutsJson.getJSONObject(i)
                                    val chip = createShortcutChip(s.getString("name"), s.optString("icon", "📁")) {
                                        loadFolder(s.getString("path"))
                                    }
                                    layoutShortcuts.addView(chip)
                                }
                                for (i in 0 until drivesJson.length()) {
                                    val d = drivesJson.getString(i)
                                    val chip = createShortcutChip(d, "💾") {
                                        loadFolder(d)
                                    }
                                    layoutShortcuts.addView(chip)
                                }
                            }
                            return
                        }
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    runOnUiThread {
                        pbBrowserLoading.visibility = View.GONE
                    }
                }
            })
        }

        btnBackFolder.setOnClickListener {
            if (!parentFolder.isNullOrEmpty()) {
                loadFolder(parentFolder)
            }
        }
        btnCloseBrowser.setOnClickListener { dialog.dismiss() }

        loadFolder(null)
        dialog.show()
    }

    private fun createShortcutChip(title: String, icon: String, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = "$icon $title"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(28, 14, 28, 14)
            isClickable = true
            isFocusable = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 16
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
        return tv
    }

    private fun downloadFileFromPc(item: PcFileItem) {
        Toast.makeText(this, "⏳ جاري سحب ${item.name} (${item.sizeStr})...", Toast.LENGTH_SHORT).show()
        val baseUrl = getBaseUrl()
        val encoded = URLEncoder.encode(item.path, "UTF-8")
        val url = "$baseUrl/api/download?path=$encoded"

        val req = Request.Builder().url(url).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ فشل سحب الملف: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()

                        var targetFile = File(downloadsDir, item.name)
                        var counter = 1
                        val nameWithoutExt = targetFile.nameWithoutExtension
                        val ext = targetFile.extension
                        while (targetFile.exists()) {
                            val newName = if (ext.isNotEmpty()) "$nameWithoutExt ($counter).$ext" else "$nameWithoutExt ($counter)"
                            targetFile = File(downloadsDir, newName)
                            counter++
                        }

                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        // Notify MediaScanner so file appears in Android Downloads app immediately
                        android.media.MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(targetFile.absolutePath),
                            null,
                            null
                        )

                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "✅ تم حفظ ${targetFile.name} في مجلد Download بنجاح!", Toast.LENGTH_LONG).show()
                            addPcMessage(
                                "📥 تم سحب الملف من الحاسوب بنجاح!\n" +
                                "📁 اسم الملف: ${targetFile.name}\n" +
                                "💾 الحجم: ${item.sizeStr}\n" +
                                "📍 تم الحفظ في: Download/${targetFile.name}"
                            )
                        }
                        return
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ فشل حفظ الملف", Toast.LENGTH_SHORT).show()
                }
            }
        })
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
