package com.remote.pccontroller

import android.content.ClipData
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ClipboardWatcher {

    private const val TAG = "ClipboardWatcher"
    private var lastKnownText: String = ""
    private var lastPcClipVersion: Int = 0

    private var clipboardService: Any? = null
    private var getClipMethod: Method? = null
    private var setClipMethod: Method? = null

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== ClipboardWatcher Starting as Root/Shell ===")
        val ip = if (args.isNotEmpty()) args[0] else "192.168.11.109"
        val port = if (args.size > 1) args[1] else "5050"
        println("Target PC Server: http://$ip:$port")

        bypassHiddenApiRestrictions()
        initClipboardService()

        // Thread 1: PC -> Phone Sync (Long Polling from PC server)
        val pcPollThread = Thread({
            runPcToPhoneSync(ip, port)
        }, "PcToPhoneSyncThread")
        pcPollThread.isDaemon = true
        pcPollThread.start()

        // Thread 2: Phone -> PC Sync (Continuous Polling Loop)
        runPhoneToPcSync(ip, port)
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getMethod("getRuntime")
            val runtime = getRuntimeMethod.invoke(null)
            val setHiddenApiExemptionsMethod = vmRuntimeClass.getMethod("setHiddenApiExemptions", Array<String>::class.java)
            setHiddenApiExemptionsMethod.invoke(runtime, arrayOf("L"))
            println("Bypassed hidden API restrictions successfully.")
        } catch (e: Throwable) {
            println("Notice: VMRuntime hidden API exemption skipped: ${e.message}")
        }
    }

    private fun initClipboardService(): Boolean {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "clipboard") as? IBinder
            if (binder == null) {
                println("Failed to obtain 'clipboard' IBinder from ServiceManager")
                return false
            }

            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)
            if (service == null) {
                println("Failed to get IClipboard interface from Stub")
                return false
            }
            clipboardService = service

            for (m in service.javaClass.methods) {
                if (m.name == "getPrimaryClip" && getClipMethod == null) {
                    m.isAccessible = true
                    getClipMethod = m
                    println("Mapped getPrimaryClip: $m")
                }
                if (m.name == "setPrimaryClip" && setClipMethod == null) {
                    m.isAccessible = true
                    setClipMethod = m
                    println("Mapped setPrimaryClip: $m")
                }
            }
            return getClipMethod != null && setClipMethod != null
        } catch (e: Exception) {
            println("Error in initClipboardService: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun getPrimaryClipText(): String? {
        val service = clipboardService ?: return null
        val method = getClipMethod ?: return null
        try {
            val paramTypes = method.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val pt = paramTypes[i]
                when {
                    pt == String::class.java -> args[i] = "com.android.shell"
                    pt == Int::class.javaPrimitiveType || pt == java.lang.Integer::class.java -> args[i] = 0
                    pt == Boolean::class.javaPrimitiveType || pt == java.lang.Boolean::class.java -> args[i] = false
                    else -> args[i] = null
                }
            }
            val clip = method.invoke(service, *args) as? ClipData ?: return null
            if (clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text?.toString()
                if (!text.isNullOrEmpty()) return text
                val uri = item.uri
                if (uri != null) return uri.toString()
                val intent = item.intent
                if (intent != null) return intent.toUri(0)
            }
        } catch (e: Exception) {
            // Ignore security errors or reinitialize if needed
        }
        return null
    }

    private fun setPrimaryClipText(text: String): Boolean {
        val service = clipboardService ?: return false
        val method = setClipMethod ?: return false
        try {
            val clip = ClipData.newPlainText("PC Remote", text)
            val paramTypes = method.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val pt = paramTypes[i]
                when {
                    pt == ClipData::class.java -> args[i] = clip
                    pt == String::class.java -> args[i] = "com.android.shell"
                    pt == Int::class.javaPrimitiveType || pt == java.lang.Integer::class.java -> args[i] = 0
                    pt == Boolean::class.javaPrimitiveType || pt == java.lang.Boolean::class.java -> args[i] = false
                    else -> args[i] = null
                }
            }
            method.invoke(service, *args)
            return true
        } catch (e: Exception) {
            println("Error in setPrimaryClipText: ${e.message}")
            return false
        }
    }

    private fun runPhoneToPcSync(ip: String, port: String) {
        println("Starting Phone -> PC clipboard monitoring...")
        while (true) {
            try {
                if (clipboardService == null || getClipMethod == null) {
                    initClipboardService()
                }

                val currentText = getPrimaryClipText()
                if (!currentText.isNullOrBlank() && currentText != lastKnownText) {
                    lastKnownText = currentText
                    println("New phone clipboard detected (${currentText.length} chars). Sending to PC...")
                    val success = postClipboardToPc(ip, port, currentText)
                    if (success) {
                        println("Successfully synced to PC: ${currentText.take(30)}")
                        sendToastNotification("📋 تم إرسال الحافظة إلى الحاسوب")
                    }
                }
            } catch (e: Exception) {
                println("Exception in runPhoneToPcSync: ${e.message}")
            }

            try {
                Thread.sleep(600) // 600ms polling: ultra responsive and negligible CPU
            } catch (ignored: InterruptedException) {
            }
        }
    }

    private fun runPcToPhoneSync(ip: String, port: String) {
        println("Starting PC -> Phone long-poll listener...")
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$ip:$port/api/clipboard?version=$lastPcClipVersion&wait=25")
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 35000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val version = json.optInt("version", lastPcClipVersion)
                    val text = json.optString("text", "")

                    if (version > lastPcClipVersion) {
                        lastPcClipVersion = version
                        if (text.isNotEmpty() && text != lastKnownText) {
                            println("New PC clipboard received (v$version, ${text.length} chars). Applying to phone...")
                            lastKnownText = text
                            val setOk = setPrimaryClipText(text)
                            if (setOk) {
                                println("Applied to Android clipboard: ${text.take(30)}")
                                sendToastNotification("📋 تم نسخ نص من الحاسوب")
                            }
                        }
                    }
                } else {
                    Thread.sleep(2500)
                }
            } catch (e: Exception) {
                // PC server may be sleeping or unreachable
                try {
                    Thread.sleep(2500)
                } catch (ignored: Exception) {
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun postClipboardToPc(ip: String, port: String, text: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip:$port/api/clipboard")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true

            val payload = JSONObject().apply { put("text", text) }.toString()
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }

            conn.responseCode == 200
        } catch (e: Exception) {
            println("Failed to post clipboard to PC: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun sendToastNotification(msg: String) {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "/system/bin/am", "broadcast",
                "-a", "com.remote.pccontroller.CLIPBOARD_NOTIFY",
                "--es", "message", msg
            ))
        } catch (ignored: Exception) {
        }
    }
}
