package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions
        if (permissions[Manifest.permission.POST_NOTIFICATIONS] == true) {
            postReinstallNotification()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        checkPermissions()
        
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    XDELScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                postReinstallNotification()
            }
        } else {
            postReinstallNotification()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse(String.format("package:%s", packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }

        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0)
            }
        } catch (e: Exception) {}
    }

    private fun postReinstallNotification() {
        val channelId = "xdel_action_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "XDEL Actions",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val actionIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = "com.example.action.START_REINSTALL"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("XDEL")
            .setContentText("Tap to quick-reinstall Free Fire")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "REINSTALL FREE FIRE",
                    pendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()

        manager.notify(2, notification)
    }

    @Composable
    fun XDELScreen(modifier: Modifier = Modifier) {
        var shizukuState by remember { mutableStateOf("Checking...") }
        var isShizukuOk by remember { mutableStateOf(false) }
        var apkState by remember { mutableStateOf("Checking...") }
        var apkSizeState by remember { mutableStateOf("") }
        var isApkOk by remember { mutableStateOf(false) }
        var freefireState by remember { mutableStateOf("Checking...") }
        
        val isRunning by ReinstallController.isRunning.collectAsState()
        val logs by ReinstallController.logs.collectAsState()

        val checkStatus = {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuState = "Connected"
                    isShizukuOk = true
                } else {
                    shizukuState = "Permission Denied"
                    isShizukuOk = false
                }
            } else {
                shizukuState = "Not Running"
                isShizukuOk = false
            }

            val apkFile = File(ReinstallController.APK_PATH)
            if (apkFile.exists() && apkFile.canRead()) {
                val sizeMb = apkFile.length() / (1024 * 1024)
                apkState = ReinstallController.APK_PATH
                apkSizeState = "~$sizeMb MB"
                isApkOk = true
            } else {
                apkState = "Not found at ${ReinstallController.APK_PATH}"
                apkSizeState = ""
                isApkOk = false
            }

            // Simple check using standard PackageManager for target package
            try {
                packageManager.getPackageInfo(ReinstallController.TARGET_PACKAGE, 0)
                freefireState = "Installed"
            } catch (e: PackageManager.NameNotFoundException) {
                freefireState = "Not Installed"
            }
        }

        LaunchedEffect(Unit, isRunning) {
            while (!isRunning) {
                checkStatus()
                kotlinx.coroutines.delay(1000)
            }
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("XDEL", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            
            StatusItem("Shizuku", shizukuState, isShizukuOk)
            StatusItem("Free Fire", freefireState, freefireState == "Installed")
            
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text("APK", fontWeight = FontWeight.Bold)
                Text(apkState, style = MaterialTheme.typography.bodyMedium, color = if (isApkOk) Color.Unspecified else MaterialTheme.colorScheme.error)
                if (apkSizeState.isNotEmpty()) {
                    Text("Size: $apkSizeState", style = MaterialTheme.typography.bodyMedium)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    val serviceIntent = Intent(this@MainActivity, ReinstallService::class.java).apply {
                        action = ReinstallService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning && isShizukuOk && isApkOk
            ) {
                Text(if (isRunning) "INSTALLING..." else "REINSTALL FREE FIRE")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Operation Log", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { logLine ->
                        Text(
                            text = logLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun StatusItem(title: String, status: String, isOk: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
            val color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            Text("● $status", color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

