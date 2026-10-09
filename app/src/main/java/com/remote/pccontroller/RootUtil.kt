package com.remote.pccontroller

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object RootUtil {

    private const val TAG = "RootUtil"

    fun isRootAvailable(): Boolean {
        val suPaths = arrayOf(
            "/product/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "su"
        )
        for (path in suPaths) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(path, "-v"))
                val exited = process.waitFor(2, TimeUnit.SECONDS)
                if (exited && process.exitValue() == 0) {
                    return true
                }
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    fun executeSu(command: String): Boolean {
        val suPaths = arrayOf("/product/bin/su", "su", "/system/bin/su", "/system/xbin/su")
        for (su in suPaths) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
                val exited = process.waitFor(5, TimeUnit.SECONDS)
                if (exited && process.exitValue() == 0) {
                    Log.d(TAG, "Root command success [$su]: $command")
                    return true
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        return false
    }

    fun writeRootFile(filePath: String, content: String, permissions: String = "755"): Boolean {
        val suPaths = arrayOf("/product/bin/su", "su", "/system/bin/su", "/system/xbin/su")
        for (su in suPaths) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(su, "-c", "cat > $filePath && chmod $permissions $filePath"))
                process.outputStream.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                val exited = process.waitFor(5, TimeUnit.SECONDS)
                if (exited && process.exitValue() == 0) {
                    Log.d(TAG, "writeRootFile success [$su]: $filePath")
                    return true
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        return false
    }

    fun autoGrantAllPermissions(context: Context) {
        val pkg = context.packageName
        Thread {
            try {
                val commands = listOf(
                    "pm grant $pkg android.permission.POST_NOTIFICATIONS",
                    "pm grant $pkg android.permission.READ_LOGS",
                    "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow",
                    "cmd appops set $pkg READ_CLIPBOARD allow",
                    "cmd appops set $pkg WRITE_CLIPBOARD allow",
                    "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                    "dumpsys deviceidle whitelist +$pkg"
                )
                for (cmd in commands) {
                    executeSu(cmd)
                }
                Log.i(TAG, "Auto-granted all permissions for $pkg via Magisk SU")
            } catch (e: Exception) {
                Log.e(TAG, "Error granting permissions via root", e)
            }
        }.start()
    }
}
