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

    suspend fun startReinstall(context: android.content.Context) = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext
        _isRunning.value = true
        clearLogs()
        log("XDEL started")

        val prefs = context.getSharedPreferences("xdel_prefs", android.content.Context.MODE_PRIVATE)
        val useInstallPrompt = prefs.getBoolean("installPrompt", false)

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
                // Backup guest100067.dat before uninstalling
                val guestFile = java.io.File("/storage/emulated/0/xdel/x/guest100067.dat")
                val yDir = java.io.File("/storage/emulated/0/xdel/y")
                
                if (guestFile.exists()) {
                    log("Found guest100067.dat, backing up...")
                    if (!yDir.exists()) yDir.mkdirs()
                    
                    var destFile = java.io.File(yDir, "guest100067.dat")
                    var counter = 1
                    while (destFile.exists()) {
                        destFile = java.io.File(yDir, "guest100067($counter).dat")
                        counter++
                    }
                    
                    try {
                        val moved = guestFile.renameTo(destFile)
                        if (moved) {
                            log("Moved backup to ${destFile.name}")
                        } else {
                            guestFile.copyTo(destFile, overwrite = true)
                            guestFile.delete()
                            log("Copied backup to ${destFile.name}")
                        }
                    } catch (e: Exception) {
                        log("Warning: Failed to backup guest file: ${e.message}")
                    }
                }

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
            
            if (useInstallPrompt) {
                log("Opening system package installer...")
                val apkUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                log("Please complete the installation in the prompt.")
                // We won't be able to easily wait for the prompt to finish. We'll just exit here.
                _isRunning.value = false
                return@withContext
            } else {
                val installResult = runShellCommand("pm install -r -d $APK_PATH")
                val installTime = (System.currentTimeMillis() - startInstall) / 1000.0
                
                if (installResult.contains("Success", ignoreCase = true)) {
                    log("Installation completed in ${String.format("%.1f", installTime)} s")
                } else {
                    log("Installation failed: $installResult")
                    _isRunning.value = false
                    return@withContext
                }
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
