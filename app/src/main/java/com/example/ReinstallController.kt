package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ReinstallController {
    val APK_PATH = "/storage/emulated/0/ff/freefire.apk"
    val TARGET_PACKAGE = "com.dts.freefireth"

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _progress = MutableStateFlow("Idle")
    val progress: StateFlow<String> = _progress

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$time] $message"
        _logs.value = _logs.value + logLine
        _progress.value = message
    }

    suspend fun startReinstall() = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext
        _isRunning.value = true
        clearLogs()
        log("XDEL started")

        try {
            if (!Shizuku.pingBinder()) {
                log("Shizuku is not running")
                _isRunning.value = false
                return@withContext
            }
            log("Shizuku connected")

            val apkFile = File(APK_PATH)
            if (!apkFile.exists() || !apkFile.canRead()) {
                log("Free Fire APK not found at $APK_PATH")
                _isRunning.value = false
                return@withContext
            }
            val sizeMb = apkFile.length() / (1024 * 1024)
            log("APK found (~$sizeMb MB)")

            // Check if installed
            val isInstalled = runShellCommand("pm path $TARGET_PACKAGE").isNotBlank()
            
            if (isInstalled) {
                log("Removing $TARGET_PACKAGE...")
                val startUninstall = System.currentTimeMillis()
                val uninstallResult = runShellCommand("pm uninstall $TARGET_PACKAGE")
                val uninstallTime = (System.currentTimeMillis() - startUninstall) / 1000.0
                if (uninstallResult.contains("Success", ignoreCase = true)) {
                    log("Uninstall completed in ${String.format("%.1f", uninstallTime)} s")
                } else {
                    log("Uninstall failed: $uninstallResult")
                    _isRunning.value = false
                    return@withContext
                }
            } else {
                log("$TARGET_PACKAGE is not currently installed")
            }

            log("Installing APK...")
            val startInstall = System.currentTimeMillis()
            val installResult = runShellCommand("pm install -r -d $APK_PATH")
            val installTime = (System.currentTimeMillis() - startInstall) / 1000.0
            
            if (installResult.contains("Success", ignoreCase = true)) {
                log("Installation completed in ${String.format("%.1f", installTime)} s")
            } else {
                log("Installation failed: $installResult")
                _isRunning.value = false
                return@withContext
            }

            log("Verifying installation...")
            val startVerify = System.currentTimeMillis()
            val pathResult = runShellCommand("pm path $TARGET_PACKAGE")
            val verifyTime = (System.currentTimeMillis() - startVerify) / 1000.0
            
            if (pathResult.isNotBlank()) {
                log("Package verified in ${String.format("%.1f", verifyTime)} s")
            } else {
                log("Package verification failed")
                _isRunning.value = false
                return@withContext
            }

            log("Launching $TARGET_PACKAGE...")
            val startLaunch = System.currentTimeMillis()
            val launchResult = runShellCommand("monkey -p $TARGET_PACKAGE -c android.intent.category.LAUNCHER 1")
            val launchTime = (System.currentTimeMillis() - startLaunch) / 1000.0
            
            log("Free Fire launched in ${String.format("%.1f", launchTime)} s")
            log("Free Fire ready ✓")

        } catch (e: Exception) {
            log("Error: ${e.message}")
        } finally {
            _isRunning.value = false
        }
    }

    private fun runShellCommand(command: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "Command execution failed: ${e.message}"
        }
    }
}
