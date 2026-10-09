package com.remote.pccontroller

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var rvMessages: RecyclerView
    private lateinit var etCommand: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var tvConnectionStatus: TextView

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)

        // Initialize views
        rvMessages = findViewById(R.id.rvMessages)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)

        // Setup RecyclerView
        adapter = ChatAdapter(messages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = adapter

        // Welcome message
        addPcMessage(
            "👋 مرحباً بك في لوحة تحكم الحاسوب!\n" +
            "يمكنك كتابة أي أمر مثل 'sleep' أو 'lock' أو 'shutdown' أو استخدام الأزرار بالأعلى."
        )

        // Button listeners
        btnSend.setOnClickListener {
            val text = etCommand.text.toString().trim()
            if (text.isNotEmpty()) {
                sendCommand(text)
                etCommand.text.clear()
            }
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
        findViewById<TextView>(R.id.chipSleep).setOnClickListener { sendCommand("sleep") }
        findViewById<TextView>(R.id.chipLock).setOnClickListener { sendCommand("lock") }
        findViewById<TextView>(R.id.chipHibernate).setOnClickListener { sendCommand("hibernate") }
        findViewById<TextView>(R.id.chipStatus).setOnClickListener { sendCommand("status") }
        findViewById<TextView>(R.id.chipMute).setOnClickListener { sendCommand("mute") }
        findViewById<TextView>(R.id.chipCancel).setOnClickListener { sendCommand("cancel") }
        findViewById<TextView>(R.id.chipRestart).setOnClickListener {
            showConfirmationDialog("إعادة التشغيل", "هل تريد بالتأكيد إعادة تشغيل الحاسوب؟") {
                sendCommand("restart")
            }
        }
        findViewById<TextView>(R.id.chipShutdown).setOnClickListener {
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
                try {
                    val json = JSONObject(body)
                    if (json.has("message")) {
                        reply = json.getString("message")
                    }
                } catch (ignored: Exception) {
                }

                runOnUiThread {
                    tvConnectionStatus.text = "🟢 متصل ($baseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                    addPcMessage(if (reply.isNotEmpty()) reply else "✅ تم استلام الأمر بنجاح.")
                }
            }
        })
    }

    private fun addUserMessage(text: String) {
        val time = timeFormat.format(Date())
        messages.add(ChatMessage(text, isUser = true, time = time))
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private fun addPcMessage(text: String) {
        val time = timeFormat.format(Date())
        messages.add(ChatMessage(text, isUser = false, time = time))
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
